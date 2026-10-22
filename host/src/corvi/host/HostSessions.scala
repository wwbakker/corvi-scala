package corvi.host

import corvi.contract.*
import corvi.protocol.*
import corvi.protocol.Messages.*
import java.nio.file.Paths
import kyo.*
import kyo.Frame

/** The host side of the protocol: the live sessions, which clients are connected and which screens they are attached to, and the fiber that
  * pushes a session's changes.
  *
  * Session lifetime is a fiber's lifetime. `HostSession.open` wants a `Scope` — it registers the PTY's destroy there — and a request
  * handler has no `Scope` of its own, so each session is opened inside a scope of its own in an unscoped fiber, and closing the session is
  * completing a latch that scope waits on. The registries are only written here, and everything in them is immutable.
  *
  * **One registry.** A connection's handler and each of its session baselines live in the same map entry (`Connected`), so a client cannot
  * be attached to a session and yet have no handler to push to: `bind`, `unbind`, `attach` and every baseline advance are one atomic update
  * of that map. Keeping them in two maps made exactly that window real — a concurrent bind lost a handler (`bind` was a read-then-write),
  * the client stayed in a session's attachment map with its socket open, and its screen never updated again.
  *
  * Damage is pushed **per client**, against that client's own baseline frame: a delta computed over a window that straddled an attach would
  * otherwise describe a screen the attaching client never had, and since a delta is absolute, applying it would overwrite newer state. The
  * baseline advances only when the host has actually sent that client the delta that moves it, and only if the entry still exists.
  */
private[corvi] final class HostSessions private (
    command: Seq[String],
    pushInterval: kyo.Duration,
    sessions: AtomicRef[Map[SessionId, HostSessions.Managed]],
    connections: AtomicRef[Map[ClientId, HostSessions.Connected]],
    lastIndex: AtomicInt,
    lastClient: AtomicInt
):
  import HostSessions.*

  /** Binds a client's peer handler. An id that is already bound is refused and the first handler stays: two peers behind one id would each
    * be pushed a frame the other never saw.
    *
    * One atomic update, never a read followed by a write: connections bind concurrently at startup, and two read-then-write binds each
    * write back the map they read, so the later write silently drops the other handler.
    */
  def bind(client: ClientId, handler: JsonRpcHandler)(using Frame): Boolean < Sync =
    connections
      .getAndUpdate(current => if current.contains(client) then current else current.updated(client, Connected(handler, Map.empty)))
      .map(previous => !previous.contains(client))

  /** The clients whose pushes can be delivered, for the registry's own tests: a client that attached but is no longer here receives
    * nothing.
    */
  private[corvi] def boundClients(using Frame): Map[ClientId, JsonRpcHandler] < Sync =
    connections.get.map(all => all.view.mapValues(_.handler).toMap)

  /** The whole registry, handler and baselines, for the registry's own tests. */
  private[corvi] def connectionsByClient(using Frame): Map[ClientId, Connected] < Sync = connections.get

  /** Forgets a client that has gone: its handler is closed with its connection's scope, and its id must not be pushed to afterwards.
    * Removing the one entry drops every session baseline with it, so no session's push loop can keep sampling a client that is no longer
    * there.
    */
  def unbind(client: ClientId)(using Frame): Unit < Sync =
    Sync.defer(forgetUnsafe(client))

  /** The same from a scope finaliser, where an effect cannot be run. */
  private[corvi] def forgetUnsafe(client: ClientId): Unit =
    import AllowUnsafe.embrace.danger
    val _ = connections.unsafe.updateAndGet(current => current - client)

  /** A fresh client id, unique per connection. */
  def nextClientId(using Frame): ClientId < Sync =
    lastClient.getAndIncrement.map(index => ClientId(s"client-${index + 1}"))

  /** Sessions in the order the host created them, which is the order a tab strip shows. */
  def list(using Frame): Seq[SessionInfo] < Sync =
    sessions.get.map(all => all.values.toVector.sortBy(_.session.index).map(_.session.info))

  def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[UnknownSession | SessionFailed]) =
    for
      index <- lastIndex.getAndIncrement
      id = SessionId(s"session-${index + 1}")
      stop <- Latch.init(1)
      managed <- start(id, index, cwd, size, stop)
      _ <- sessions.updateAndGet(_.updated(id, managed)).map(_ => ())
      _ <- startPush(managed)
    yield managed.session.info

  /** The snapshot the client draws from, and the registration that makes this client part of the session's damage pushes. The snapshot is
    * taken *before* the registration, so the baseline is a frame this client is being handed rather than one it never saw.
    *
    * A client that is already attached keeps its baseline. Resetting it would take deltas that are already in flight out of the client's
    * history, and because a delta's rows are absolute, the frame a client had at the old baseline would be rewound by them and never
    * recover. An attach is therefore authoritative for a client that has no frame yet; a client that has one keeps applying deltas.
    *
    * The registration is an update of an *existing* entry, never an insert: a client whose connection ended while the snapshot was being
    * taken must not be put back into the registry, or it would be sampled forever with no handler (the bug the single map exists to
    * prevent).
    */
  def attach(client: ClientId, id: SessionId)(using Frame): Snapshot < (Async & Abort[UnknownSession]) =
    for
      managed <- lookup(id)
      snapshot <- Sync.defer(managed.session.snapshot())
      _ <- connections
        .updateAndGet(current =>
          current.updatedWith(client) { existing =>
            existing.map(connected =>
              if connected.attached.contains(id) then connected else connected.copy(attached = connected.attached.updated(id, snapshot))
            )
          }
        )
        .map(_ => ())
    yield snapshot

  def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[UnknownSession]) =
    lookup(id).map(managed => managed.session.history(from, count))

  /** Text for the shell's input. A session whose shell is gone is still listed (a tab shows its final screen) but cannot take input. */
  def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[UnknownSession | SessionEnded]) =
    live(id).map(managed => managed.session.write(text))

  def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[UnknownSession | SessionEnded]) =
    live(id).map(managed => managed.session.resize(size))

  /** Closes the shell and ends the scope that owned it. The session's push loop reports the end to every client still attached to it — a
    * client that closes a session still expects `session.ended` — and only then drops their baselines, because the end is the last thing
    * the session ever says. `close` therefore never drops a baseline itself: doing it here would race a shell that exited naturally in the
    * same tick and take the watchers away before the loop could tell them.
    */
  def close(id: SessionId)(using Frame): Unit < (Async & Abort[UnknownSession]) =
    for
      managed <- lookup(id)
      _ <- managed.closed.set(true)
      _ <- Sync.defer(managed.session.close())
      _ <- managed.stop.release
      _ <- sessions.updateAndGet(_ - id).map(_ => ())
    yield ()

  /** Forgets a session's baseline in every client: the session is gone, so nothing may keep sampling it. */
  private def dropBaselines(id: SessionId)(using Frame): Unit < Sync =
    connections
      .updateAndGet(current => current.map { case (client, connected) => client -> connected.copy(attached = connected.attached - id) })
      .map(_ => ())

  /** Ends every session: the scope that owns this service calls it, and each session's own scope follows. */
  def shutdown(using Frame): Unit < (Async & Abort[Throwable]) =
    for
      all <- sessions.get
      _ <- Async.foreachDiscard(all.values.toVector) { managed =>
        for
          _ <- Sync.defer(managed.session.close())
          _ <- managed.stop.release
        yield ()
      }
      _ <- sessions.set(Map.empty[SessionId, Managed])
      _ <- connections.set(Map.empty[ClientId, Connected])
    yield ()

  private def lookup(id: SessionId)(using Frame): Managed < (Sync & Abort[UnknownSession]) =
    for
      all <- sessions.get
      managed <- all.get(id) match
        case Some(found) => Sync.defer(found)
        case None        => Abort.fail(UnknownSession(id))
    yield managed

  private def live(id: SessionId)(using Frame): Managed < (Sync & Abort[UnknownSession | SessionEnded]) =
    for
      managed <- lookup(id)
      _ <- if managed.session.info.running then Sync.defer(()) else Abort.fail(SessionEnded(id))
    yield managed

  /** Opens the session inside a scope of its own, publishes it to whoever is waiting, and holds the scope open until the stop latch. A
    * shell that cannot start publishes the failure instead, so the caller sees it as an abort rather than as a timeout.
    */
  private def start(id: SessionId, index: Int, cwd: String, size: TermSize, stop: Latch)(using
      Frame
  ): Managed < (Async & Abort[UnknownSession | SessionFailed]) =
    for
      outcome <- AtomicRef.init[Maybe[Result[Throwable, HostSession]]](Maybe.empty)
      ready <- Latch.init(1)
      _ <- Fiber.initUnscoped {
        Scope.run {
          for
            result <- Abort.run[Throwable] {
              for
                session <- HostSession.open(id, index, Paths.get(cwd), command)
                _ <- Sync.defer(session.resize(size))
                _ <- outcome.set(Maybe(Result.Success(session)))
                _ <- ready.release
                _ <- stop.await
              yield ()
            }
            _ <- result match
              case Result.Success(_)     => Sync.defer(())
              case Result.Failure(error) =>
                for
                  _ <- outcome.set(Maybe(Result.Failure(error)))
                  _ <- ready.release
                yield ()
              case Result.Panic(error) =>
                for
                  _ <- outcome.set(Maybe(Result.Failure(error)))
                  _ <- ready.release
                yield ()
          yield ()
        }
      }
      _ <- ready.await
      published <- outcome.get
      session <- published match
        case Maybe.Present(Result.Success(found)) => Sync.defer(found)
        case Maybe.Present(Result.Failure(error)) => Abort.fail(SessionFailed(id, error.toString))
        case Maybe.Present(Result.Panic(error))   => Abort.fail(SessionFailed(id, error.toString))
        case Maybe.Absent                         => Abort.fail(SessionFailed(id, "the shell did not start"))
      closed <- AtomicRef.init(false)
    yield Managed(session, stop, closed)

  private def startPush(managed: Managed)(using Frame): Unit < Sync =
    Fiber.initUnscoped(pushLoop(managed)).map(_ => ())

  /** Pushes what changed to each client attached to this session, until the shell is gone. Sampling is the coalescer: everything that
    * arrived since the previous tick leaves as one delta per client, an unchanged frame costs nothing on the wire, and a session nobody is
    * watching is not sampled at all.
    */
  private def pushLoop(managed: Managed)(using Frame): Unit < Async =
    def loop: Unit < Async =
      for
        _ <- Async.sleep(pushInterval)
        _ <- pushDamage(managed)
        _ <- if managed.session.info.running then loop else pushEnded(managed)
      yield ()
    loop

  private def pushDamage(managed: Managed)(using Frame): Unit < Async =
    def one(client: ClientId, baseline: Snapshot): Unit < Async =
      for
        step <- Sync.defer(managed.session.frameSince(baseline))
        (frame, damage) = step
        _ <- if damage.isEmpty then Sync.defer(()) else push(client, Methods.Damage, DamageParams(frame.id, damage))
        // The baseline moves with the delta, whether or not the send was delivered: `send` logs a failure and carries on, because a failed
        // push means the peer's connection is gone and its unbind removes the entry. `updatedWith` on the entry and on the session keeps a client, or a session, that was unbound or closed
        // while this tick was in flight out of the registry: `updated` would put it back and sample it forever.
        _ <- connections
          .updateAndGet(current =>
            current.updatedWith(client) { existing =>
              existing.map(connected =>
                connected.copy(attached = connected.attached.updatedWith(managed.session.id)(current => current.map(_ => frame)))
              )
            }
          )
          .map(_ => ())
      yield ()
    for
      current <- connections.get
      watchers = current.toVector.collect {
        case (client, connected) if connected.attached.contains(managed.session.id) =>
          (client, connected.attached(managed.session.id))
      }
      _ <- Async.foreachDiscard(watchers)((client, baseline) => one(client, baseline))
    yield ()

  private def pushEnded(managed: Managed)(using Frame): Unit < Async =
    for
      closed <- managed.closed.get
      code <- Sync.defer(managed.session.exitCode)
      reason <- Sync.defer(managed.session.error.map(_.toString))
      current <- connections.get
      ended = Ended(code = code, closed = closed, reason = reason)
      watchers = current.toVector.collect {
        case (client, connected) if connected.attached.contains(managed.session.id) => (client, connected.handler)
      }
      _ <- Async.foreachDiscard(watchers)((_, handler) => send(handler, Methods.Ended, EndedParams(managed.session.id, ended)))
      // The end is the session's last message: once it has been sent, the baselines it was sent to are dropped.
      _ <- dropBaselines(managed.session.id)
    yield ()

  /** A push reads the handler from the same registry as the baseline. The only race left is a client whose connection ends between the
    * tick's snapshot and this send; that client is gone, so the push is dropped rather than reported — it is not bookkeeping slipping, it
    * is a peer that has left.
    */
  private def push[A: Schema](client: ClientId, method: String, params: A)(using Frame): Unit < Async =
    for
      current <- connections.get
      _ <- current.get(client) match
        case Some(connected) => send(connected.handler, method, params)
        case None            => Sync.defer(())
    yield ()

  /** A push to a client that has gone away is not the host's problem: the send fails with `Closed` and the loop carries on. */
  private def send[A: Schema](handler: JsonRpcHandler, method: String, params: A)(using Frame): Unit < Async =
    Abort.run[Closed](handler.notify[A](method, params)).map {
      case Result.Success(_)     => ()
      case Result.Failure(error) => java.lang.System.err.println(s"corvi host: sending $method failed: $error")
      case Result.Panic(error)   => java.lang.System.err.println(s"corvi host: sending $method panicked: $error")
    }

private[corvi] object HostSessions:
  /** One connection: its peer handler, and this client's baseline frame for every session it has attached to. One entry, because a client
    * with a baseline and no handler is a screen that never updates again.
    */
  private[corvi] final case class Connected(handler: JsonRpcHandler, attached: Map[SessionId, Snapshot])

  /** A session, the latch that ends the scope holding it, and whether a client ended it. */
  private[corvi] final case class Managed(session: HostSession, stop: Latch, closed: AtomicRef[Boolean])

  /** The shell to run is a parameter so a caller can prove what a shell that cannot start does. `pushInterval` is too, so a test does not
    * have to wait a frame.
    */
  def init(pushInterval: kyo.Duration, command: Seq[String])(using Frame): HostSessions < Sync =
    for
      sessions <- AtomicRef.init(Map.empty[SessionId, Managed])
      connections <- AtomicRef.init(Map.empty[ClientId, Connected])
      lastIndex <- AtomicInt.init(0)
      lastClient <- AtomicInt.init(0)
    yield new HostSessions(command, pushInterval, sessions, connections, lastIndex, lastClient)
