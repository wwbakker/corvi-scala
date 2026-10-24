package corvi.host

import corvi.protocol.*
import java.io.IOException
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.Files
import kyo.*
import kyo.Frame
import scala.concurrent.duration.*

/** The daemon: one process that holds the shells, listening on a Unix domain socket.
  *
  * The UI starts it, and the UI may die and come back as often as it likes: the shells belong to this process, so a restart of the UI is a
  * reconnect rather than a restart of anything the user was running. Nothing here touches JavaFX, and the process is meant to outlive
  * whoever started it.
  *
  * Binding the socket *is* the single-instance lock, but only within this daemon's own family: an owner marker records the pid, a socket it
  * did not create is never unlinked, and the paths are namespaced away from the installed Corvi app's. Every connection says `host.hello`
  * with the token from the file next to the socket before it can touch a session; the socket and the token are 0600 in a 0700 directory.
  *
  * ./mill -i host.runMain corvi.host.HostMain
  */
object HostMain extends KyoApp:

  /** A push tick every 16 ms: 60 frames a second, which is what a screen can show. */
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(16.millis)

  run {
    for
      // The daemon's scope is run here so the process can exit *after* it has closed: `KyoApp` treats a successful body as "nothing to
      // report" and would otherwise leave the runtime's threads holding the JVM open.
      _ <- Scope.run(daemon)
      _ <- Sync.defer(java.lang.System.exit(0))
    yield ()
  }

  private def daemon(using Frame): Unit < (Async & Scope & Abort[Any]) =
    for
      paths <- Sync.defer(HostPaths.defaultPaths())
      _ <- Sync.defer(paths.prepare())
      token <- Sync.defer(paths.token())
      _ <- takeSocket(paths)
      server <- SocketWire.listen(paths.socket)
      _ <- Sync.defer(paths.claim(ProcessHandle.current().pid()))
      _ <- Sync.defer(paths.restrictSocket())
      _ <- Sync.defer(java.lang.System.err.println(s"corvi host: listening on ${paths.socket}"))
      // LIFO: the host's sessions are released before the socket is unlinked, and the socket before the process exits.
      _ <- Scope.ensure(Sync.defer {
        val _ = server.close()
        val _ = Files.deleteIfExists(paths.socket)
        val _ = Files.deleteIfExists(paths.ownerFile)
      })
      host <- Scope.acquireRelease(Sync.defer(HostSessions.init(pushInterval, HostSession.loginShell)))(_.shutdown)
      stop <- Latch.init(1)
      startedAt <- Sync.defer(java.lang.System.currentTimeMillis())
      _ <- Fiber.init(acceptLoop(server, host, token, stop, startedAt))
      _ <- stop.await
      // Closing the listener is what ends the accept loop's blocking accept; without it the daemon's scope would wait for a fiber waiting for
      // a connection that will never come. Connections are not waited for at all (their fibers are unscoped): the process exits, which closes
      // their sockets, rather than the daemon trying to close a handler from inside that handler's own request.
      _ <- Sync.defer {
        val _ = server.close()
        java.lang.System.err.println("corvi host: stopping")
      }
    yield ()

  /** Accepts a connection, hands it to a fiber of its own, and goes straight back to listening. */
  private def acceptLoop(
      server: ServerSocketChannel,
      host: HostSessions,
      token: String,
      stop: Latch,
      startedAt: Long
  )(using Frame): Unit < (Async & Scope & Abort[Any]) =
    def loop: Unit < (Async & Scope & Abort[Any]) =
      for
        accepted <- Sync.defer(accept(server))
        _ <- accepted match
          case None => Sync.defer(())
          // Unscoped: a connection lives until its peer goes, not until the daemon does, and the daemon must not wait for peers that
          // are still connected when it stops.
          case Some(channel) => Fiber.initUnscoped(contained(channel, host, token, stop, startedAt)).map(_ => ())
        _ <- if server.isOpen then loop else Sync.defer(())
      yield ()
    loop

  private def accept(server: ServerSocketChannel): Option[SocketChannel] =
    try Option(server.accept())
    catch
      // Closing the listener (on stop) makes the blocking accept throw; so does a connection that cannot be accepted. Either way there is
      // nothing to serve.
      case _: IOException => None

  /** A connection's failures stay with the connection: one broken peer must not take the daemon's other clients down with it. */
  private def contained(channel: SocketChannel, host: HostSessions, token: String, stop: Latch, startedAt: Long)(using
      Frame
  ): Unit < (Async & Scope & Abort[Any]) =
    Abort.run[Any](Scope.run(connection(channel, host, token, stop, startedAt))).map {
      case Result.Success(_)     => ()
      case Result.Failure(error) => java.lang.System.err.println(s"corvi host: a connection ended with $error")
      case Result.Panic(error)   => java.lang.System.err.println(s"corvi host: a connection panicked: $error")
    }

  /** One client, in a scope of its own: the handler, the wire, the socket and the client's registration all end with the connection,
    * whichever way it ends — the peer going away, a read error, or the daemon stopping.
    *
    * The end is signalled by `SocketWire`'s latch, which is released from three places (EOF, a read failure, and a local close) and `Latch`
    * release is once-only, so the first of them wakes `closed.await` and the rest are no-ops.
    */
  private def connection(
      channel: SocketChannel,
      host: HostSessions,
      token: String,
      stop: Latch,
      startedAt: Long
  )(using Frame): Unit < (Async & Scope & Abort[Any]) =
    for
      closed <- Latch.init(1)
      clientId <- host.nextClientId
      _ <- Scope.ensure(Sync.defer {
        val _ = channel.close()
        host.forgetUnsafe(clientId)
      })
      wire <- Sync.defer(new SocketWire(channel, closed))
      transport <- JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength)
      connection <- Sync.defer(Connection(clientId, token))
      handler <- JsonRpcHandler.init(transport, HostRoutes.routes(host, connection, startedAt, stop.release)*)
      bound <- host.bind(clientId, handler)
      _ <- if bound then Sync.defer(()) else Sync.defer { val _ = channel.close() }
      // The scope ends here: on EOF, on a read error, or when the daemon closes the socket under it.
      _ <- closed.await
    yield ()

  /** Binding the socket is the single-instance lock, and the lock is only taken from its own family.
    *
    * A socket that answers is somebody else's daemon (ours or, since the paths are namespaced, an interloper) and this one refuses to
    * start. A socket file with an owner marker whose process is gone is our own dead daemon: remove both and take the path. A socket file
    * with no marker is *not* ours — the installed Corvi app keeps one — so it is never unlinked; the daemon says so in one line and exits.
    */
  private def takeSocket(paths: HostPaths): Unit < Sync =
    if !Files.exists(paths.socket) then Sync.defer(())
    else
      SocketWire.answers(paths.socket, 500.millis).map { answered =>
        if answered then exitCleanly(s"another corvi host is listening on ${paths.socket}")
        else
          paths.owner() match
            case Some(pid) if !ProcessHandle.of(pid).isPresent =>
              val _ = Files.deleteIfExists(paths.socket)
              val _ = Files.deleteIfExists(paths.ownerFile)
            case Some(pid) =>
              exitCleanly(s"the socket ${paths.socket} belongs to pid $pid, which is still running")
            case None =>
              exitCleanly(s"${paths.socket} is not this daemon's socket; leaving it alone")
      }

  private def exitCleanly(message: String): Nothing =
    java.lang.System.err.println(s"corvi host: $message")
    java.lang.System.exit(1)
    throw new IllegalStateException(message)
