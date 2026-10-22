package corvi.host

import corvi.contract.*
import corvi.protocol.*
import corvi.protocol.Messages.*
import java.nio.file.Paths
import kyo.*
import kyo.Frame

/** The host side of the protocol: the live sessions, which clients are attached to them, and the fiber that pushes a session's changes.
  *
  * Session lifetime is a fiber's lifetime. `HostSession.open` wants a `Scope` — it registers the PTY's destroy there — and a request
  * handler has no `Scope` of its own, so each session is opened inside a scope of its own in an unscoped fiber, and closing the session is
  * completing a latch that scope waits on. The registry's map is only written here, and everything in it is immutable.
  *
  * Damage is pushed **per client**, against that client's own baseline frame: a delta computed over a window that straddled an attach would
  * otherwise describe a screen the attaching client never had, and since a delta is absolute, applying it would overwrite newer state. The
  * baseline advances only when the host has actually sent that client the delta that moves it.
  */
private[corvi] final class HostSessions private (
    command: Seq[String],
    pushInterval: kyo.Duration,
    sessions: AtomicRef[Map[SessionId, HostSessions.Managed]],
    clients: AtomicRef[Map[ClientId, JsonRpcHandler]],
    lastIndex: AtomicInt,
    lastClient: AtomicInt
):
  import HostSessions.*

  /** Binds a client's peer handler. An id that is already bound is refused: two peers behind one id would each be pushed a frame the other
    * never saw.
    *
    * One atomic update, never a read followed by a write: connections bind concurrently at startup, and two read-then-write binds each
    * write back the map they read, so the later write silently drops the other handler. That client stays in every session's `attached` map
    * with a live connection and receives nothing — the push loop drops its damage as "no handler", the socket stays open, and the terminal
    * never paints.
    */
  def bind(client: ClientId, handler: JsonRpcHandler)(using Frame): Boolean < Sync =
    clients
      .getAndUpdate(current => if current.contains(client) then current else current.updated(client, handler))
      .map(previous => !previous.contains(client))

  /** The clients whose pushes can be delivered, for the registry's own tests: a client that attached but is no longer here receives
    * nothing.
    */
  private[corvi] def boundClients(using Frame): Map[ClientId, JsonRpcHandler] < Sync = clients.get

  /** Forgets a client that has gone: its handler is closed with its connection's scope, and its id must not be pushed to afterwards. */
  def unbind(client: ClientId)(using Frame): Unit < Sync =
    Sync.defer(forgetUnsafe(client))

  /** The same from a scope finaliser, where an effect cannot be run. The client is dropped from the session baselines too: a closed
    * connection that stayed in a session's map would keep that session's push loop computing deltas for a client it can never reach.
    */
  private[corvi] def forgetUnsafe(client: ClientId): Unit =
    import AllowUnsafe.embrace.danger
    val _ = clients.unsafe.updateAndGet(current => current - client)
    val _ = sessions.unsafe.updateAndGet(all =>
      all.map { case (id, managed) =>
        val _ = managed.attached.unsafe.updateAndGet(frames => frames - client)
        (id, managed)
      }
    )

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
    */
  def attach(client: ClientId, id: SessionId)(using Frame): Snapshot < (Async & Abort[UnknownSession]) =
    for
      managed <- lookup(id)
      snapshot <- Sync.defer(managed.session.snapshot())
      _ <- managed.attached
        .updateAndGet(frames => if frames.contains(client) then frames else frames.updated(client, snapshot))
        .map(_ => ())
    yield snapshot

  def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[UnknownSession]) =
    lookup(id).map(managed => managed.session.history(from, count))

  /** Text for the shell's input. A session whose shell is gone is still listed (a tab shows its final screen) but cannot take input. */
  def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[UnknownSession | SessionEnded]) =
    live(id).map(managed => managed.session.write(text))

  def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[UnknownSession | SessionEnded]) =
    live(id).map(managed => managed.session.resize(size))

  /** Closes the shell and ends the scope that owned it. The session's push loop then reports the end and stops. */
  def close(id: SessionId)(using Frame): Unit < (Async & Abort[UnknownSession]) =
    for
      managed <- lookup(id)
      _ <- managed.closed.set(true)
      _ <- Sync.defer(managed.session.close())
      _ <- managed.stop.release
      _ <- sessions.updateAndGet(_ - id).map(_ => ())
    yield ()

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
      attached <- AtomicRef.init(Map.empty[ClientId, Snapshot])
      closed <- AtomicRef.init(false)
    yield Managed(session, stop, attached, closed)

  private def startPush(managed: Managed)(using Frame): Unit < Sync =
    Fiber.initUnscoped(pushLoop(managed)).map(_ => ())

  /** Pushes what changed to each attached client, until the shell is gone. Sampling is the coalescer: everything that arrived since the
    * previous tick leaves as one delta per client, an unchanged frame costs nothing on the wire, and a session nobody is watching is not
    * sampled at all.
    */
  private def pushLoop(managed: Managed)(using Frame): Unit < Async =
    def loop: Unit < Async =
      for
        _ <- Async.sleep(pushInterval)
        attached <- managed.attached.get
        _ <- if attached.isEmpty then Sync.defer(()) else pushDamage(managed, attached)
        _ <- if managed.session.info.running then loop else pushEnded(managed)
      yield ()
    loop

  private def pushDamage(managed: Managed, attached: Map[ClientId, Snapshot])(using Frame): Unit < Async =
    def one(client: ClientId, baseline: Snapshot): Unit < Async =
      for
        step <- Sync.defer(managed.session.frameSince(baseline))
        (frame, damage) = step
        _ <- if damage.isEmpty then Sync.defer(()) else push(client, Methods.Damage, DamageParams(frame.id, damage))
        // The baseline only moves once the delta has been sent, so a failed send leaves this client where it was and the next tick
        // recomputes from there. `updatedWith` keeps a client that `forgetUnsafe` removed while this tick was in flight out of the map:
        // `updated` would put it back, and it would be sampled again forever with no handler to push to.
        _ <- managed.attached
          .updateAndGet(frames => frames.updatedWith(client)(current => current.map(_ => frame)))
          .map(_ => ())
      yield ()
    for
      handlers <- clients.get
      // A client whose handler has gone is dropped from this session's baselines the first time a tick finds it: the map is supposed to be
      // cleaned with the connection, so a missing handler is a bookkeeping slip, and it is reported once — the removal is what stops the
      // report repeating at the push loop's 60 Hz.
      stale = attached.keys.filterNot(handlers.contains)
      _ <- Async.foreachDiscard(stale.toVector) { client =>
        managed.attached.updateAndGet(frames => frames - client).map { _ =>
          java.lang.System.err.println(
            s"corvi host: no handler for ${client.value}; dropping it from ${managed.session.id.value}"
          )
        }
      }
      live = attached.filter((client, _) => handlers.contains(client))
      _ <- Async.foreachDiscard(live.toVector)((client, baseline) => one(client, baseline))
    yield ()

  private def pushEnded(managed: Managed)(using Frame): Unit < Async =
    for
      closed <- managed.closed.get
      code <- Sync.defer(managed.session.exitCode)
      reason <- Sync.defer(managed.session.error.map(_.toString))
      attached <- managed.attached.get
      handlers <- clients.get
      ended = Ended(code = code, closed = closed, reason = reason)
      _ <- Async.foreachDiscard(attached.keys.toVector) { client =>
        handlers.get(client) match
          case Some(handler) => send(handler, Methods.Ended, EndedParams(managed.session.id, ended))
          case None          => Sync.defer(())
      }
    yield ()

  /** A push to a client whose connection has gone is a bug the host can see: the baseline map is supposed to forget it with the connection,
    * so a missing handler means that bookkeeping slipped rather than a peer that is merely slow.
    */
  private def push[A: Schema](client: ClientId, method: String, params: A)(using Frame): Unit < Async =
    for
      handlers <- clients.get
      _ <- handlers.get(client) match
        case Some(handler) => send(handler, method, params)
        case None          =>
          Sync.defer(
            java.lang.System.err.println(s"corvi host: no handler for ${client.value}; dropping $method")
          )
    yield ()

  /** A push to a client that has gone away is not the host's problem: the send fails with `Closed` and the loop carries on. */
  private def send[A: Schema](handler: JsonRpcHandler, method: String, params: A)(using Frame): Unit < Async =
    Abort.run[Closed](handler.notify[A](method, params)).map {
      case Result.Success(_)     => ()
      case Result.Failure(error) => java.lang.System.err.println(s"corvi host: sending $method failed: $error")
      case Result.Panic(error)   => java.lang.System.err.println(s"corvi host: sending $method panicked: $error")
    }

private[corvi] object HostSessions:
  /** A session, the latch that ends the scope holding it, each client's baseline frame, and whether a client ended it. */
  private[corvi] final case class Managed(
      session: HostSession,
      stop: Latch,
      attached: AtomicRef[Map[ClientId, Snapshot]],
      closed: AtomicRef[Boolean]
  )

  /** The shell to run is a parameter so a caller can prove what a shell that cannot start does. `pushInterval` is too, so a test does not
    * have to wait a frame.
    */
  def init(pushInterval: kyo.Duration, command: Seq[String])(using Frame): HostSessions < Sync =
    for
      sessions <- AtomicRef.init(Map.empty[SessionId, Managed])
      clients <- AtomicRef.init(Map.empty[ClientId, JsonRpcHandler])
      lastIndex <- AtomicInt.init(0)
      lastClient <- AtomicInt.init(0)
    yield new HostSessions(command, pushInterval, sessions, clients, lastIndex, lastClient)
