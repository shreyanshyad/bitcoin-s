package org.bitcoins.crypto

import scodec.bits.ByteVector

import java.math.BigInteger

/** Represents a point on the secp256k1 elliptic curve. */
sealed trait SecpPoint extends NetworkElement {

  /** Returns the group sum of this point and the input. */
  def add(point: SecpPoint): SecpPoint = {
    CryptoUtil.add(this, point)
  }

  def multiply(fieldElement: FieldElement): SecpPoint = {
    CryptoUtil.tweakMultiply(this, fieldElement)
  }

  def negate: SecpPoint = {
    this match {
      case SecpPointInfinity  => SecpPointInfinity
      case p: SecpPointFinite => p.toPublicKey.negate.toPoint
    }
  }
}

/** The point at infinity, this is the secp256k1 group identity element meaning
  * p + 0x00 = 0x00 + p = p for any point p and
  * p + (-p) = 0x00.
  *
  * Note that this does not correspond to a valid ECPublicKey just like
  * FieldElement.zero does not correspond to a valid private key (and in fact
  * 0x00 = FieldElement.zero*G).
  */
case object SecpPointInfinity extends SecpPoint {
  override val bytes: ByteVector = ByteVector(0x00)
}

/** A non-identity point, (x, y), on the secp256k1 elliptic curve.
  */
case class SecpPointFinite(x: CurveCoordinate, y: CurveCoordinate)
    extends SecpPoint {

  override def bytes: ByteVector = {
    ByteVector(0x04) ++ x.bytes ++ y.bytes
  }

  def toPublicKey: ECPublicKey = {
    ECPublicKey(bytes)
  }
}

object SecpPoint {

  def fromPublicKey(key: ECPublicKey): SecpPointFinite = {
    val (x, y) = key.decompressedBytes.tail.splitAt(32)
    SecpPointFinite(CurveCoordinate.fromBytes(x), CurveCoordinate.fromBytes(y))
  }

  def apply(x: ByteVector, y: ByteVector): SecpPointFinite =
    SecpPointFinite(CurveCoordinate.fromBytes(x), CurveCoordinate.fromBytes(y))

  def apply(x: Array[Byte], y: Array[Byte]): SecpPointFinite =
    SecpPointFinite(CurveCoordinate.fromByteArray(x),
                    CurveCoordinate.fromByteArray(y))

  def apply(x: BigInteger, y: BigInteger): SecpPointFinite =
    SecpPointFinite(CurveCoordinate(x), CurveCoordinate(y))

  def apply(x: BigInt, y: BigInt): SecpPointFinite =
    SecpPointFinite(CurveCoordinate(x), CurveCoordinate(y))

  def apply(x: String, y: String): SecpPointFinite =
    SecpPointFinite(CurveCoordinate.fromHex(x), CurveCoordinate.fromHex(y))
}
