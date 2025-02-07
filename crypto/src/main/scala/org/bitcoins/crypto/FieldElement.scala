package org.bitcoins.crypto

import scodec.bits.ByteVector

import scala.util.Try

/** Represents integers modulo the secp256k1 field size: pow(2,256) - 0x1000003D1.
  *
  * Supports arithmetic for these elements including +, -, *, and inverses.
  * Supports 32 byte serialization as is needed for ECPrivateKeys.
  */
case class FieldElement(bytes: ByteVector)
    extends FiniteFieldMember[FieldElement](CryptoParams.getN, 32) {
  private val privKeyT: Try[ECPrivateKey] = Try(ECPrivateKey(bytes))

  require(
    privKeyT.isSuccess || isZero,
    s"$bytes is not a valid field element: ${privKeyT.failed.get.getMessage}")

  def toPrivateKey: ECPrivateKey =
    if (!isZero) {
      privKeyT.get
    } else {
      throw new RuntimeException("Cannot turn zero into a private key")
    }

  def getPublicKey: ECPublicKey = toPrivateKey.publicKey

  override def fieldObj: FiniteFieldObject[FieldElement] = FieldElement

  override def toString: String = s"FieldElement(${bytes.toHex})"
}

object FieldElement
    extends FiniteFieldObject[FieldElement](CryptoParams.getN, 32) {

  override def fieldMemberConstructor(bytes: ByteVector): FieldElement = {
    new FieldElement(bytes)
  }

  def computePoint(fe: FieldElement): ECPublicKey =
    CryptoParams.getG.multiply(fe)
}
