package org.bitcoins.node.networking.peer

import akka.Done
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.chain.models.BlockHeaderDAO
import org.bitcoins.core.api.chain.ChainApi
import org.bitcoins.core.api.node.NodeType
import org.bitcoins.core.gcs.BlockFilter
import org.bitcoins.core.p2p._
import org.bitcoins.crypto.DoubleSha256DigestBE
import org.bitcoins.node.config.NodeAppConfig
import org.bitcoins.node.models._
import org.bitcoins.node.networking.peer.DataMessageHandlerState._
import org.bitcoins.node.{NeutrinoNode, P2PLogger, PeerManager}

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

/** This actor is meant to handle a [[org.bitcoins.core.p2p.DataPayload DataPayload]]
  * that a peer to sent to us on the p2p network, for instance, if we a receive a
  * [[org.bitcoins.core.p2p.HeadersMessage HeadersMessage]] we should store those headers in our database
  *
  * @param currentFilterBatch holds the current batch of filters to be processed, after its size reaches
  *                           chainConfig.filterBatchSize they will be processed and then emptied
  */
case class DataMessageHandler(
    chainApi: ChainApi,
    walletCreationTimeOpt: Option[Instant],
    initialSyncDone: Option[Promise[Done]] = None,
    currentFilterBatch: Vector[CompactFilterMessage] = Vector.empty,
    filterHeaderHeightOpt: Option[Int] = None,
    filterHeightOpt: Option[Int] = None,
    var syncing: Boolean = false,
    var syncPeer: Option[Peer] = None,
    node: Option[NeutrinoNode] = None,
    private var state: DataMessageHandlerState = Initial)(implicit
    ec: ExecutionContext,
    appConfig: NodeAppConfig,
    chainConfig: ChainAppConfig)
    extends P2PLogger {

  require(appConfig.nodeType == NodeType.NeutrinoNode,
          "DataMessageHandler is meant to be used with NeutrinoNode")

  private val txDAO = BroadcastAbleTransactionDAO()

  def reset: DataMessageHandler = copy(initialSyncDone = None,
                                       currentFilterBatch = Vector.empty,
                                       filterHeaderHeightOpt = None,
                                       filterHeightOpt = None,
                                       syncPeer = None,
                                       syncing = false)

  def manager: PeerManager = node.get.peerManager

  def handleDataPayload(
      payload: DataPayload,
      peerMsgSender: PeerMessageSender,
      peer: Peer): Future[DataMessageHandler] = {

    lazy val resultF = payload match {
      case checkpoint: CompactFilterCheckPointMessage =>
        logger.debug(
          s"Got ${checkpoint.filterHeaders.size} checkpoints ${checkpoint} from $peer")
        for {
          newChainApi <- chainApi.processCheckpoints(
            checkpoint.filterHeaders.map(_.flip),
            checkpoint.stopHash.flip)
        } yield {
          this.copy(chainApi = newChainApi)
        }
      case filterHeader: CompactFilterHeadersMessage =>
        logger.debug(
          s"Got ${filterHeader.filterHashes.size} compact filter header hashes")
        val filterHeaders = filterHeader.filterHeaders
        for {
          newChainApi <- chainApi.processFilterHeaders(
            filterHeaders,
            filterHeader.stopHash.flip)
          (newSyncing, startFilterHeightOpt) <-
            if (filterHeaders.size == chainConfig.filterHeaderBatchSize) {
              logger.debug(
                s"Received maximum amount of filter headers in one header message. This means we are not synced, requesting more")
              sendNextGetCompactFilterHeadersCommand(
                peerMsgSender,
                filterHeader.stopHash.flip).map(_ => (syncing, None))
            } else {
              for {
                startHeightOpt <- getCompactFilterStartHeight(
                  walletCreationTimeOpt)
                _ = logger.info(
                  s"Done syncing filter headers, beginning to sync filters from startHeightOpt=$startHeightOpt")
                syncing <- sendFirstGetCompactFilterCommand(
                  peerMsgSender,
                  startHeightOpt).map { synced =>
                  if (!synced) logger.info("We are synced")
                  syncing
                }
              } yield (syncing, startHeightOpt)
            }
          newFilterHeaderHeight <- filterHeaderHeightOpt match {
            case None =>
              chainApi.getFilterHeaderCount()
            case Some(filterHeaderHeight) =>
              Future.successful(filterHeaderHeight + filterHeaders.size)
          }
        } yield {
          this.copy(chainApi = newChainApi,
                    syncing = newSyncing,
                    filterHeaderHeightOpt = Some(newFilterHeaderHeight),
                    filterHeightOpt = startFilterHeightOpt)
        }
      case filter: CompactFilterMessage =>
        logger.debug(s"Received ${filter.commandName}, $filter")
        val batchSizeFull: Boolean =
          currentFilterBatch.size == chainConfig.filterBatchSize - 1
        for {
          (newFilterHeaderHeight, newFilterHeight) <-
            calcFilterHeaderFilterHeight()
          newSyncing =
            if (batchSizeFull) {
              syncing
            } else {
              val syncing = newFilterHeight < newFilterHeaderHeight
              if (!syncing) {
                logger.info(s"We are synced")
                Try(initialSyncDone.map(_.success(Done)))
              }
              syncing
            }
          // If we are not syncing or our filter batch is full, process the filters
          filterBatch = currentFilterBatch :+ filter
          (newBatch, newChainApi) <-
            if (!newSyncing || batchSizeFull) {
              val blockFilters = filterBatch.map { filter =>
                (filter.blockHash,
                 BlockFilter.fromBytes(filter.filterBytes, filter.blockHash))
              }
              logger.info(s"Processing ${filterBatch.size} filters")
              for {
                newChainApi <- chainApi.processFilters(filterBatch)
                _ <-
                  appConfig.nodeCallbacks
                    .executeOnCompactFiltersReceivedCallbacks(logger,
                                                              blockFilters)
              } yield (Vector.empty, newChainApi)
            } else Future.successful((filterBatch, chainApi))
          _ <-
            if (batchSizeFull) {
              logger.info(
                s"Received maximum amount of filters in one batch. This means we are not synced, requesting more")
              sendNextGetCompactFilterCommand(peerMsgSender, newFilterHeight)
            } else Future.unit
        } yield {
          this.copy(
            chainApi = newChainApi,
            currentFilterBatch = newBatch,
            syncing = newSyncing,
            filterHeaderHeightOpt = Some(newFilterHeaderHeight),
            filterHeightOpt = Some(newFilterHeight)
          )
        }
      case notHandling @ (MemPoolMessage | _: GetHeadersMessage |
          _: GetBlocksMessage | _: GetCompactFiltersMessage |
          _: GetCompactFilterHeadersMessage |
          _: GetCompactFilterCheckPointMessage) =>
        logger.debug(s"Received ${notHandling.commandName} message, skipping ")
        Future.successful(this)
      case getData: GetDataMessage =>
        logger.info(
          s"Received a getdata message for inventories=${getData.inventories}")
        getData.inventories.foreach { inv =>
          logger.debug(s"Looking for inv=$inv")
          inv.typeIdentifier match {
            case msgTx @ (TypeIdentifier.MsgTx | TypeIdentifier.MsgWitnessTx) =>
              txDAO.findByHash(inv.hash).flatMap {
                case Some(BroadcastAbleTransaction(tx)) =>
                  val txToBroadcast =
                    if (msgTx == TypeIdentifier.MsgTx) {
                      // send non-witness serialization
                      tx.toBaseTx
                    } else tx // send normal serialization

                  peerMsgSender.sendTransactionMessage(txToBroadcast)
                case None =>
                  logger.warn(
                    s"Got request to send data with hash=${inv.hash}, but found nothing")
                  Future.unit
              }
            case other @ (TypeIdentifier.MsgBlock |
                TypeIdentifier.MsgFilteredBlock |
                TypeIdentifier.MsgCompactBlock |
                TypeIdentifier.MsgFilteredWitnessBlock |
                TypeIdentifier.MsgWitnessBlock) =>
              logger.warn(
                s"Got request to send data type=$other, this is not implemented yet")

            case unassigned: MsgUnassigned =>
              logger.warn(
                s"Received unassigned message we do not understand, msg=${unassigned}")
          }

        }
        Future.successful(this)
      case HeadersMessage(count, headers) =>
        logger.info(
          s"Received headers message with ${count.toInt} headers from $peer")
        logger.trace(
          s"Received headers=${headers.map(_.hashBE.hex).mkString("[", ",", "]")}")
        val chainApiF = chainApi.processHeaders(headers)

        chainApiF.failed.map {
          case r: RuntimeException
              if r.getMessage.startsWith(
                "Failed to connect any headers to our internal chain state") =>
            logger.info(
              s"Invalid headers $headers of count $count sent from ${syncPeer.get}")

            state match {
              case HeaderSync =>
                manager.peerData(peer).updateInvalidMessageCount()

                if (manager.peerData(peer).exceededMaxInvalidMessages) {
                  manager.removePeer(peer)
                  manager.syncFromNewPeer()
                } else {
                  //re-query
                  for {
                    blockchains <- BlockHeaderDAO().getBlockchains()
                    cachedHeaders = blockchains
                      .flatMap(_.headers)
                      .map(_.hashBE.flip)
                    _ <- peerMsgSender.sendGetHeadersMessage(cachedHeaders)
                  } yield ()
                }

              case headerState @ ValidatingHeaders(_, failedCheck, _) =>
                //if a peer sends invalid data then disconnect it
                manager.removePeer(peer)
                failedCheck.add(peer)

                if (headerState.validated) {
                  state = PostHeaderSync
                  fetchCompactFilters().map { newSyncing =>
                    node.get.updateDataMessageHandler(
                      copy(syncing = newSyncing))
                    ()
                  }
                } else Future.unit

                Future.unit
              case _: DataMessageHandlerState =>
                Future.unit
            }
          case throwable: Throwable => throw throwable
        }

        val getHeadersF = chainApiF
          .flatMap { newApi =>
            if (headers.nonEmpty) {

              val lastHeader = headers.last
              val lastHash = lastHeader.hash
              newApi.getBlockCount().map { count =>
                logger.trace(
                  s"Processed headers, most recent has height=$count and hash=$lastHash.")
              }

              if (count.toInt == HeadersMessage.MaxHeadersCount) {

                state match {
                  case HeaderSync =>
                    logger.info(
                      s"Received maximum amount of headers in one header message. This means we are not synced, requesting more")
                    //ask for headers more from the same peer
                    peerMsgSender
                      .sendGetHeadersMessage(lastHash)
                      .map(_ => syncing)

                  case ValidatingHeaders(inSyncWith, _, _) =>
                    //In the validation stage, some peer sent max amount of valid headers, revert to HeaderSync with that peer as syncPeer
                    //disconnect the ones that we have already checked since they are at least out of sync by 2000 headers
                    inSyncWith.foreach(p => manager.removePeer(p))

                    syncPeer = Some(peer)
                    state = HeaderSync

                    //ask for more headers now
                    peerMsgSender
                      .sendGetHeadersMessage(lastHash)
                      .map(_ => syncing)

                  case _: DataMessageHandlerState =>
                    Future.successful(syncing)
                }

              } else {
                logger.debug(
                  List(s"Received headers=${count.toInt} in one message,",
                       "which is less than max. This means we are synced,",
                       "not requesting more.")
                    .mkString(" "))
                // If we are in neutrino mode, we might need to start fetching filters and their headers
                // if we are syncing we should do this, however, sometimes syncing isn't a good enough check,
                // so we also check if our cached filter heights have been set as well, if they haven't then
                // we probably need to sync filters
                state match {
                  case HeaderSync =>
                    // headers are synced now with the current sync peer, now move to validating it for all peers
                    assert(syncPeer.get == peer)

                    if (manager.peers.size > 1) {
                      state =
                        ValidatingHeaders(inSyncWith = mutable.Set(peer),
                                          verifyingWith = manager.peers.toSet,
                                          failedCheck = mutable.Set.empty[Peer])

                      val getHeadersAllF = manager.peerData
                        .filter(_._1 != peer)
                        .map(
                          _._2.peerMessageSender
                            .sendGetHeadersMessage(lastHash)
                        )

                      Future.sequence(getHeadersAllF).map(_ => syncing)
                    } else {
                      //if just one peer then can proceed ahead directly
                      state = PostHeaderSync
                      fetchCompactFilters()
                    }

                  case headerState @ ValidatingHeaders(inSyncWith, _, _) =>
                    //add the current peer to it
                    inSyncWith.add(peer)
                    logger.info(s"In sync with $inSyncWith")

                    if (headerState.validated) {
                      // If we are in neutrino mode, we might need to start fetching filters and their headers
                      // if we are syncing we should do this, however, sometimes syncing isn't a good enough check,
                      // so we also check if our cached filter heights have been set as well, if they haven't then
                      // we probably need to sync filters

                      state = PostHeaderSync
                      fetchCompactFilters()
                    } else {
                      //do nothing, we are still waiting for some peers to send headers or timeout
                      Future.successful(syncing)
                    }

                  case PostHeaderSync =>
                    //send further requests to the same one that sent this
                    if (
                      !syncing ||
                      (filterHeaderHeightOpt.isEmpty &&
                        filterHeightOpt.isEmpty)
                    ) {
                      logger.info(
                        s"Starting to fetch filter headers in data message handler")
                      sendFirstGetCompactFilterHeadersCommand(peerMsgSender)
                    } else {
                      Try(initialSyncDone.map(_.success(Done)))
                      Future.successful(syncing)
                    }

                  case _: DataMessageHandlerState =>
                    Future.successful(syncing)
                }
              }
            } else {
              //what if we are synced exactly by the 2000th header
              state match {
                case headerState @ ValidatingHeaders(inSyncWith, _, _) =>
                  inSyncWith.add(peer)
                  logger.info(s"In sync with $inSyncWith")

                  if (headerState.validated) {
                    state = PostHeaderSync
                    fetchCompactFilters()
                  } else {
                    //do nothing, we are still waiting for some peers to send headers
                    Future.successful(syncing)
                  }
                case _: DataMessageHandlerState =>
                  Future.successful(syncing)
              }
            }
          }

        getHeadersF.failed.map { err =>
          logger.error(s"Error when processing headers message", err)
        }

        for {
          newApi <- chainApiF
          newSyncing <- getHeadersF
          _ <- appConfig.nodeCallbacks.executeOnBlockHeadersReceivedCallbacks(
            logger,
            headers)
        } yield {
          this.copy(chainApi = newApi, syncing = newSyncing)
        }
      case msg: BlockMessage =>
        val block = msg.block
        logger.info(
          s"Received block message with hash ${block.blockHeader.hash.flip.hex}")

        val newApiF = {
          chainApi
            .getHeader(block.blockHeader.hashBE)
            .flatMap { headerOpt =>
              if (headerOpt.isEmpty) {
                logger.debug("Processing block's header...")
                for {
                  processedApi <- chainApi.processHeader(block.blockHeader)
                  _ <-
                    appConfig.nodeCallbacks
                      .executeOnBlockHeadersReceivedCallbacks(
                        logger,
                        Vector(block.blockHeader))
                } yield processedApi
              } else Future.successful(chainApi)
            }
        }

        for {
          newApi <- newApiF
          _ <-
            appConfig.nodeCallbacks
              .executeOnBlockReceivedCallbacks(logger, block)
        } yield {
          this.copy(chainApi = newApi)
        }
      case TransactionMessage(tx) =>
        MerkleBuffers.putTx(tx, appConfig.nodeCallbacks).flatMap {
          belongsToMerkle =>
            if (belongsToMerkle) {
              logger.trace(
                s"Transaction=${tx.txIdBE} belongs to merkleblock, not calling callbacks")
              Future.successful(this)
            } else {
              logger.trace(
                s"Transaction=${tx.txIdBE} does not belong to merkleblock, processing given callbacks")
              appConfig.nodeCallbacks
                .executeOnTxReceivedCallbacks(logger, tx)
                .map(_ => this)
            }
        }
      case MerkleBlockMessage(merkleBlock) =>
        MerkleBuffers.putMerkle(merkleBlock)
        Future.successful(this)
      case invMsg: InventoryMessage =>
        handleInventoryMsg(invMsg = invMsg, peerMsgSender = peerMsgSender)
    }

    if (state.isInstanceOf[ValidatingHeaders]) {
      resultF.failed.foreach { err =>
        logger.error(s"Failed to handle data payload=${payload} from $peer",
                     err)
      }
      resultF.recoverWith { case NonFatal(_) =>
        Future.successful(this)
      }
    } else if (syncPeer.isEmpty || peer != syncPeer.get) {
      logger.debug(s"Ignoring ${payload.commandName} from $peer")
      Future.successful(this)
    } else {
      resultF.failed.foreach { err =>
        logger.error(s"Failed to handle data payload=${payload} from $peer",
                     err)
      }
      resultF.recoverWith { case NonFatal(_) =>
        Future.successful(this)
      }

    }
  }

  private def fetchCompactFilters(): Future[Boolean] = {
    if (
      !syncing ||
      (filterHeaderHeightOpt.isEmpty &&
        filterHeightOpt.isEmpty)
    ) {
      logger.info(s"Starting to fetch filter headers in data message handler")

      for {
        peer <- manager.randomPeerWithService(
          ServiceIdentifier.NODE_COMPACT_FILTERS)
        _ = syncPeer = Some(peer)
        sender = manager.peerData(peer).peerMessageSender
        res <- sendFirstGetCompactFilterHeadersCommand(sender)
      } yield res

    } else {
      Try(initialSyncDone.map(_.success(Done)))
      Future.successful(syncing)
    }
  }

  def onHeaderRequestTimeout(peer: Peer): Future[Unit] = {
    state match {
      case HeaderSync =>
        manager.syncFromNewPeer()

      case headerState @ ValidatingHeaders(_, failedCheck, _) =>
        failedCheck.add(peer)

        if (headerState.validated) {
          state = PostHeaderSync
          fetchCompactFilters().map { newSyncing =>
            node.get.updateDataMessageHandler(copy(syncing = newSyncing))
            ()
          }
        } else Future.unit

      case _: DataMessageHandlerState => Future.unit
    }
  }

  private def sendNextGetCompactFilterHeadersCommand(
      peerMsgSender: PeerMessageSender,
      prevStopHash: DoubleSha256DigestBE): Future[Boolean] =
    peerMsgSender.sendNextGetCompactFilterHeadersCommand(
      chainApi = chainApi,
      filterHeaderBatchSize = chainConfig.filterHeaderBatchSize,
      prevStopHash = prevStopHash)

  private def sendFirstGetCompactFilterHeadersCommand(
      peerMsgSender: PeerMessageSender): Future[Boolean] = {

    for {
      bestFilterHeaderOpt <-
        chainApi
          .getBestFilterHeader()
      blockHash = bestFilterHeaderOpt match {
        case Some(filterHeaderDb) =>
          filterHeaderDb.blockHashBE
        case None =>
          DoubleSha256DigestBE.empty
      }
      hashHeightOpt <- chainApi.nextBlockHeaderBatchRange(
        prevStopHash = blockHash,
        batchSize = chainConfig.filterHeaderBatchSize)
      res <- hashHeightOpt match {
        case Some(filterSyncMarker) =>
          peerMsgSender
            .sendGetCompactFilterHeadersMessage(filterSyncMarker)
            .map(_ => true)
        case None =>
          sys.error(
            s"Could not find block header in database to sync filter headers from! It's likely your database is corrupted")
      }
    } yield res
  }

  private def sendNextGetCompactFilterCommand(
      peerMsgSender: PeerMessageSender,
      startHeight: Int): Future[Boolean] =
    peerMsgSender.sendNextGetCompactFilterCommand(chainApi = chainApi,
                                                  filterBatchSize =
                                                    chainConfig.filterBatchSize,
                                                  startHeight = startHeight)

  private def sendFirstGetCompactFilterCommand(
      peerMsgSender: PeerMessageSender,
      startHeightOpt: Option[Int]): Future[Boolean] = {
    val startHeightF = startHeightOpt match {
      case Some(startHeight) => Future.successful(startHeight)
      case None              => chainApi.getFilterCount()
    }

    for {
      startHeight <- startHeightF
      res <- sendNextGetCompactFilterCommand(peerMsgSender, startHeight)
    } yield res
  }

  private def handleInventoryMsg(
      invMsg: InventoryMessage,
      peerMsgSender: PeerMessageSender): Future[DataMessageHandler] = {
    logger.debug(s"Received inv=${invMsg}")
    val getData = GetDataMessage(invMsg.inventories.flatMap {
      case Inventory(TypeIdentifier.MsgBlock, hash) =>
        appConfig.nodeType match {
          case NodeType.NeutrinoNode | NodeType.FullNode =>
            if (syncing) None
            else Some(Inventory(TypeIdentifier.MsgWitnessBlock, hash))
          case NodeType.BitcoindBackend =>
            throw new RuntimeException("This is impossible")
        }
      case Inventory(TypeIdentifier.MsgTx, hash) =>
        Some(Inventory(TypeIdentifier.MsgWitnessTx, hash))
      case other: Inventory => Some(other)
    })
    peerMsgSender.sendMsg(getData).map(_ => this)
  }

  private def getCompactFilterStartHeight(
      walletCreationTimeOpt: Option[Instant]): Future[Option[Int]] = {
    walletCreationTimeOpt match {
      case Some(instant) =>
        val creationTimeHeightF = chainApi
          .epochSecondToBlockHeight(instant.toEpochMilli / 1000)
        val filterCountF = chainApi.getFilterCount()
        for {
          creationTimeHeight <- creationTimeHeightF
          filterCount <- filterCountF
        } yield {
          //want to choose the maximum out of these too
          //if our internal chainstate filter count is > creationTimeHeight
          //we just want to start syncing from our last seen filter
          Some(Math.max(creationTimeHeight, filterCount))
        }
      case None =>
        Future.successful(None)
    }
  }

  private def calcFilterHeaderFilterHeight(): Future[(Int, Int)] = {
    (filterHeaderHeightOpt, filterHeightOpt) match {
      case (Some(filterHeaderHeight), Some(filterHeight)) =>
        Future.successful((filterHeaderHeight, filterHeight + 1))
      case (_, _) => // If either are None
        for {
          filterHeaderHeight <- chainApi.getFilterHeaderCount()
          filterHeight <- chainApi.getFilterCount()
        } yield (filterHeaderHeight,
                 if (filterHeight == 0) 0 else filterHeight + 1)
    }
  }
}
