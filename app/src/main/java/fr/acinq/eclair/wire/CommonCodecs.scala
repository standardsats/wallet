package fr.acinq.eclair.wire

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.crypto.Mac32
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, UInt64}
import org.apache.commons.codec.binary.Base32
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

import java.net.{Inet4Address, Inet6Address, InetAddress}
import scala.Ordering.Implicits._
import scala.util.Try


object CommonCodecs {
  def discriminatorWithDefault[A](discriminator: Codec[A], fallback: Codec[A]): Codec[A] = new Codec[A] {
    def sizeBound: SizeBound = discriminator.sizeBound | fallback.sizeBound

    def encode(e: A): Attempt[BitVector] = discriminator.encode(e).recoverWith { case _ => fallback.encode(e) }

    def decode(b: BitVector): Attempt[DecodeResult[A]] = discriminator.decode(b).recoverWith {
      case _: KnownDiscriminatorType[_]#UnknownDiscriminator => fallback.decode(b)
    }
  }

  // this codec can be safely used for values < 2^63 and will fail otherwise
  // (for something smarter see https://github.com/yzernik/bitcoin-scodec/blob/master/src/main/scala/io/github/yzernik/bitcoinscodec/structures/UInt64.scala)
  val uint64overflow: Codec[Long] = int64.narrow(l => if (l >= 0) Attempt.Successful(l) else Attempt.failure(Err(s"overflow for value $l")), l => l)
  val uint64: Codec[UInt64] = bytes(8).xmap(b => UInt64(b), a => a.toByteVector.padLeft(8))

  val satoshi: Codec[Satoshi] = uint64overflow.xmapc(l => Satoshi(l))(_.toLong)
  val millisatoshi: Codec[MilliSatoshi] = uint64overflow.xmapc(l => MilliSatoshi(l))(_.toLong)

  val feeratePerKw: Codec[FeeratePerKw] = uint32.xmapc(l => FeeratePerKw(Satoshi(l)))(_.toLong)

  val cltvExpiry: Codec[CltvExpiry] = uint32.xmapc(CltvExpiry)(_.underlying)
  val cltvExpiryDelta: Codec[CltvExpiryDelta] = uint16.xmapc(CltvExpiryDelta)(_.underlying)

  // this is needed because some millisatoshi values are encoded on 32 bits in the BOLTs
  // this codec will fail if the amount does not fit on 32 bits
  val millisatoshi32: Codec[MilliSatoshi] = uint32.xmapc(l => MilliSatoshi(l))(_.toLong)

  val text: Codec[String] = variableSizeBytes(uint16, utf8)
  val optionalText: Codec[Option[String]] = optional(bool, text)

  /**
   * We impose a minimal encoding on some values (such as varint and truncated int) to ensure that signed hashes can be
   * re-computed correctly.
   * If a value could be encoded with less bytes, it's considered invalid and results in a failed decoding attempt.
   *
   * @param codec the value codec (depends on the value).
   * @param min   the minimal value that should be encoded.
   */
  def minimalvalue[A: Ordering](codec: Codec[A], min: A): Codec[A] = codec.exmap({
    case i if i < min => Attempt.failure(Err("value was not minimally encoded"))
    case i => Attempt.successful(i)
  }, Attempt.successful)

  // Bitcoin-style varint codec (CompactSize).
  // See https://bitcoin.org/en/developer-reference#compactsize-unsigned-integers for reference.
  val varint: Codec[UInt64] = discriminatorWithDefault(
    discriminated[UInt64].by(uint8L)
      .\(0xff) { case i if i >= UInt64(0x100000000L) => i }(minimalvalue(uint64, UInt64(0x100000000L)))
      .\(0xfe) { case i if i >= UInt64(0x10000) => i }(minimalvalue(uint32.xmap(UInt64(_), _.toBigInt.toLong), UInt64(0x10000)))
      .\(0xfd) { case i if i >= UInt64(0xfd) => i }(minimalvalue(uint16.xmap(UInt64(_), _.toBigInt.toInt), UInt64(0xfd))),
    uint8L.xmap(UInt64(_), _.toBigInt.toInt)
  )

  // This codec can be safely used for values < 2^63 and will fail otherwise.
  // It is useful in combination with variableSizeBytesLong to encode/decode TLV lengths because those will always be < 2^63.
  val varintoverflow: Codec[Long] = varint.narrow(l => if (l <= UInt64(Long.MaxValue)) Attempt.successful(l.toBigInt.toLong) else Attempt.failure(Err(s"overflow for value $l")), l => UInt64(l))

  val bytes32: Codec[ByteVector32] = limitedSizeBytes(32, bytesStrict(32).xmap(d => ByteVector32(d), d => d.bytes))

  val bytes64: Codec[ByteVector64] = limitedSizeBytes(64, bytesStrict(64).xmap(d => ByteVector64(d), d => d.bytes))

  val sha256: Codec[ByteVector32] = bytes32

  val varsizebinarydata: Codec[ByteVector] = variableSizeBytes(uint16, bytes)

  def mapCodec[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Map[K, V]] = listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

  def setCodec[T](codec: Codec[T]): Codec[Set[T]] = listOfN(uint16, codec).xmap(_.toSet, _.toList)

  val listofsignatures: Codec[List[ByteVector64]] = listOfN(uint16, bytes64)

  val ipv4address: Codec[Inet4Address] = bytes(4).xmap(b => InetAddress.getByAddress(b.toArray).asInstanceOf[Inet4Address], a => ByteVector(a.getAddress))

  val ipv6address: Codec[Inet6Address] = bytes(16).exmap(b => Attempt.fromTry(Try(Inet6Address.getByAddress(null, b.toArray, null))), a => Attempt.fromTry(Try(ByteVector(a.getAddress))))

  def base32(size: Int): Codec[String] = bytes(size).xmap(b => new Base32().encodeAsString(b.toArray).toLowerCase, a => ByteVector(new Base32().decode(a.toUpperCase())))

  val nodeaddress: Codec[NodeAddress] =
    discriminated[NodeAddress].by(uint8)
      .typecase(1, (ipv4address :: uint16).as[IPv4])
      .typecase(2, (ipv6address :: uint16).as[IPv6])
      .typecase(3, (base32(10) :: uint16).as[Tor2])
      .typecase(4, (base32(35) :: uint16).as[Tor3])
      .typecase(5, (zeropaddedstring(64) :: uint16).as[Domain])

  // this one is a bit different from most other codecs: the first 'len' element is *not* the number of items
  // in the list but rather the  number of bytes of the encoded list. The rationale is once we've read this
  // number of bytes we can just skip to the next field
  val listofnodeaddresses: Codec[List[NodeAddress]] = variableSizeBytes(uint16, list(nodeaddress))

  val privateKey: Codec[PrivateKey] = Codec[PrivateKey](
    (priv: PrivateKey) => bytes(32).encode(priv.value),
    (wire: BitVector) => bytes(32).decode(wire).map(_.map(b => PrivateKey(b)))
  )

  val publicKey: Codec[PublicKey] = Codec[PublicKey](
    (pub: PublicKey) => bytes(33).encode(pub.value),
    (wire: BitVector) => bytes(33).decode(wire).map(_.map(b => PublicKey(b)))
  )

  val rgb: Codec[Color] = bytes(3).xmap(buf => Color(buf(0), buf(1), buf(2)), t => ByteVector(t.r, t.g, t.b))

  def zeropaddedstring(size: Int): Codec[String] = fixedSizeBytes(size, utf8).xmap(s => s.takeWhile(_ != '\u0000'), s => s)

  /**
   * When encoding, prepend a valid mac to the output of the given codec.
   * When decoding, verify that a valid mac is prepended.
   */
  def prependmac[A](codec: Codec[A], mac: Mac32): Codec[A] = Codec[A](
    (a: A) => codec.encode(a).map(bits => mac.mac(bits.toByteVector).bits ++ bits),
    (bits: BitVector) => ("mac" | bytes32).decode(bits) match {
      case Attempt.Successful(DecodeResult(msgMac, remainder)) if mac.verify(msgMac, remainder.toByteVector) => codec.decode(remainder)
      case Attempt.Successful(_) => Attempt.Failure(scodec.Err("invalid mac"))
      case Attempt.Failure(err) => Attempt.Failure(err)
    }
  )

}
