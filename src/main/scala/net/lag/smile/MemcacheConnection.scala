/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.smile

import java.io.IOException
import java.net.InetSocketAddress
import scala.actors.{Actor, OutputChannel, TIMEOUT}
import scala.actors.Actor._
import scala.collection.mutable
import net.lag.extensions._
import net.lag.logging.Logger
import net.lag.naggati.{IoHandlerActorAdapter, MinaMessage}
import org.apache.mina.core.session.IoSession
import org.apache.mina.transport.socket.nio.NioSocketConnector


/**
 * Connection to and configuration for a memcache server, and an actor for handling requests.
 */
class MemcacheConnection(val hostname: String, val port: Int, val weight: Int) {
  var pool: ServerPool = null

  private val log = Logger.get

  @volatile protected[smile] var session: Option[IoSession] = None

  // if the last connection attempt failed, this contains the time we should try next:
  @volatile protected[smile] var delaying: Option[Long] = None


  override def toString() = {
    val status = session match {
      case None =>
        delaying match {
          case None => "not connected"
          case Some(d) =>
            if (d > Time.now) {
              "waiting %d sec to retry".format((Time.now - d + 999) / 1000)
            } else {
              "ready to retry"
            }
        }
      case Some(s) => "connected"
    }
    "<MemcacheConnection %s:%d weight=%d (%s)>".format(hostname, port, weight, status)
  }

  /**
   * Do we have an open TCP connection to this memcache server (or think we do)?
   */
  def connected = session != None

  @throws(classOf[MemcacheServerException])
  def get(keys: Array[String]): Map[String, MemcacheResponse.Value] = {
    serverActor !? Get("get", keys.mkString(" ")) match {
      case Timeout => throw new MemcacheServerTimeout
      case ConnectionFailed => throw new MemcacheServerOffline
      case Error(description) => throw new MemcacheServerException(description)
      case GetResponse(values) => Map.empty ++ (for (v <- values) yield (v.key, v))
    }
  }

  @throws(classOf[MemcacheServerException])
  def get(key: String): Option[MemcacheResponse.Value] = {
    serverActor !? Get("get", key) match {
      case Timeout => throw new MemcacheServerTimeout
      case ConnectionFailed => throw new MemcacheServerOffline
      case Error(description) => throw new MemcacheServerException(description)
      case GetResponse(values) =>
        values match {
          case Nil => None
          case v :: Nil => Some(v)
          // sanity check:
          case _ => throw new MemcacheServerException("too many results for single get: " +
            values.length)
        }
    }
  }

  @throws(classOf[MemcacheServerException])
  def store(query: String, key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean = {
    serverActor !? Store(query, key, flags, expiry, value) match {
      case Timeout => throw new MemcacheServerTimeout
      case ConnectionFailed => throw new MemcacheServerOffline
      case Error(description) => throw new MemcacheServerException(description)
      case MemcacheResponse.Stored => true
      case MemcacheResponse.NotStored => false
    }
  }

  @throws(classOf[MemcacheServerException])
  def set(key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean =
    store("set", key, value, flags, expiry)

  @throws(classOf[MemcacheServerException])
  def add(key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean =
    store("add", key, value, flags, expiry)

  @throws(classOf[MemcacheServerException])
  def replace(key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean =
    store("replace", key, value, flags, expiry)

  @throws(classOf[MemcacheServerException])
  def append(key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean =
    store("append", key, value, flags, expiry)

  @throws(classOf[MemcacheServerException])
  def prepend(key: String, value: Array[Byte], flags: Int, expiry: Int): Boolean =
    store("prepend", key, value, flags, expiry)

  /**
   * Stop the actor associated with this connection, and disconnect from the server if
   * connected. The MemcacheConnection object will be useless after this call.
   */
  def shutdown() = {
    serverActor ! Stop
  }


  //  ----------  implementation

  private def connect(): Unit = {
    if (delaying.isDefined && (Time.now < delaying.get)) {
      // not yet.
      return
    }
    delaying = None

    val future = pool.connector.connect(new InetSocketAddress(hostname, port))
    future.await
    if (!future.isConnected) {
      val exception = future.getException
      if (exception != null) {
        log.warning("Failed to connect to memcache server %s:%d: %s", hostname, port, exception)
      } else {
        log.warning("Failed to connect to memcache server %s:%d: no exception", hostname, port)
      }
      delaying = Some(Time.now + pool.retryDelay)
      session = None
    } else {
      session = Some(future.getSession)
      IoHandlerActorAdapter.setActorFor(session.get, serverActor)
      IoHandlerActorAdapter.filter(session.get) -= MinaMessage.SessionOpened
      IoHandlerActorAdapter.filter(session.get) -= classOf[MinaMessage.MessageSent]
    }
  }

  private[smile] def ensureConnected(): Boolean = {
    session match {
      case None =>
        connect
        session.isDefined
      case Some(s) => true
    }
  }

  private def disconnect(): Unit = {
    for (s <- session) {
      s.close
    }
    session = None
  }


  //  ----------  actor

  private case object Stop
  private case class Get(query: String, key: String)
  private case class Store(query: String, key: String, flags: Int, expiry: Int, data: Array[Byte])

  private case object ConnectionFailed
  private case class Error(description: String)
  private case object Timeout
  private case class GetResponse(values: List[MemcacheResponse.Value])

  // values collected from a get/gets
  private val values = new mutable.ListBuffer[MemcacheResponse.Value]


  val serverActor = actor {
    loop {
      values.clear

      react {
        case Stop =>
          disconnect
          self.exit

        case Get(query, key) =>
          if (!ensureConnected()) {
            reply(ConnectionFailed)
          } else {
            for (s <- session) {
              s.write(query + " " + key + "\r\n")
              waitForGetResponse(sender)
            }
          }

        case Store(query, key, flags, expiry, data) =>
          if (!ensureConnected()) {
            reply(ConnectionFailed)
          } else {
            for (s <- session) {
              s.write(query + " " + key + " " + flags + " " + expiry + " " + data.length + "\r\n")
              s.write(data)
              s.write("\r\n")
              waitForStoreResponse(sender)
            }
          }

        // non-interesting (unsolicited) mina messages:
        case MinaMessage.MessageReceived(message) =>
          log.error("unsolicited response from server %s: %s", this, message)
        case MinaMessage.ExceptionCaught(cause) =>
          log.error(cause, "unsolicited exception in actor for %s", this)
          disconnect
        case MinaMessage.SessionClosed =>
          log.error("disconnected from server for %s", this)
          disconnect
      }
    }
  }

  /**
   * Handle mina messages on this connection while waiting for a response from the memcache
   * server. Timeouts and disconnects are handled too. Any response is passed into the
   * handler block.
   */
  private def waitForResponse(sender: OutputChannel[Any])(handler: AnyRef => Unit) = {
    reactWithin(pool.readTimeout) {
      case MinaMessage.MessageReceived(message) =>
        handler(message)
      case MinaMessage.ExceptionCaught(cause) =>
        disconnect
        cause match {
          case e: IOException =>
            log.error(cause, "exception in actor for %s ioexception", this)
            sender ! ConnectionFailed
          case e =>
            log.error(cause, "exception in actor for %s", this)
            sender ! Error(cause.toString)
        }
      case MinaMessage.SessionClosed =>
        log.error("disconnected from server for %s", this)
        disconnect
        sender ! ConnectionFailed
      case TIMEOUT =>
        log.error("timeout for %s", this)
        if (session != None) {
          disconnect
          sender ! Timeout
        }
    }
  }

  private def waitForGetResponse(sender: OutputChannel[Any]): Unit = {
    waitForResponse(sender) { message =>
      message match {
        case v: MemcacheResponse.Value =>
          values += v
          waitForGetResponse(sender)
        case MemcacheResponse.EndOfResults =>
          sender ! GetResponse(values.toList)
        case MemcacheResponse.Error => sender ! Error("error")
        case MemcacheResponse.ClientError(x) => sender ! Error("client error: " + x)
        case MemcacheResponse.ServerError(x) => sender ! Error("server error: " + x)
        case x => sender ! Error("unexpected: " + x)
      }
    }
  }

  private def waitForStoreResponse(sender: OutputChannel[Any]): Unit = {
    waitForResponse(sender) { message =>
      message match {
        case MemcacheResponse.Error => sender ! Error("error")
        case MemcacheResponse.ClientError(x) => sender ! Error("client error: " + x)
        case MemcacheResponse.ServerError(x) => sender ! Error("server error: " + x)
        case item => sender ! item
      }
    }
  }
}
