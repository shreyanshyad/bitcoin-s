package org.bitcoins.server.util

import akka.actor.ActorSystem
import grizzled.slf4j.Logging

trait BitcoinSApp {
  def actorSystemName: String

  implicit lazy val system: ActorSystem = ActorSystem(actorSystemName)

  def commandLineArgs: Array[String]

  /** Useful for projects like the oracle server to specify a custom directory inside of ~./bitcoin-s */
  def customFinalDirOpt: Option[String]
}

/** Trait for using BitcoinS app with a daemon backend */
trait BitcoinSAppScalaDaemon extends App with BitcoinSApp with Logging {
  final override def commandLineArgs: Array[String] = args
}
