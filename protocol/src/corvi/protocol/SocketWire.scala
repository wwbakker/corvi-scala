package corvi.protocol

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.Path
import kyo.*
import kyo.Frame

/** kyo-jsonrpc's byte seam over a Unix domain socket, which is the only thing a custom transport has to implement.
  *
  * Both directions block on the socket: the reads because that is how a socket with no messages behaves, and the write because a keystroke
  * is worth waiting for. They run in fibers, so a blocked read costs a parked virtual thread, the same posture as the host's PTY reader,
  * not a thread spinning.
  *
  * `closed` is released when the peer goes away (EOF, a reset, or a local close), which is how the connection's scope learns to end: the
  * handler's dispatch fiber would otherwise wait for a stream that has already finished.
  */
private[corvi] final class SocketWire(channel: SocketChannel, closed: Latch) extends JsonRpcWireTransport:

  /** A write to a peer that has gone is `Closed`, not a panic: the push loops `Abort.run[Closed]` around this, and a throw would escape as
    * a panic and take a session's damage loop — and every other client's — down with it.
    */
  def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
    Abort
      .run[Throwable](Sync.defer {
        val buffer = ByteBuffer.wrap(bytes.toArray)
        while buffer.hasRemaining do
          val _ = channel.write(buffer)
        ()
      })
      .flatMap {
        case Result.Success(_)     => Sync.defer(())
        case Result.Failure(error) =>
          unsafeRelease()
          Abort.fail(new Closed(socketName, summon[Frame], s"the write failed: $error"))
        case Result.Panic(error) =>
          unsafeRelease()
          Abort.fail(new Closed(socketName, summon[Frame], s"the write panicked: $error"))
      }

  def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
    // One chunk per read: batching would hold a message back until the batch filled, and a terminal's replies are small and irregular.
    Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]((), chunkSize = 1) { _ =>
      Sync.defer(readChunk(channel)).map {
        case Maybe.Present(chunk) => Maybe.Present((chunk, ()))
        case Maybe.Absent         => Maybe.Absent
      }
    }

  def close(using Frame): Unit < Async =
    Sync.defer {
      val _ = channel.close()
      unsafeRelease()
    }

  private def socketName: String = s"corvi socket ${channel.getRemoteAddress}"

  /** Releases the connection's end signal from a blocking call, where no effect can be run: the latch is a `java.util.concurrent` countdown
    * under the hood, and this is the one place that reaches it without an effect.
    */
  private def unsafeRelease(): Unit =
    import AllowUnsafe.embrace.danger
    closed.unsafe.release()

  /** One read, blocking: a chunk of bytes, or `Absent` once the peer has gone. */
  private def readChunk(channel: SocketChannel): Maybe[Chunk[Byte]] =
    val buffer = ByteBuffer.allocate(SocketWire.readBuffer)
    try
      val read = channel.read(buffer)
      if read < 0 then
        unsafeRelease()
        Maybe.Absent
      else if read == 0 then Maybe.Present(Chunk.empty)
      else
        buffer.flip()
        val bytes = new Array[Byte](read)
        val _ = buffer.get(bytes)
        Maybe.Present(Chunk.from(bytes.toSeq))
    catch
      case _: IOException =>
        unsafeRelease()
        Maybe.Absent

private[corvi] object SocketWire:
  /** Large enough for a screen delta, small enough that a decode is cheap. */
  private val readBuffer: Int = 64 * 1024

  /** Whether something answers on a socket, without blocking: a non-blocking connect that gives up after `timeout`.
    *
    * A wedged listener with a full backlog would otherwise block a daemon's start or a UI's `ensure` for as long as the kernel felt like,
    * which is exactly the moment a person is staring at a window that has not opened yet.
    */
  def answers(socket: Path, timeout: scala.concurrent.duration.Duration)(using Frame): Boolean < Sync =
    Sync.defer(answersUnsafe(socket, timeout))

  /** The same check from a caller that is not inside an effect: a spawn helper deciding whether to start a daemon is exactly that. */
  def answersUnsafe(socket: Path, timeout: scala.concurrent.duration.Duration): Boolean = {
    if !java.nio.file.Files.exists(socket) then false
    else
      var probe: SocketChannel = null
      try
        // The channel's family has to be UNIX: a default `SocketChannel.open()` is an INET channel, and connecting that to a
        // `UnixDomainSocketAddress` throws `UnsupportedAddressTypeException`, which reads exactly like "nobody is listening".
        probe = SocketChannel.open(StandardProtocolFamily.UNIX)
        probe.configureBlocking(false)
        val _ = probe.connect(UnixDomainSocketAddress.of(socket))
        val deadline = java.lang.System.nanoTime() + timeout.toNanos
        while !probe.finishConnect() do
          if java.lang.System.nanoTime() > deadline then throw new IOException("connect timed out")
          Thread.sleep(5)
        true
      catch case _: Throwable => false
      finally
        if probe != null then
          try { val _ = probe.close() }
          catch case _: Throwable => ()
  }

  /** The client end: a blocking connection to an existing socket. */
  def connect(socket: Path, closed: Latch)(using Frame): SocketWire < (Async & Abort[Throwable]) =
    for channel <- Abort.catching[Throwable](
        SocketChannel.open(UnixDomainSocketAddress.of(socket))
      )
    yield new SocketWire(channel, closed)

  /** The server end: listening on a socket path, in blocking mode, ready for `accept`. */
  def listen(socket: Path)(using Frame): ServerSocketChannel < (Async & Abort[Throwable]) =
    for
      server <- Abort.catching[Throwable](ServerSocketChannel.open(StandardProtocolFamily.UNIX))
      _ <- Abort.catching[Throwable](server.bind(UnixDomainSocketAddress.of(socket)))
    yield server
