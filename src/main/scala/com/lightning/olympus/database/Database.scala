package com.lightning.olympus.database

import com.mongodb.casbah.Imports._
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.wallet.ln.Scripts.cltvBlocks
import com.lightning.olympus.Utils.StringVec
import com.lightning.olympus.BlindData
import language.implicitConversions
import java.math.BigInteger
import java.util.Date


abstract class Database {
  // Recording on-chain transactions
  def putTx(txids: Seq[String], prefix: String, hex: String)
  def getTxs(txids: StringVec): StringVec

  // Scheduling txs to spend
  def putScheduled(tx: Transaction): Unit
  def getScheduled(depth: Int): Seq[Transaction]

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Storing arbitrary data in database
  def putData(key: String, data: String)
  def getData(key: String): List[String]
}

class MongoDatabase extends Database {
  implicit def obj2String(source: Object): String = source.toString
  val blindSignatures: MongoDB = MongoClient("localhost")("blindSignatures")
  val olympus: MongoDB = MongoClient("localhost")("olympus")
  val createdAt = "createdAt"

  def putTx(txids: Seq[String], prefix: String, hex: String) =
    olympus("spentTxs").update("prefix" $eq prefix, $set("prefix" -> prefix, "txids" -> txids,
      "hex" -> hex, createdAt -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getTxs(txids: StringVec) =
    olympus("spentTxs").find("txids" $in txids)
      .map(_ as[String] "hex").toVector

  def putScheduled(tx: Transaction) =
    olympus("scheduledTxs") insert MongoDBObject("cltv" -> cltvBlocks(tx),
      "tx" -> Transaction.write(tx).toString, createdAt -> new Date)

  def getScheduled(depth: Int) = {
    val res = olympus("scheduledTxs").find("cltv" $lt depth).map(_ as[String] "tx")
    for (transaction <- res.toList) yield Transaction read BinaryData(transaction)
  }

  // Storing arbitrary data
  def putData(key: String, encryptedData: String) =
    olympus("userData") insert MongoDBObject("key" -> key,
      "data" -> encryptedData, createdAt -> new Date)

  def getData(key: String) = {
    val results = olympus("userData").find("key" $eq key)
    val firstOne = results sort DBObject(createdAt -> -1) take 8
    firstOne.map(_ as[String] "data").toList
  }

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) =
    blindSignatures("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey,
      "paymentHash" -> data.paymentHash.toString, "id" -> data.id, "k" -> data.k.toString,
      "tokens" -> data.tokens, createdAt -> new Date), upsert = true, multi = false,
        WriteConcern.Safe)

  def getPendingTokens(seskey: String) = for {
    result <- blindSignatures("blindTokens").findOne("seskey" $eq seskey)
    tokens = result.get("tokens").asInstanceOf[BasicDBList].map(item => item.toString).toVector
  } yield BlindData(BinaryData(result get "paymentHash"), result get "id", new BigInteger(result get "k"), tokens)

  // Many collections in total to store clear tokens because we have to keep every token
  def putClearToken(clear: String) = blindSignatures("clearTokens" + clear.head).insert("token" $eq clear)
  def isClearTokenUsed(clear: String) = blindSignatures("clearTokens" + clear.head).findOne("token" $eq clear).isDefined
}