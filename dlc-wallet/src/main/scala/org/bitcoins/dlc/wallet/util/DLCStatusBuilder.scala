package org.bitcoins.dlc.wallet.util

import org.bitcoins.core.api.dlc.wallet.db.DLCDb
import org.bitcoins.core.api.wallet.db.TransactionDb
import org.bitcoins.core.dlc.accounting.DLCAccounting
import org.bitcoins.core.protocol.dlc.models.DLCStatus._
import org.bitcoins.core.protocol.dlc.models._
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto.SchnorrDigitalSignature
import org.bitcoins.dlc.wallet.accounting.{AccountingUtil, DLCAccountingDbs}
import org.bitcoins.dlc.wallet.models._

/** Creates a case class that represents all DLC data from dlcdb.sqlite
  * Unfortunately we have to read some data from walletdb.sqlite to build a
  * full [[DLCStatus]]
  * @see https://github.com/bitcoin-s/bitcoin-s/pull/4555#issuecomment-1200113188
  */
case class IntermediaryDLCStatus(
    dlcDb: DLCDb,
    contractInfo: ContractInfo,
    contractData: DLCContractDataDb,
    offerDb: DLCOfferDb,
    acceptDbOpt: Option[DLCAcceptDb],
    nonceDbs: Vector[OracleNonceDb],
    announcementsWithId: Vector[(OracleAnnouncementV0TLV, Long)],
    announcementIds: Vector[DLCAnnouncementDb]
) {

  def complete(
      payoutAddressOpt: Option[PayoutAddress],
      closingTxOpt: Option[TransactionDb]): DLCStatus = {
    val dlcId = dlcDb.dlcId
    dlcDb.state match {
      case _: DLCState.InProgressState =>
        DLCStatusBuilder.buildInProgressDLCStatus(dlcDb = dlcDb,
                                                  contractInfo = contractInfo,
                                                  contractData = contractData,
                                                  offerDb = offerDb,
                                                  payoutAddress =
                                                    payoutAddressOpt)
      case _: DLCState.ClosedState =>
        (acceptDbOpt, closingTxOpt) match {
          case (Some(acceptDb), Some(closingTx)) =>
            DLCStatusBuilder.buildClosedDLCStatus(
              dlcDb = dlcDb,
              contractInfo = contractInfo,
              contractData = contractData,
              announcementsWithId = announcementsWithId,
              announcementIds = announcementIds,
              nonceDbs = nonceDbs,
              offerDb = offerDb,
              acceptDb = acceptDb,
              closingTx = closingTx.transaction,
              payoutAddress = payoutAddressOpt
            )
          case (None, None) =>
            throw new RuntimeException(
              s"Could not find acceptDb or closingTx for closing state=${dlcDb.state} dlcId=$dlcId")
          case (Some(_), None) =>
            throw new RuntimeException(
              s"Could not find closingTx for state=${dlcDb.state} dlcId=$dlcId")
          case (None, Some(_)) =>
            throw new RuntimeException(
              s"Cannot find acceptDb for dlcId=$dlcId. This likely means we have data corruption")
        }
    }
  }
}

object DLCStatusBuilder {

  /** Helper method to convert a bunch of indepdendent datastructures into a in progress dlc status */
  def buildInProgressDLCStatus(
      dlcDb: DLCDb,
      contractInfo: ContractInfo,
      contractData: DLCContractDataDb,
      offerDb: DLCOfferDb,
      payoutAddress: Option[PayoutAddress]): DLCStatus = {
    require(
      dlcDb.state.isInstanceOf[DLCState.InProgressState],
      s"Cannot have divergent states between dlcDb and the parameter state, got= dlcDb.state=${dlcDb.state} state=${dlcDb.state}"
    )
    val dlcId = dlcDb.dlcId

    val totalCollateral = contractData.totalCollateral

    val localCollateral = if (dlcDb.isInitiator) {
      offerDb.collateral
    } else {
      totalCollateral - offerDb.collateral
    }

    val status = dlcDb.state.asInstanceOf[DLCState.InProgressState] match {
      case DLCState.Offered =>
        Offered(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.AcceptComputingAdaptorSigs =>
        AcceptedComputingAdaptorSigs(
          dlcId = dlcId,
          isInitiator = dlcDb.isInitiator,
          lastUpdated = dlcDb.lastUpdated,
          tempContractId = dlcDb.tempContractId,
          contractId = dlcDb.contractIdOpt.get,
          contractInfo = contractInfo,
          timeouts = contractData.dlcTimeouts,
          feeRate = dlcDb.feeRate,
          totalCollateral = totalCollateral,
          localCollateral = localCollateral,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.Accepted =>
        Accepted(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          dlcDb.contractIdOpt.get,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.SignComputingAdaptorSigs =>
        SignedComputingAdaptorSigs(
          dlcId = dlcId,
          isInitiator = dlcDb.isInitiator,
          lastUpdated = dlcDb.lastUpdated,
          tempContractId = dlcDb.tempContractId,
          contractId = dlcDb.contractIdOpt.get,
          contractInfo = contractInfo,
          timeouts = contractData.dlcTimeouts,
          feeRate = dlcDb.feeRate,
          totalCollateral = totalCollateral,
          localCollateral = localCollateral,
          dlcDb.fundingTxIdOpt.get,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.Signed =>
        Signed(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          dlcDb.contractIdOpt.get,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          dlcDb.fundingTxIdOpt.get,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.Broadcasted =>
        Broadcasted(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          dlcDb.contractIdOpt.get,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          dlcDb.fundingTxIdOpt.get,
          payoutAddress,
          dlcDb.peerOpt
        )
      case DLCState.Confirmed =>
        Confirmed(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          dlcDb.contractIdOpt.get,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          dlcDb.fundingTxIdOpt.get,
          payoutAddress,
          dlcDb.peerOpt
        )
    }

    status
  }

  def buildClosedDLCStatus(
      dlcDb: DLCDb,
      contractInfo: ContractInfo,
      contractData: DLCContractDataDb,
      nonceDbs: Vector[OracleNonceDb],
      announcementsWithId: Vector[(OracleAnnouncementV0TLV, Long)],
      announcementIds: Vector[DLCAnnouncementDb],
      offerDb: DLCOfferDb,
      acceptDb: DLCAcceptDb,
      closingTx: Transaction,
      payoutAddress: Option[PayoutAddress]): ClosedDLCStatus = {
    require(
      dlcDb.state.isInstanceOf[DLCState.ClosedState],
      s"Cannot have divergent states beteween dlcDb and the parameter state, got= dlcDb.state=${dlcDb.state} state=${dlcDb.state}"
    )

    val dlcId = dlcDb.dlcId
    val financials = DLCAccountingDbs(dlcDb, offerDb, acceptDb, closingTx)
    val accounting: DLCAccounting =
      AccountingUtil.calculatePnl(financials)

    val totalCollateral = contractData.totalCollateral

    val localCollateral = if (dlcDb.isInitiator) {
      offerDb.collateral
    } else {
      totalCollateral - offerDb.collateral
    }
    val status = dlcDb.state.asInstanceOf[DLCState.ClosedState] match {
      case DLCState.Refunded =>
        //no oracle information in the refund case
        val refund = Refunded(
          dlcId,
          dlcDb.isInitiator,
          dlcDb.lastUpdated,
          dlcDb.tempContractId,
          dlcDb.contractIdOpt.get,
          contractInfo,
          contractData.dlcTimeouts,
          dlcDb.feeRate,
          totalCollateral,
          localCollateral,
          dlcDb.fundingTxIdOpt.get,
          closingTx.txIdBE,
          myPayout = accounting.myPayout,
          counterPartyPayout = accounting.theirPayout,
          payoutAddress = payoutAddress,
          peer = dlcDb.peerOpt
        )
        refund
      case oracleOutcomeState: DLCState.ClosedViaOracleOutcomeState =>
        val (oracleOutcome, sigs) = getOracleOutcomeAndSigs(
          announcementIds = announcementIds,
          announcementsWithId = announcementsWithId,
          nonceDbs = nonceDbs)

        oracleOutcomeState match {
          case DLCState.Claimed =>
            Claimed(
              dlcId,
              dlcDb.isInitiator,
              dlcDb.lastUpdated,
              dlcDb.tempContractId,
              dlcDb.contractIdOpt.get,
              contractInfo,
              contractData.dlcTimeouts,
              dlcDb.feeRate,
              totalCollateral,
              localCollateral,
              dlcDb.fundingTxIdOpt.get,
              closingTx.txIdBE,
              sigs,
              oracleOutcome,
              myPayout = accounting.myPayout,
              counterPartyPayout = accounting.theirPayout,
              payoutAddress = payoutAddress,
              peer = dlcDb.peerOpt
            )
          case DLCState.RemoteClaimed =>
            RemoteClaimed(
              dlcId,
              dlcDb.isInitiator,
              dlcDb.lastUpdated,
              dlcDb.tempContractId,
              dlcDb.contractIdOpt.get,
              contractInfo,
              contractData.dlcTimeouts,
              dlcDb.feeRate,
              totalCollateral,
              localCollateral,
              dlcDb.fundingTxIdOpt.get,
              closingTx.txIdBE,
              dlcDb.aggregateSignatureOpt.get,
              oracleOutcome,
              myPayout = accounting.myPayout,
              counterPartyPayout = accounting.theirPayout,
              payoutAddress = payoutAddress,
              peer = dlcDb.peerOpt
            )
        }
    }
    status
  }

  /** Calculates oracle outcome and signatures. Returns none if the dlc is not in a valid state to
    * calculate the outcome
    */
  def getOracleOutcomeAndSigs(
      announcementIds: Vector[DLCAnnouncementDb],
      announcementsWithId: Vector[(OracleAnnouncementV0TLV, Long)],
      nonceDbs: Vector[OracleNonceDb]): (
      OracleOutcome,
      Vector[SchnorrDigitalSignature]) = {
    val noncesByAnnouncement: Map[Long, Vector[OracleNonceDb]] =
      nonceDbs.sortBy(_.index).groupBy(_.announcementId)
    val oracleOutcome = {
      val usedOracleIds = announcementIds.filter(_.used)
      val usedOracles = usedOracleIds.sortBy(_.index).map { used =>
        announcementsWithId.find(_._2 == used.announcementId).get
      }
      require(usedOracles.nonEmpty,
              s"Error, no oracles used, dlcIds=${announcementIds.map(_.dlcId)}")
      announcementsWithId.head._1.eventTLV.eventDescriptor match {
        case _: EnumEventDescriptorV0TLV =>
          val oracleInfos = usedOracles.map(t => EnumSingleOracleInfo(t._1))
          val outcomes = usedOracles.map { case (_, id) =>
            val nonces = noncesByAnnouncement(id)
            require(nonces.size == 1,
                    s"Only 1 outcome for enum, got ${nonces.size}")
            EnumOutcome(nonces.head.outcomeOpt.get)
          }
          require(outcomes.distinct.size == 1,
                  s"Should only be one outcome for enum, got $outcomes")
          EnumOracleOutcome(oracleInfos, outcomes.head)
        case _: UnsignedDigitDecompositionEventDescriptor =>
          val oraclesAndOutcomes = usedOracles.map { case (announcement, id) =>
            val oracleInfo = NumericSingleOracleInfo(announcement)
            val nonces = noncesByAnnouncement(id).sortBy(_.index)
            // need to allow for some Nones because we don't always get
            // all the digits because of prefixing
            val digits = nonces.flatMap(_.outcomeOpt.map(_.toInt))
            require(digits.nonEmpty, s"Got no digits for announcement id $id")
            val outcome = UnsignedNumericOutcome(digits)
            (oracleInfo, outcome)
          }
          NumericOracleOutcome(oraclesAndOutcomes)
        case _: SignedDigitDecompositionEventDescriptor =>
          throw new RuntimeException(s"SignedNumericOutcome not yet supported")
      }
    }

    val sigs = nonceDbs.flatMap(_.signatureOpt)
    (oracleOutcome, sigs)
  }
}
