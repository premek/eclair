package fr.acinq.eclair.payment

import java.math.BigInteger
import java.nio.ByteOrder

import fr.acinq.bitcoin.Bech32.Int5
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, _}
import fr.acinq.eclair.crypto.BitStream
import fr.acinq.eclair.crypto.BitStream.Bit
import fr.acinq.eclair.payment.PaymentRequest.{Amount, RoutingInfoTag, Timestamp}

import scala.annotation.tailrec
import scala.util.Try

/**
  * Lightning Payment Request
  * see https://github.com/lightningnetwork/lightning-rfc/pull/183
  *
  * @param prefix    currency prefix; lnbc for bitcoin, lntb for bitcoin testnet
  * @param amount    amount to pay (empty string means no amount is specified)
  * @param timestamp request timestamp (UNIX format)
  * @param nodeId    id of the node emitting the payment request
  * @param tags      payment tags; must include a single PaymentHash tag
  * @param signature request signature that will be checked against node id
  */
case class PaymentRequest(prefix: String, amount: Option[MilliSatoshi], timestamp: Long, nodeId: PublicKey, tags: List[PaymentRequest.Tag], signature: BinaryData) {

  amount.map(a => require(a > MilliSatoshi(0) && a <= PaymentRequest.maxAmount, s"amount is not valid"))
  require(tags.collect { case _: PaymentRequest.PaymentHashTag => {} }.size == 1, "there must be exactly one payment hash tag")
  require(tags.collect { case PaymentRequest.DescriptionTag(_) | PaymentRequest.DescriptionHashTag(_) => {} }.size == 1, "there must be exactly one description tag or one description hash tag")

  /**
    *
    * @return the payment hash
    */
  def paymentHash = tags.collectFirst { case p: PaymentRequest.PaymentHashTag => p }.get.hash

  /**
    *
    * @return the description of the payment, or its hash
    */
  def description: Either[String, BinaryData] = tags.collectFirst {
    case PaymentRequest.DescriptionTag(d) => Left(d)
    case PaymentRequest.DescriptionHashTag(h) => Right(h)
  }.get

  /**
    *
    * @return the fallback address if any. It could be a script address, pubkey address, ..
    */
  def fallbackAddress(): Option[String] = tags.collectFirst {
    case PaymentRequest.FallbackAddressTag(17, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.PubkeyAddress, hash)
    case PaymentRequest.FallbackAddressTag(18, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    case PaymentRequest.FallbackAddressTag(17, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, hash)
    case PaymentRequest.FallbackAddressTag(18, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
    case PaymentRequest.FallbackAddressTag(version, hash) if prefix == "lnbc" => Bech32.encodeWitnessAddress("bc", version, hash)
    case PaymentRequest.FallbackAddressTag(version, hash) if prefix == "lntb" => Bech32.encodeWitnessAddress("tb", version, hash)
  }

  def routingInfo(): Seq[RoutingInfoTag] = tags.collect { case t: RoutingInfoTag => t}

  /**
    *
    * @return a representation of this payment request, without its signature, as a bit stream. This is what will be signed.
    */
  def stream: BitStream = {
    val stream = BitStream.empty
    val int5s = Timestamp.encode(timestamp) ++ (tags.map(_.toInt5s).flatten)
    val stream1 = int5s.foldLeft(stream)(PaymentRequest.write5)
    stream1
  }

  /**
    *
    * @return the hash of this payment request
    */
  def hash: BinaryData = Crypto.sha256(s"${prefix}${Amount.encode(amount)}".getBytes("UTF-8") ++ stream.bytes)

  /**
    *
    * @param priv private key
    * @return a signed payment request
    */
  def sign(priv: PrivateKey): PaymentRequest = {
    val (r, s) = Crypto.sign(hash, priv)
    val (pub1, pub2) = Crypto.recoverPublicKey((r, s), hash)
    val recid = if (nodeId == pub1) 0.toByte else 1.toByte
    val signature = PaymentRequest.Signature.encode(r, s, recid)
    this.copy(signature = signature)
  }
}

object PaymentRequest {

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#adding-an-htlc-update_add_htlc
  val maxAmount = MilliSatoshi(4294967296L)

  def apply(chainHash: BinaryData, amount: Option[MilliSatoshi], paymentHash: BinaryData, privateKey: PrivateKey, description: String, fallbackAddress: Option[String] = None, expirySeconds: Option[Long] = None, timestamp: Long = System.currentTimeMillis() / 1000L): PaymentRequest = {
    val prefix = chainHash match {
    case Block.RegtestGenesisBlock.hash => "lntb"
    case Block.TestnetGenesisBlock.hash => "lntb"
    case Block.LivenetGenesisBlock.hash => "lnbc"
    }
    PaymentRequest(
      prefix = prefix,
      amount = amount,
      timestamp = timestamp,
      nodeId = privateKey.publicKey,
      tags = List(
        Some(PaymentHashTag(paymentHash)),
        Some(DescriptionTag(description)),
        expirySeconds.map(ExpiryTag(_))).flatten,
      signature = BinaryData.empty)
      .sign(privateKey)
  }

  sealed trait Tag {
    def toInt5s: Seq[Int5]
  }

  /**
    * Payment Hash Tag
    *
    * @param hash payment hash
    */
  case class PaymentHashTag(hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(hash)
      Seq(Bech32.map('p'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Description Tag
    *
    * @param description a free-format string that will be included in the payment request
    */
  case class DescriptionTag(description: String) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(description.getBytes("UTF-8"))
      Seq(Bech32.map('d'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Hash Tag
    *
    * @param hash hash that will be included in the payment request, and can be checked against the hash of a
    *             long description, an invoice, ...
    */
  case class DescriptionHashTag(hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(hash)
      Seq(Bech32.map('h'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }


  /**
    * Fallback Payment Tag that specifies a fallback payment address to be used if LN payment cannot be processed
    *
    * @param version address version; valid values are
    *                - 17 (pubkey hash)
    *                - 18 (script hash)
    *                - 0 (segwit hash: p2wpkh (20 bytes) or p2wsh (32 bytes))
    * @param hash    address hash
    */
  case class FallbackAddressTag(version: Byte, hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = version +: Bech32.eight2five(hash)
      Seq(Bech32.map('f'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  object FallbackAddressTag {
    /**
      *
      * @param address valid base58 or bech32 address
      * @return a FallbackAddressTag instance
      */
    def apply(address: String): FallbackAddressTag = {
      Try(fromBase58Address(address)).orElse(Try(fromBech32Address(address))).get
    }

    def fromBase58Address(address: String): FallbackAddressTag = {
      val (prefix, hash) = Base58Check.decode(address)
      prefix match {
        case Base58.Prefix.PubkeyAddress => FallbackAddressTag(17, hash)
        case Base58.Prefix.PubkeyAddressTestnet => FallbackAddressTag(17, hash)
        case Base58.Prefix.ScriptAddress => FallbackAddressTag(18, hash)
        case Base58.Prefix.ScriptAddressTestnet => FallbackAddressTag(18, hash)
      }
    }

    def fromBech32Address(address: String): FallbackAddressTag = {
      val (prefix, hash) = Bech32.decodeWitnessAddress(address)
      FallbackAddressTag(prefix, hash)
    }
  }

  /**
    * Routing Info Tag
    *
    * @param pubkey          node id
    * @param channelId       channel id
    * @param fee             node fee
    * @param cltvExpiryDelta node cltv expiry delta
    */
  case class RoutingInfoTag(pubkey: PublicKey, channelId: BinaryData, fee: Long, cltvExpiryDelta: Int) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(pubkey.toBin ++ channelId ++ Protocol.writeUInt64(fee, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(cltvExpiryDelta, ByteOrder.BIG_ENDIAN))
      Seq(Bech32.map('r'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Expiry Date
    *
    * @param seconds expriry data for this payment request
    */
  case class ExpiryTag(seconds: Long) extends Tag {
    override def toInt5s = {
      val ints = Seq((seconds / 32).toByte, (seconds % 32).toByte)
      Seq(Bech32.map('x'), 0.toByte, 2.toByte) ++ ints
    }
  }

  object Amount {

    /**
      * @param amount
      * @return the unit allowing for the shortest representation possible
      */
    def unit(amount: MilliSatoshi): Char = amount.amount * 10 match { // 1 milli-satoshis == 10 pico-bitcoin
      case pico if pico % 1000 > 0 => 'p'
      case pico if pico % 1000000 > 0 => 'n'
      case pico if pico % 1000000000 > 0 => 'u'
      case _ => 'm'
    }

    def decode(input: String): Option[MilliSatoshi] =
      input match {
        case "" => None
        case a if a.last == 'p' => Some(MilliSatoshi(a.dropRight(1).toLong / 10L)) // 1 pico-bitcoin == 10 milli-satoshis
        case a if a.last == 'n' => Some(MilliSatoshi(a.dropRight(1).toLong * 100L))
        case a if a.last == 'u' => Some(MilliSatoshi(a.dropRight(1).toLong * 100000L))
        case a if a.last == 'm' => Some(MilliSatoshi(a.dropRight(1).toLong * 100000000L))
      }

    def encode(amount: Option[MilliSatoshi]): String = {
      amount match {
        case None => ""
        case Some(amt) if unit(amt) == 'p' => s"${amt.amount * 10L}p" // 1 pico-bitcoin == 10 milli-satoshis
        case Some(amt) if unit(amt) == 'n' => s"${amt.amount / 100L}n"
        case Some(amt) if unit(amt) == 'u' => s"${amt.amount / 100000L}u"
        case Some(amt) if unit(amt) == 'm' => s"${amt.amount / 100000000L}m"
      }
    }
  }

  object Tag {
    def parse(input: Seq[Byte]): Tag = {
      val tag = input(0)
      val len = input(1) * 32 + input(2)
      tag match {
        case p if p == Bech32.map('p') =>
          val hash = Bech32.five2eight(input.drop(3).take(52))
          PaymentHashTag(hash)
        case d if d == Bech32.map('d') =>
          val description = new String(Bech32.five2eight(input.drop(3).take(len)).toArray, "UTF-8")
          DescriptionTag(description)
        case h if h == Bech32.map('h') =>
          val hash: BinaryData = Bech32.five2eight(input.drop(3).take(len))
          DescriptionHashTag(hash)
        case f if f == Bech32.map('f') =>
          val version = input(3)
          val prog = Bech32.five2eight(input.drop(4).take(len - 1))
          version match {
            case v if v >= 0 && v <= 16 =>
              FallbackAddressTag(version, prog)
            case 17 | 18 =>
              FallbackAddressTag(version, prog)
          }
        case r if r == Bech32.map('r') =>
          val data = Bech32.five2eight(input.drop(3).take(len))
          val pubkey = PublicKey(data.take(33))
          val channelId = data.drop(33).take(8)
          val fee = Protocol.uint64(data.drop(33 + 8), ByteOrder.BIG_ENDIAN)
          val cltv = Protocol.uint16(data.drop(33 + 8 + 8), ByteOrder.BIG_ENDIAN)
          RoutingInfoTag(pubkey, channelId, fee, cltv)
        case x if x == Bech32.map('x') =>
          require(len == 2, s"invalid length for expiry tag, should be 2 instead of $len")
          val expiry = 32 * input(3) + input(4)
          ExpiryTag(expiry)
      }
    }
  }

  object Timestamp {
    def decode(data: Seq[Int5]): Long = data.take(7).foldLeft(0L)((a, b) => a * 32 + b)

    def encode(timestamp: Long, acc: Seq[Int5] = Nil): Seq[Int5] = if (acc.length == 7) acc else {
      encode(timestamp / 32, (timestamp % 32).toByte +: acc)
    }
  }

  object Signature {
    /**
      *
      * @param signature 65-bytes signatyre: r (32 bytes) | s (32 bytes) | recid (1 bytes)
      * @return a (r, s, recoveryId)
      */
    def decode(signature: BinaryData): (BigInteger, BigInteger, Byte) = {
      require(signature.length == 65)
      val r = new BigInteger(1, signature.take(32).toArray)
      val s = new BigInteger(1, signature.drop(32).take(32).toArray)
      val recid = signature.last
      (r, s, recid)
    }

    /**
      *
      * @return a 65 bytes representation of (r, s, recid)
      */
    def encode(r: BigInteger, s: BigInteger, recid: Byte): BinaryData = {
      Crypto.fixSize(r.toByteArray.dropWhile(_ == 0.toByte)) ++ Crypto.fixSize(s.toByteArray.dropWhile(_ == 0.toByte)) :+ recid
    }
  }

  def toBits(value: Int5) : Seq[Bit] = Seq((value & 16) != 0, (value & 8) != 0, (value & 4) != 0, (value & 2) != 0, (value & 1) != 0)

  /**
    * write a 5bits integer to a stream
    * @param stream stream to write to
    * @param value a 5bits value
    * @return an upated stream
    */
  def write5(stream: BitStream, value: Int5) : BitStream = stream.writeBits(toBits(value))

  /**
    * read a 5bits value from a stream
    * @param stream stream to read from
    * @return a (stream, value) pair
    */
  def read5(stream: BitStream) : (BitStream, Int5) = {
    val (stream1, bits) = stream.readBits(5)
    val value = (if (bits(0)) 1 << 4 else 0) + (if (bits(1)) 1 << 3 else 0) + (if (bits(2)) 1 << 2 else 0) + (if (bits(3)) 1 << 1 else 0) + (if (bits(4)) 1 << 0 else 0)
    (stream1, (value & 0xff).toByte)
  }

  /**
    * splits a bit stream into 5bits values
    * @param stream
    * @param acc
    * @return a sequence of 5bits values
    */
  @tailrec
  def toInt5s(stream: BitStream, acc :Seq[Int5] = Nil) : Seq[Int5] = if (stream.bitCount == 0) acc else {
    val (stream1, value) = read5(stream)
    toInt5s(stream1, acc :+ value)
  }

  /**
    *
    * @param input bech32-encoded payment request
    * @return a payment request
    */
  def read(input: String): PaymentRequest = {
    val (hrp, data) = Bech32.decode(input)
    val stream = data.foldLeft(BitStream.empty)(write5)
    require(stream.bitCount >= 65 * 8, "data is too short to contain a 65 bytes signature")
    val (stream1, sig) = stream.popBytes(65)

    val data0 = toInt5s(stream1)
    val timestamp = Timestamp.decode(data0)
    val data1 = data0.drop(7)

    @tailrec
    def loop(data: Seq[Int5], tags: Seq[Seq[Int5]] = Nil): Seq[Seq[Int5]] = if(data.isEmpty) tags else {
      // 104 is the size of a signature
        val len = 1 + 2 + 32 * data(1) + data(2)
        loop(data.drop(len), tags :+ data.take(len))
    }

    val rawtags = loop(data1)
    val tags = rawtags.map(Tag.parse)
    val signature = sig.reverse
    val r = new BigInteger(1, signature.take(32).toArray)
    val s = new BigInteger(1, signature.drop(32).take(32).toArray)
    val recid = signature.last
    val message: BinaryData = hrp.getBytes ++ stream1.bytes
    val (pub1, pub2) = Crypto.recoverPublicKey((r, s), Crypto.sha256(message))
    val pub = if (recid % 2 != 0) pub2 else pub1
    val prefix = hrp.take(4)
    val amount_opt = Amount.decode(hrp.drop(4))
    val pr = PaymentRequest(prefix, amount_opt, timestamp, pub, tags.toList, signature)
    val validSig = Crypto.verifySignature(Crypto.sha256(message), (r, s), pub)
    require(validSig, "invalid signature")
    pr
  }

  /**
    *
    * @param pr payment request
    * @return a bech32-encoded payment request
    */
  def write(pr: PaymentRequest): String = {
    // currency unit is Satoshi, but we compute amounts in Millisatoshis
    val hramount = Amount.encode(pr.amount)
    val hrp = s"${pr.prefix}$hramount"
    val stream = pr.stream.writeBytes(pr.signature)
    val checksum = Bech32.checksum(hrp, toInt5s(stream))
    hrp + "1" + new String((toInt5s(stream) ++ checksum).map(i => Bech32.pam(i)).toArray)
  }
}

