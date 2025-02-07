package org.bitcoins.commons

import org.bitcoins.commons.serializers.Picklers
import org.bitcoins.commons.serializers.Picklers._
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.dlc.models.DLCMessage._
import org.bitcoins.core.protocol.dlc.models.{
  DLCState,
  DLCStatus,
  PayoutAddress
}
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.testkitcore.gen.{CryptoGenerators, NumberGenerator, TLVGen}
import org.bitcoins.testkitcore.util.BitcoinSJvmTest
import org.scalacheck.Gen
import upickle.default._

class DLCStatusTest extends BitcoinSJvmTest {
  behavior of "DLCStatus"

  it must "have json symmetry in DLCStatus.Offered" in {
    forAllParallel(NumberGenerator.bool, TLVGen.dlcOfferTLV) {
      case (isInit, offerTLV) =>
        val offer = DLCOffer.fromTLV(offerTLV)

        val totalCollateral = offer.contractInfo.totalCollateral

        val payoutAddress = Option.empty[PayoutAddress]

        val contact = Some("127.0.0.1:0")

        val status =
          DLCStatus.Offered(
            Sha256Digest.empty,
            isInit,
            TimeUtil.now,
            offer.tempContractId,
            offer.contractInfo,
            offer.timeouts,
            offer.feeRate,
            totalCollateral,
            offer.collateral,
            payoutAddress,
            contact
          )

        assert(status.state == DLCState.Offered)
        assert(read[DLCStatus](write(status)) == status)
        assert(read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Accepted" in {
    forAllParallel(NumberGenerator.bool,
                   TLVGen.dlcOfferTLV,
                   NumberGenerator.bytevector) {
      case (isInit, offerTLV, contractId) =>
        val offer = DLCOffer.fromTLV(offerTLV)

        val totalCollateral = offer.contractInfo.totalCollateral

        // random testnet address
        val payoutAddress =
          Some(
            PayoutAddress(BitcoinAddress.fromString(
                            "tb1q4ps6c9ewa7uca5v39fakykq9q6hpgjkxje8gve"),
                          true))

        val contact = Option.empty[String]

        val status =
          DLCStatus.Accepted(
            Sha256Digest.empty,
            isInit,
            TimeUtil.now,
            offer.tempContractId,
            contractId,
            offer.contractInfo,
            offer.timeouts,
            offer.feeRate,
            totalCollateral,
            offer.collateral,
            payoutAddress,
            contact
          )

        assert(status.state == DLCState.Accepted)
        assert(read[DLCStatus](write(status)) == status)
        assert(read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Signed" in {
    forAllParallel(NumberGenerator.bool,
                   TLVGen.dlcOfferTLV,
                   NumberGenerator.bytevector,
                   CryptoGenerators.doubleSha256DigestBE) {
      case (isInit, offerTLV, contractId, txId) =>
        val offer = DLCOffer.fromTLV(offerTLV)

        val totalCollateral = offer.contractInfo.totalCollateral

        val payoutAddress = Option.empty[PayoutAddress]

        val contact = Option.empty[String]

        val status =
          DLCStatus.Signed(
            Sha256Digest.empty,
            isInit,
            TimeUtil.now,
            offer.tempContractId,
            contractId,
            offer.contractInfo,
            offer.timeouts,
            offer.feeRate,
            totalCollateral,
            offer.collateral,
            txId,
            payoutAddress,
            contact
          )

        assert(status.state == DLCState.Signed)
        assert(read[DLCStatus](write(status)) == status)
        assert(read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Broadcasted" in {
    forAllParallel(NumberGenerator.bool,
                   TLVGen.dlcOfferTLV,
                   NumberGenerator.bytevector,
                   CryptoGenerators.doubleSha256DigestBE) {
      case (isInit, offerTLV, contractId, fundingTxId) =>
        val offer = DLCOffer.fromTLV(offerTLV)

        val totalCollateral = offer.contractInfo.totalCollateral

        val payoutAddress = Option.empty[PayoutAddress]

        val contact = Option.empty[String]

        val status =
          DLCStatus.Broadcasted(
            Sha256Digest.empty,
            isInit,
            TimeUtil.now,
            offer.tempContractId,
            contractId,
            offer.contractInfo,
            offer.timeouts,
            offer.feeRate,
            totalCollateral,
            offer.collateral,
            fundingTxId,
            payoutAddress,
            contact
          )

        assert(status.state == DLCState.Broadcasted)
        assert(read[DLCStatus](write(status)) == status)
        assert(read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Confirmed" in {
    forAllParallel(NumberGenerator.bool,
                   TLVGen.dlcOfferTLV,
                   NumberGenerator.bytevector,
                   CryptoGenerators.doubleSha256DigestBE) {
      case (isInit, offerTLV, contractId, fundingTxId) =>
        val offer = DLCOffer.fromTLV(offerTLV)

        val totalCollateral = offer.contractInfo.totalCollateral

        val payoutAddress = Option.empty[PayoutAddress]

        val contact = Option.empty[String]

        val status =
          DLCStatus.Confirmed(
            Sha256Digest.empty,
            isInit,
            TimeUtil.now,
            offer.tempContractId,
            contractId,
            offer.contractInfo,
            offer.timeouts,
            offer.feeRate,
            totalCollateral,
            offer.collateral,
            fundingTxId,
            payoutAddress,
            contact
          )

        assert(status.state == DLCState.Confirmed)
        assert(read[DLCStatus](write(status)) == status)
        assert(read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Claimed" in {
    forAllParallel(
      NumberGenerator.bool,
      TLVGen.dlcOfferTLV,
      NumberGenerator.bytevector,
      CryptoGenerators.doubleSha256DigestBE,
      CryptoGenerators.doubleSha256DigestBE,
      Gen.listOf(CryptoGenerators.schnorrDigitalSignature)
    ) { case (isInit, offerTLV, contractId, fundingTxId, closingTxId, sigs) =>
      val offer = DLCOffer.fromTLV(offerTLV)

      val totalCollateral = offer.contractInfo.totalCollateral
      val randomMyPayout =
        Math.abs(scala.util.Random.nextLong() % totalCollateral.toLong)
      val myPayout: CurrencyUnit = Satoshis(randomMyPayout)

      val theirPayout: CurrencyUnit = totalCollateral - myPayout
      val rand =
        scala.util.Random.nextInt(offer.contractInfo.allOutcomes.size)
      val outcome = offer.contractInfo.allOutcomes(rand)

      val payoutAddress = Option.empty[PayoutAddress]

      val contact = Option.empty[String]

      val status =
        DLCStatus.Claimed(
          Sha256Digest.empty,
          isInit,
          TimeUtil.now,
          offer.tempContractId,
          contractId,
          offer.contractInfo,
          offer.timeouts,
          offer.feeRate,
          totalCollateral,
          offer.collateral,
          fundingTxId,
          closingTxId,
          sigs.toVector,
          outcome,
          myPayout = myPayout,
          counterPartyPayout = theirPayout,
          payoutAddress = payoutAddress,
          contact
        )

      assert(status.state == DLCState.Claimed)
      assert(read[DLCStatus](write(status)) == status)
      assert(
        read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.RemoteClaimed" in {
    forAllParallel(
      NumberGenerator.bool,
      TLVGen.dlcOfferTLV,
      NumberGenerator.bytevector,
      CryptoGenerators.doubleSha256DigestBE,
      CryptoGenerators.doubleSha256DigestBE,
      CryptoGenerators.schnorrDigitalSignature
    ) { case (isInit, offerTLV, contractId, fundingTxId, closingTxId, sig) =>
      val offer = DLCOffer.fromTLV(offerTLV)

      val totalCollateral = offer.contractInfo.totalCollateral

      val randomMyPayout =
        Math.abs(scala.util.Random.nextLong() % totalCollateral.toLong)
      val myPayout: CurrencyUnit = Satoshis(randomMyPayout)

      val theirPayout: CurrencyUnit = totalCollateral - myPayout

      val rand =
        scala.util.Random.nextInt(offer.contractInfo.allOutcomes.size)
      val outcome = offer.contractInfo.allOutcomes(rand)

      val payoutAddress = Option.empty[PayoutAddress]

      val contact = Option.empty[String]

      val status =
        DLCStatus.RemoteClaimed(
          Sha256Digest.empty,
          isInit,
          TimeUtil.now,
          offer.tempContractId,
          contractId,
          offer.contractInfo,
          offer.timeouts,
          offer.feeRate,
          totalCollateral,
          offer.collateral,
          fundingTxId,
          closingTxId,
          sig,
          outcome,
          myPayout = myPayout,
          counterPartyPayout = theirPayout,
          payoutAddress = payoutAddress,
          contact
        )

      assert(status.state == DLCState.RemoteClaimed)
      assert(read[DLCStatus](write(status)) == status)
      assert(
        read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }

  it must "have json symmetry in DLCStatus.Refunded" in {
    forAllParallel(
      NumberGenerator.bool,
      TLVGen.dlcOfferTLV,
      NumberGenerator.bytevector,
      CryptoGenerators.doubleSha256DigestBE,
      CryptoGenerators.doubleSha256DigestBE
    ) { case (isInit, offerTLV, contractId, fundingTxId, closingTxId) =>
      val offer = DLCOffer.fromTLV(offerTLV)

      val totalCollateral = offer.contractInfo.totalCollateral

      val randomMyPayout =
        Math.abs(scala.util.Random.nextLong() % totalCollateral.toLong)
      val myPayout: CurrencyUnit =
        Satoshis(randomMyPayout)

      val theirPayout: CurrencyUnit = totalCollateral - myPayout

      val payoutAddress = Option.empty[PayoutAddress]

      val contact = Option.empty[String]

      val status =
        DLCStatus.Refunded(
          Sha256Digest.empty,
          isInit,
          TimeUtil.now,
          offer.tempContractId,
          contractId,
          offer.contractInfo,
          offer.timeouts,
          offer.feeRate,
          totalCollateral,
          offer.collateral,
          fundingTxId,
          closingTxId,
          myPayout = myPayout,
          counterPartyPayout = theirPayout,
          payoutAddress = payoutAddress,
          contact
        )

      assert(status.state == DLCState.Refunded)
      assert(read[DLCStatus](write(status)) == status)
      assert(
        read[DLCStatus](
          write(status.asInstanceOf[DLCStatus])(Picklers.dlcStatusW)) == status)
    }
  }
}
