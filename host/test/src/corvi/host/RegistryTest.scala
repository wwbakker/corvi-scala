package corvi.host

import corvi.contract.{Damage, Ended, SessionId, TermSize}
import corvi.protocol.{ClientId, ClientRoutes, FrameSink}
import kyo.*
import kyo.Frame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scala.concurrent.duration.*

/** The connection registry: one entry per client holds that client's handler *and* its per-session baselines, and every write is one atomic
  * update of that one map. These tests are about the invariant that made the two-map version fail: a client can never have a baseline
  * without a handler, and a client that has gone can never be brought back by a late attach or a baseline advance.
  */
final class RegistryTest:

  private val interval: kyo.Duration = kyo.Duration.fromScala(16.millis)
  private def cwd: String = java.lang.System.getProperty("user.dir")

  @Test
  def `an attach racing an unbind leaves nothing behind`(): Unit =
    val (session, before, after) = runKyo {
      Scope.run {
        for
          host <- HostSessions.init(interval, HostSession.loginShell)
          session <- host.create(cwd, TermSize(40, 10))
          client <- host.nextClientId
          peer <- handler
          _ <- host.bind(client, peer)
          _ <- host.attach(client, session.id)
          registered <- host.connectionsByClient
          attaching <- Fiber.init(host.attach(client, session.id))
          unbinding <- Fiber.init(host.unbind(client))
          _ <- attaching.get
          _ <- unbinding.get
          // A late attach must not put the entry back: it is an update of an existing client, never an insert.
          _ <- host.attach(client, session.id)
          late <- host.connectionsByClient
          _ <- host.shutdown
        yield (session.id, registered, late)
      }
    }
    assertTrue(before.values.exists(_.attached.contains(session)), "the first attach registered a baseline")
    assertTrue(after.isEmpty, s"an unbound client leaves neither a handler nor a baseline: $after")

  @Test
  def `a client attached to two sessions and unbound leaves neither`(): Unit =
    val (attached, after) = runKyo {
      Scope.run {
        for
          host <- HostSessions.init(interval, HostSession.loginShell)
          first <- host.create(cwd, TermSize(40, 10))
          second <- host.create(cwd, TermSize(42, 12))
          client <- host.nextClientId
          peer <- handler
          _ <- host.bind(client, peer)
          _ <- host.attach(client, first.id)
          _ <- host.attach(client, second.id)
          attached <- host.connectionsByClient
          _ <- host.unbind(client)
          after <- host.connectionsByClient
          _ <- host.shutdown
        yield (attached(client).attached.keySet, after)
      }
    }
    assertEquals(Set(SessionId("session-1"), SessionId("session-2")), attached, "both sessions are baselines of the client")
    assertTrue(after.isEmpty, s"the unbind drops the handler and both baselines: $after")

  @Test
  def `closing a session drops every client's baseline for it`(): Unit =
    val after = runKyo {
      Scope.run {
        for
          host <- HostSessions.init(interval, HostSession.loginShell)
          session <- host.create(cwd, TermSize(40, 10))
          client <- host.nextClientId
          peer <- handler
          _ <- host.bind(client, peer)
          _ <- host.attach(client, session.id)
          _ <- host.close(session.id)
          // The close asks the push loop to report the end and drop the baseline; wait for that rather than for a fixed sleep.
          _ <- poll(50)(host.connectionsByClient.map(_.values.forall(_.attached.isEmpty)))
          after <- host.connectionsByClient
          _ <- host.shutdown
        yield after
      }
    }
    assertEquals(1, after.size, "the client stays connected after the session closes")
    assertTrue(after.values.forall(_.attached.isEmpty), s"a closed session is not a baseline any more: $after")

  @Test
  def `both clients hear the end when a shell exits and its tab closes in the same tick`(): Unit =
    val rounds = runKyo {
      Scope.run {
        for
          host <- HostSessions.init(interval, HostSession.loginShell)
          rounds <- Async.foreach(1 to 8) { _ =>
            for
              session <- host.create(cwd, TermSize(40, 10))
              (firstClient, firstSink) <- wire(host)
              (secondClient, secondSink) <- wire(host)
              _ <- host.attach(firstClient, session.id)
              _ <- host.attach(secondClient, session.id)
              // The shell exits by itself, and the tab is closed as soon as the exit is visible — which is inside the push loop's next tick as
              // often as not. The loop, not `close`, is what reports the end, so both clients must still be told; a `close` that dropped the
              // baselines first would leave them with nothing. Repeated because the race is real but its two orderings are equally likely.
              _ <- host.text(session.id, "exit\r")
              _ <- poll(400, pauseMillis = 2)(host.list.map(infos => infos.find(_.id == session.id).forall(!_.running)))
              _ <- host.close(session.id)
              _ <- poll(100)(
                Sync.defer(firstSink.endedFor(session.id).isDefined && secondSink.endedFor(session.id).isDefined)
              )
            yield (session.id, firstSink.endedFor(session.id), secondSink.endedFor(session.id))
          }
          _ <- host.shutdown
        yield rounds.toVector
      }
    }
    rounds.foreach { (id, first, second) =>
      assertTrue(first.isDefined, s"the first client was told ${id.value} ended: $first")
      assertTrue(second.isDefined, s"the second client was told ${id.value} ended: $second")
    }

  /** A client of the in-memory host whose pushes land in a sink this test can read. */
  private def wire(host: HostSessions)(using Frame): (ClientId, RecordingSink) < (Async & Scope & Abort[Any]) =
    for
      client <- host.nextClientId
      sink <- Sync.defer(RecordingSink())
      transports <- JsonRpcTransport.inMemory
      (clientSide, hostSide) = transports
      // The client side answers the two push routes; the host side is the handler the registry pushes through.
      _ <- JsonRpcHandler.initUnscoped(clientSide, ClientRoutes.routes(sink)*)
      handler <- JsonRpcHandler.initUnscoped(hostSide, Seq.empty)
      _ <- host.bind(client, handler)
    yield (client, sink)

  private final class RecordingSink extends FrameSink:
    private val endedBySession = new java.util.concurrent.ConcurrentHashMap[SessionId, Ended]()
    def damage(id: SessionId, damage: Damage): Unit < Sync = Sync.defer(())
    def ended(id: SessionId, ended: Ended): Unit < Sync =
      Sync.defer { val _ = endedBySession.put(id, ended) }
    def endedFor(id: SessionId): Option[Ended] = Option(endedBySession.get(id))

  /** Re-runs a condition until it holds or the checks run out: a fixed sleep either wastes time or flakes, and the push loop's tick is the
    * thing being waited for.
    */
  private def poll(checks: Int, pauseMillis: Long = 20)(condition: => Boolean < Sync): Boolean < Async =
    def loop(remaining: Int): Boolean < Async =
      for
        satisfied <- condition
        result <-
          if satisfied || remaining <= 0 then Sync.defer(satisfied)
          else Async.sleep(kyo.Duration.fromScala(pauseMillis.millis)).andThen(loop(remaining - 1))
      yield result
    loop(checks)

  /** A handler of its own per client: the registry only stores them, so the routes and the transport do not matter here. */
  private def handler(using Frame): JsonRpcHandler < Async =
    JsonRpcTransport.inMemory.flatMap((clientSide, _) => JsonRpcHandler.initUnscoped(clientSide, Seq.empty))

  private def runKyo[A](v: A < (Async & Scope & Abort[Any])): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(60.seconds))(v) match
      case Result.Success(value) => value
      case Result.Failure(error) => throw new IllegalStateException(s"the registry phase failed: $error")
      case Result.Panic(error)   => throw new IllegalStateException(s"the registry phase panicked: $error")
