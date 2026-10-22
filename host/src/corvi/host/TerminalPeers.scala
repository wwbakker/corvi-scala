package corvi.host

import corvi.protocol.*
import kyo.*
import kyo.Frame

/** Both ends of the protocol over `kyo-jsonrpc`'s in-memory transport.
  *
  * The next phase replaces the transport with a Unix socket and nothing else: the host peer answers the same routes over the same live
  * sessions, and a client is whatever peer connected.
  */
object TerminalPeers:

  /** A client and the id the host knows it by. */
  final case class Wired(client: TerminalService, id: ClientId)

  /** A running host, in the scope that created it, which clients can attach to as they connect. */
  final class InMemoryHost private[corvi] (val sessions: HostSessions):

    /** Wires another client over a fresh in-memory transport pair: the "attach a client to a running host" entry point. The peers live as
      * long as the caller's scope.
      */
    def client(sink: FrameSink)(using Frame): Wired < (Async & Scope & Abort[ClientAlreadyBound]) =
      for
        id <- sessions.nextClientId
        wired <- client(sink, id)
      yield wired

    /** The same with an id the caller chooses; an id already in use is refused rather than taking over the other peer's pushes. */
    def client(sink: FrameSink, id: ClientId)(using Frame): Wired < (Async & Scope & Abort[ClientAlreadyBound]) =
      for
        transports <- JsonRpcTransport.inMemory
        (clientSide, hostSide) = transports
        // In memory both peers are one process and there is no door to knock on, so the connection is trusted from the start.
        hostHandler <- JsonRpcHandler.init(
          hostSide,
          HostRoutes.routes(sessions, Connection.trusted(id), java.lang.System.currentTimeMillis(), Sync.defer(()))*
        )
        bound <- sessions.bind(id, hostHandler)
        _ <- if bound then Sync.defer(()) else Abort.fail(ClientAlreadyBound(id))
        clientHandler <- JsonRpcHandler.init(clientSide, ClientRoutes.routes(sink)*)
      yield Wired(TerminalClient(clientHandler), id)

  /** A host inside the caller's scope: its sessions end with the scope, and its shell and push interval are what the caller asked for. */
  def host(pushInterval: kyo.Duration, command: Seq[String])(using Frame): InMemoryHost < (Async & Scope) =
    for
      sessions <- HostSessions.init(pushInterval, command)
      _ <- Scope.ensure(sessions.shutdown)
    yield new InMemoryHost(sessions)

  /** A host with one client on it, which is what the probes and the renderer start from. */
  def inMemory(sink: FrameSink, pushInterval: kyo.Duration, command: Seq[String])(using
      Frame
  ): (TerminalService, InMemoryHost) < (Async & Scope & Abort[ClientAlreadyBound]) =
    for
      host <- TerminalPeers.host(pushInterval, command)
      wired <- host.client(sink)
    yield (wired.client, host)
