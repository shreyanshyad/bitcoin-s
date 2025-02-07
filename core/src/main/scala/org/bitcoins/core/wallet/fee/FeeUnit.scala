package org.bitcoins.core.wallet.fee

import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto.StringFactory

/** This is meant to be an abstract type that represents different fee unit measurements for
  * blockchains
  */
sealed abstract class FeeUnit {
  def currencyUnit: CurrencyUnit
  final def *(cu: CurrencyUnit): CurrencyUnit = this * cu.satoshis.toLong
  final def *(int: Int): CurrencyUnit = this * int.toLong

  final def *(long: Long): CurrencyUnit = Satoshis(toLong * long / scaleFactor)
  def *(tx: Transaction): CurrencyUnit = calc(tx)

  def factory: FeeUnitFactory[FeeUnit]

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  def scaleFactor: Long = factory.scaleFactor

  require(scaleFactor > 0,
          s"Scale factor cannot be less than or equal to 0, got $scaleFactor")

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  def txSizeForCalc(tx: Transaction): Long = factory.txSizeForCalc(tx)

  /** Calculates the fee for the transaction using this fee rate, rounds down satoshis */
  final def calc(tx: Transaction): CurrencyUnit = this * txSizeForCalc(tx)
  def toLong: Long = currencyUnit.satoshis.toLong

  override def toString: String = s"$toLong ${factory.unitString}"

  /** Converts the current fee unit to sats/vybte */
  def toSatsPerVByte: SatoshisPerVirtualByte
}

trait FeeUnitFactory[+T <: FeeUnit] {

  /** String to identify this fee unit, for example "sats/byte" */
  def unitString: String

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  def scaleFactor: Long

  require(scaleFactor > 0,
          s"Scale factor cannot be less than or equal to 0, got $scaleFactor")

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  def txSizeForCalc(tx: Transaction): Long

  /** Creates an instance T where the value given is the numerator of the fee unit
    */
  def fromLong(long: Long): T

  /** Calculates the fee rate from the given Transaction.
    * Uses Math.round for rounding up, which returns the closest long to the argument
    * @param totalInputAmount Total amount being spent by the transaction's inputs
    * @param tx Transaction to calculate the fee rate of
    */
  def calc(totalInputAmount: CurrencyUnit, tx: Transaction): T = {
    val feePaid = totalInputAmount - tx.totalOutput
    val feeRate = feePaid.satoshis.toLong / txSizeForCalc(tx).toDouble

    fromLong(Math.round(feeRate * scaleFactor))
  }
}

/** Meant to represent the different fee unit types for the bitcoin protocol
  * @see [[https://en.bitcoin.it/wiki/Weight_units]]
  */
sealed abstract class BitcoinFeeUnit extends FeeUnit

case class SatoshisPerByte(currencyUnit: CurrencyUnit) extends BitcoinFeeUnit {

  lazy val toSatPerKb: SatoshisPerKiloByte = {
    SatoshisPerKiloByte(currencyUnit.satoshis * Satoshis(1000))
  }

  override def factory: FeeUnitFactory[SatoshisPerByte] = SatoshisPerByte

  override val toSatsPerVByte: SatoshisPerVirtualByte = SatoshisPerVirtualByte(
    currencyUnit)
}

object SatoshisPerByte extends FeeUnitFactory[SatoshisPerByte] {

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  override lazy val scaleFactor: Long = 1

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  override def txSizeForCalc(tx: Transaction): Long = tx.byteSize

  override def fromLong(sats: Long): SatoshisPerByte =
    SatoshisPerByte(Satoshis(sats))

  val zero: SatoshisPerByte = SatoshisPerByte(Satoshis.zero)
  val one: SatoshisPerByte = SatoshisPerByte(Satoshis.one)

  override val unitString: String = "sats/byte"
}

/** KiloBytes here are defined as 1000 bytes.
  */
case class SatoshisPerKiloByte(currencyUnit: CurrencyUnit)
    extends BitcoinFeeUnit {

  lazy val toSatPerByteExact: SatoshisPerByte = {
    val conversionOpt = (currencyUnit.toBigDecimal / 1000.0).toBigIntExact
    conversionOpt match {
      case Some(conversion) =>
        val sat = Satoshis(conversion)
        SatoshisPerByte(sat)

      case None =>
        throw new RuntimeException(
          s"Failed to convert sat/kb -> sat/byte (loss of precision) for $currencyUnit")
    }
  }

  lazy val toSatPerByteRounded: SatoshisPerByte = {
    val conversion = (currencyUnit.toBigDecimal / 1000.0).toBigInt
    SatoshisPerByte(Satoshis(conversion))
  }

  lazy val toSatPerByte: SatoshisPerByte = toSatPerByteExact

  /** Converts sats/kb -> sats/vbyte with rounding if necessary. */
  override val toSatsPerVByte: SatoshisPerVirtualByte =
    toSatPerByteRounded.toSatsPerVByte

  override def factory: FeeUnitFactory[SatoshisPerKiloByte] =
    SatoshisPerKiloByte

}

object SatoshisPerKiloByte extends FeeUnitFactory[SatoshisPerKiloByte] {

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  override lazy val scaleFactor: Long = 1000

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  override def txSizeForCalc(tx: Transaction): Long = tx.byteSize

  override def fromLong(sats: Long): SatoshisPerKiloByte =
    SatoshisPerKiloByte(Satoshis(sats))

  val zero: SatoshisPerKiloByte = SatoshisPerKiloByte(Satoshis.zero)
  val one: SatoshisPerKiloByte = SatoshisPerKiloByte(Satoshis.one)

  override val unitString: String = "sats/kb"
}

/** A 'virtual byte' (also known as virtual size) is a new weight measurement that
  * was created with segregated witness (BIP141). Now 1 'virtual byte'
  * has the weight of 4 bytes in the [[org.bitcoins.core.protocol.transaction.TransactionWitness]]
  * of a [[org.bitcoins.core.protocol.transaction.WitnessTransaction]]
  */
case class SatoshisPerVirtualByte(currencyUnit: CurrencyUnit)
    extends BitcoinFeeUnit {

  override def factory: FeeUnitFactory[SatoshisPerVirtualByte] =
    SatoshisPerVirtualByte

  override def toString: String = s"$toLong sats/vbyte"

  lazy val toSatoshisPerKW: SatoshisPerKW = SatoshisPerKW(currencyUnit * 250)

  override val toSatsPerVByte: SatoshisPerVirtualByte = this

}

object SatoshisPerVirtualByte extends FeeUnitFactory[SatoshisPerVirtualByte] {

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  override lazy val scaleFactor: Long = 1

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  override def txSizeForCalc(tx: Transaction): Long = tx.vsize

  override def fromLong(sats: Long): SatoshisPerVirtualByte =
    SatoshisPerVirtualByte(Satoshis(sats))

  val zero: SatoshisPerVirtualByte = SatoshisPerVirtualByte(CurrencyUnits.zero)
  val one: SatoshisPerVirtualByte = SatoshisPerVirtualByte(Satoshis.one)

  /** Used to indicate we could not retrieve a fee from a [[org.bitcoins.core.api.feeprovider.FeeRateApi]] */
  val negativeOne: SatoshisPerVirtualByte = SatoshisPerVirtualByte(Satoshis(-1))

  override val unitString: String = "sats/vbyte"
}

/** Weight is used to indicate how 'expensive' the transaction is on the blockchain.
  * This use to be a simple calculation before segwit (BIP141). Each byte in the transaction
  * counted as 4 'weight' units. Now with segwit, the
  * [[org.bitcoins.core.protocol.transaction.TransactionWitness TransactionWitness]]
  * is counted as 1 weight unit per byte,
  * while other parts of the transaction (outputs, inputs, locktime etc) count as 4 weight units.
  * As we add more witness versions, this may be subject to change.
  * [[https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki#Transaction_size_calculations BIP 141]]
  * [[https://github.com/bitcoin/bitcoin/blob/5961b23898ee7c0af2626c46d5d70e80136578d3/src/consensus/validation.h#L96]]
  */
case class SatoshisPerKW(currencyUnit: CurrencyUnit) extends BitcoinFeeUnit {

  override def factory: FeeUnitFactory[SatoshisPerKW] = SatoshisPerKW

  override def toString: String = s"$toLong sats/kw"

  override val toSatsPerVByte: SatoshisPerVirtualByte = SatoshisPerVirtualByte(
    currencyUnit / Satoshis(250))
}

object SatoshisPerKW extends FeeUnitFactory[SatoshisPerKW] {

  /** The coefficient the denominator in the unit is multiplied by,
    * for example sats/kilobyte -> 1000
    */
  override lazy val scaleFactor: Long = 1000

  /** Takes the given transaction returns a size that will be used for calculating the fee rate.
    * This is generally the denominator in the unit, ie sats/byte
    */
  override def txSizeForCalc(tx: Transaction): Long = tx.weight

  override def fromLong(sats: Long): SatoshisPerKW =
    SatoshisPerKW(Satoshis(sats))

  val zero: SatoshisPerKW = SatoshisPerKW(CurrencyUnits.zero)
  val one: SatoshisPerKW = SatoshisPerKW(Satoshis.one)

  override val unitString: String = "sats/kw"
}

object FeeUnit extends StringFactory[FeeUnit] {

  val factories = Vector(SatoshisPerVirtualByte,
                         SatoshisPerByte,
                         SatoshisPerKiloByte,
                         SatoshisPerKW)

  override def fromString(string: String): FeeUnit = {
    val arr = string.split(" ")

    val unit = arr.last
    val feeUnitOpt = factories.find(_.unitString == unit).map { fac =>
      val long = arr.head.toLong
      fac.fromLong(long)
    }

    feeUnitOpt match {
      case Some(feeUnit) => feeUnit
      case None =>
        sys.error(s"Could not parse $string as a fee unit")
    }
  }
}
