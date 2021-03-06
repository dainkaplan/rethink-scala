package com.rethinkscala.net


import java.net.InetSocketAddress
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import com.rethinkscala.ConvertFrom._
import com.rethinkscala.Term
import com.rethinkscala.ast._
import com.rethinkscala.net.Translate._
import com.rethinkscala.reflect.Reflector
import com.rethinkscala.utils.{ConnectionFactory, SimpleConnectionPool}
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.buffer.ChannelBuffers._
import org.jboss.netty.buffer.{ChannelBuffer, HeapChannelBufferFactory}
import org.jboss.netty.channel.Channels.pipeline
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import ql2.{Ql2 => ql2}
import ql2.Response.ResponseType
import ql2.{Response, VersionDummy}

import scala.concurrent.{Promise,Future}
import scala.concurrent.duration.Duration


/** Created by IntelliJ IDEA.
  * User: Keyston
  * Date: 3/23/13
  * Time: 2:53 PM
  */





class RethinkDBHandler extends SimpleChannelUpstreamHandler {

  implicit def channelHandlerContext2Promise(ctx: ChannelHandlerContext): Option[Token[Response]] = Some(ctx.getChannel.getAttachment.asInstanceOf[Token[Response]])

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    ctx.map(_.handle(e.getMessage.asInstanceOf[Response]))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    ctx.map(_.failure(e.getCause))
  }
}

private class RethinkDBFrameDecoder extends FrameDecoder {
  var acceptJson:Boolean = false
  def asJson(b: Boolean) = acceptJson = b

  private val PROTO_READ_AMOUNT = 4
  private val JSON_READ_AMOUNT = 8+ 4


  private def decodeJson(buffer:ChannelBuffer):AnyRef= {

    val token = buffer.readLong()
    val length = buffer.readInt()

    if(buffer.readableBytes() < length) {
      buffer.resetReaderIndex()
      return null
    }
    val json  = new String(buffer.readBytes(length).array(),"UTF-8")

   val resp =  Reflector.fromJson[JsonResponse](json)

    resp


  }
  private def decodeProto(buffer:ChannelBuffer):AnyRef= {

    val length = buffer.readInt()

    if (buffer.readableBytes() < length) {
      buffer.resetReaderIndex()
      return null
    }
    buffer.readBytes(length)
  }
  def decode(ctx: ChannelHandlerContext, channel:Channel, buffer: ChannelBuffer): AnyRef = {
    buffer.markReaderIndex()
    val readAmount = if(acceptJson) JSON_READ_AMOUNT else PROTO_READ_AMOUNT

     if (!buffer.readable() || buffer.readableBytes() < readAmount) {
        buffer.resetReaderIndex()
        return null
      }
      if(acceptJson) decodeJson(buffer) else decodeProto(buffer)
    }

}

private class RethinkDBEncoder extends OneToOneEncoder {

  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {

    msg match {
      case v: VersionDummy.Version => {
        val b = buffer(ByteOrder.LITTLE_ENDIAN, 4)
        b.writeInt(v.getNumber)
        b
      }
      case p:ql2.VersionDummy.Protocol=>{
        val b = buffer(ByteOrder.LITTLE_ENDIAN,4)
        b.writeInt(p.getNumber)
        b
      }
      case q: CompiledQuery => q.encode
      case s: String =>
        val b = buffer(ByteOrder.LITTLE_ENDIAN, s.length + 4)
        b.writeInt(s.length)
        b.writeBytes(s.getBytes("ascii"))
        b
      case q: ql2.Query =>
        val size = q.getSerializedSize
        val b = buffer(ByteOrder.LITTLE_ENDIAN, size + 4)
        b.writeInt(size)

        b.writeBytes(q.toByteArray)
        b
    }

  }
}

private class PipelineFactory extends ChannelPipelineFactory {
  // stateless
  val defaultHandler = new RethinkDBHandler()


  def getPipeline: ChannelPipeline = {
    val p = pipeline()


    p.addLast("frameDecoder", new RethinkDBFrameDecoder())
    p.addLast("protobufDecoder", ProtobufDecoder(Response.getDefaultInstance))
    p.addLast("protobufEncoder", new RethinkDBEncoder())
    p.addLast("handler", defaultHandler)
    p
  }
}


abstract class AbstractConnection(version: Version) extends LazyLogging with Connection {

  private val defaultDB = Some(version.db.getOrElse("test"))
  private[this] val connectionId = new AtomicInteger()

  implicit val exc = version.executionContext


  lazy val bootstrap = {

    val factory =
      new NioClientSocketChannelFactory(
        Executors.newCachedThreadPool(),
        Executors.newCachedThreadPool())

    val b = new ClientBootstrap(factory)
    b.setPipelineFactory(new PipelineFactory())
    b.setOption("tcpNoDelay", true)
    b.setOption("keepAlive", true)
    b.setOption("bufferFactory", new HeapChannelBufferFactory(ByteOrder.LITTLE_ENDIAN))
    b

  }


  case class ChannelWrapper(cf: ChannelFuture) {
    val id = connectionId.incrementAndGet()
    val token: AtomicLong = new AtomicLong()
    @volatile
    var configured: Boolean = false
    lazy val channel = cf.getChannel
  }


  protected[rethinkscala] val channel = new SimpleConnectionPool(new ConnectionFactory[ChannelWrapper] {

    def create(): ChannelWrapper = {

      logger.debug("Creating new ChannelWrapper")
      val c = bootstrap.connect(new InetSocketAddress(version.host, version.port)).await()

      new ChannelWrapper(c)
    }

    def configure(wrapper: ChannelWrapper) = {
      if (!wrapper.configured) {
        logger.debug("Configuring ChannelWrapper")
        version.configure(wrapper.channel)
        wrapper.configured = true
      } else {
        logger.debug("Logger already configured")
      }
    }

    def validate(wrapper: ChannelWrapper): Boolean = {
      wrapper.channel.isOpen
    }

    def destroy(wrapper: ChannelWrapper) {
      logger.debug("Destroying Channel")
      wrapper.channel.close()
    }
  }, max = version.maxConnections)


  def write[T](term: Term, opts: Map[String, Any])(implicit mf: Manifest[T]): Promise[T] = {
    val p = Promise[T]()
    val f = p.future
    logger.debug(s"Writing $term")
    channel take {
      case (c, restore) =>
        logger.debug("Received connection from pool")
        val con = this
        // add a channel future to ensure that all setup has been done
        c.cf.addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            val query = version.toQuery(term, c.token.getAndIncrement, defaultDB, opts)
            val token = QueryToken[T](con, query, term, p, mf)
            // TODO : Check into dropping netty and using sockets for each,
            val frameDecoder = c.channel.getPipeline.get("frameDecoder").asInstanceOf[RethinkDBFrameDecoder]
            if(query.isInstanceOf[JsonCompiledQuery]){
                frameDecoder.asJson(true)
            }
            // Or find a way so that we can store the token for the netty handler to complete
            c.channel.setAttachment(token)
            logger.debug("Writing query")
            c.channel.write(query)
            future.removeListener(this)
          }
        })
        f onComplete {
          case _ => restore(c)
        }
    } onFailure {
      case e: Exception => p.failure(e)
    }
    p
  }
}

trait Connection {
  val underlying: Connection
  val version: Version

  def toAst(term: Term): CompiledAst = version.toAst(term)

  def write[T](term: Term, opts: Map[String, Any])(implicit mf: Manifest[T]): Promise[T]

}


trait ConnectionOps[C <: Connection, D <: Mode[C]] {
  self: C =>


  val delegate: D


  def newQuery[R](term: Term, mf: Manifest[R], opts: Map[String, Any]): ResultQuery[R]

 // def apply[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).run

 // def toOpt[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).toOpt
}


trait BlockingConnection extends Connection with ConnectionOps[BlockingConnection, Blocking] {


  val delegate=Blocking

  // FIXME : Need to place here to help out Intellijd
  def apply[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).run

  def toOpt[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).toOpt

  val timeoutDuration: Duration
}

trait AsyncConnection extends Connection with ConnectionOps[AsyncConnection, Async] {

  val delegate=Async
  // FIXME : Need to place here to help out Intellij with async(_.apply(res))
  def apply[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).run

  def toOpt[T](produce: Produce[T])(implicit m: Manifest[T]) = delegate(produce)(this).toOpt
}

object AsyncConnection {

  def apply(version: Version) = build(version, None)


  def apply(connection: Connection) = connection match {
    case c: AsyncConnection => c
    case c: BlockingConnection => build(connection.version, Some(connection))
  }


  private def build(v: Version, under: Option[Connection]) = new AbstractConnection(v) with AsyncConnection {
    val underlying: Connection = under.getOrElse(this)
    val version = v

    def newQuery[R](term: Term, mf: Manifest[R], opts: Map[String, Any]) = AsyncResultQuery[R](term, this, mf, opts)

  }
}

private class ConnectionWithAsync(v: Version, under: Option[Connection]) extends AbstractConnection(v) with AsyncConnection {
  val underlying: Connection = under.getOrElse(this)
  val version = v

  def newQuery[R](term: Term, mf: Manifest[R], opts: Map[String, Any]) = AsyncResultQuery[R](term, this, mf, opts)
}

object BlockingConnection {
  val defaultTimeoutDuration = Duration(30, "seconds")


  def apply(connection: Connection) = connection match {
    case c: BlockingConnection => c
    case c: AsyncConnection => build(connection.version, defaultTimeoutDuration, Some(connection))
  }

  def apply(version: Version, timeoutDuration: Duration = defaultTimeoutDuration) = build(version, timeoutDuration, None)

  private def build(v: Version, t: Duration, under: Option[Connection]) = new AbstractConnection(v) with BlockingConnection {
    val timeoutDuration = t
    val underlying: Connection = under.getOrElse(this)
    val version = v

    def newQuery[R](term: Term, mf: Manifest[R], opts: Map[String, Any]) = BlockingResultQuery[R](term, this, mf, opts)
  }
}



