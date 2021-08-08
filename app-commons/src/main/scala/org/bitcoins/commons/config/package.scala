package org.bitcoins.commons

import com.typesafe.config.{Config, ConfigRenderOptions}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}

package object config {

  implicit class ConfigOps(private val config: Config) extends AnyVal {

    def asReadableJson: String = {
      val options = ConfigRenderOptions.concise().setFormatted(true)
      config.root().render(options)
    }

    /** Returns the string at key or the given default value */
    def getStringOrElse(key: String, default: => String): String = {
      if (config.hasPath(key)) {
        config.getString(key)
      } else {
        default
      }
    }

    /** Returns the string at the given key, if it exists */
    def getStringOrNone(key: String): Option[String] = {
      if (config.hasPath(key)) {
        Some(config.getString(key))
      } else {
        None
      }
    }

    /** Returns the boolean at key or the given default value */
    def getBooleanOrElse(key: String, default: => Boolean): Boolean = {
      if (config.hasPath(key)) {
        config.getBoolean(key)
      } else {
        default
      }
    }

    /** Returns the int at key or the given default value */
    def getIntOrElse(key: String, default: => Int): Int = {
      getIntOpt(key).getOrElse(default)
    }

    /** Returns an option of the int at key */
    def getIntOpt(key: String): Option[Int] = {
      if (config.hasPath(key)) {
        Some(config.getInt(key))
      } else {
        None
      }
    }

    def getBooleanOpt(key: String): Option[Boolean] = {
      if (config.hasPath(key)) Some(config.getBoolean(key))
      else None
    }

    def getDurationOpt(key: String): Option[Duration] = {
      if (config.hasPath(key)) {
        val javaDuration = config.getDuration(key)
        val scalaDuration =
          new FiniteDuration(javaDuration.toNanos, TimeUnit.NANOSECONDS)
        Some(scalaDuration)
      } else None
    }
  }
}
