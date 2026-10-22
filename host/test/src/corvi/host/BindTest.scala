package corvi.host

import corvi.protocol.ClientId
import kyo.*
import kyo.Frame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scala.concurrent.duration.*

/** The client registry's binding, checked for the race it had.
  *
  * `bind` used to read the map and write it back (`use` then `set`), so two connections binding at startup could each write back what they
  * read and silently drop the other's handler. That client kept its session attachment and its open socket and was never pushed to again.
  *
  * The binds here run on separate threads with a runtime each, which is what the daemon does with concurrent connections: Kyo's own fibers
  * in one runtime can run a `Sync` read-modify-write without yielding, so only real parallelism exercises the window. The read-then-set
  * version loses entries under this shape; the atomic `getAndUpdate` does not.
  */
final class BindTest:

  private val count: Int = 64
  private val shards: Int = 8

  @Test
  def `concurrent binds keep every client, and a duplicate is refused`(): Unit =
    val clients = (1 to count).toVector.map(index => ClientId(s"client-$index"))
    val host = runKyo { HostSessions.init(kyo.Duration.fromScala(16.millis), HostSession.loginShell) }
    val handlers = runKyo { Async.foreach(clients)(_ => handler).map(_.toVector) }
    val pairs = clients.zip(handlers)

    val threads = pairs.grouped(count / shards).toVector.map { shard =>
      Thread.ofPlatform().name("corvi-bind-test").start { () =>
        val _ = runKyo { Async.foreach(shard)((id, peer) => host.bind(id, peer)) }
      }
    }
    threads.foreach(thread => thread.join())

    val registered = runKyo(host.boundClients)
    assertEquals(clients.toSet, registered.keySet, "every client is still in the registry; a lost update would drop some")

    val other = runKyo(handler)
    val duplicate = runKyo { host.bind(clients.head, other) }
    assertFalse(duplicate, "a second bind of an id already in use is refused")
    val after = runKyo(host.boundClients)
    assertSame(registered(clients.head), after(clients.head), "and the first handler is still the bound one")
    assertEquals(registered, after, "and the registry is otherwise untouched")

  /** A handler of its own per client: the registry only stores them, so the routes and the transport do not matter here. */
  private def handler(using Frame): JsonRpcHandler < Async =
    JsonRpcTransport.inMemory.flatMap((clientSide, _) => JsonRpcHandler.initUnscoped(clientSide, Seq.empty))

  private def runKyo[A](v: A < (Async & Scope & Abort[Any])): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(60.seconds))(v) match
      case Result.Success(value) => value
      case Result.Failure(error) => throw new IllegalStateException(s"the bind phase failed: $error")
      case Result.Panic(error)   => throw new IllegalStateException(s"the bind phase panicked: $error")
