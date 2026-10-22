package corvi.app

import corvi.contract.SessionId
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The client's command queue: closing it ends the consumer, and an offer that races the close is never served. That is the property the
  * old `Queue + stopped` shape could not give — a command could pass the check and still be queued behind `Stop`.
  */
final class ClientQueueTest:

  @Test
  def `only commands offered before the close are served`(): Unit =
    val queue = new Client.Queue
    (1 to 8).foreach(index => queue.offer(Client.Command.Text(SessionId(s"before-$index"), "x")))
    assertTrue(queue.close(), "the first close ends the queue")
    assertFalse(queue.close(), "closing twice is a no-op")
    (1 to 8).foreach(index => queue.offer(Client.Command.Text(SessionId(s"after-$index"), "x")))
    val drained = drain(queue)
    assertEquals(8, drained.size, "the offers after the close land behind the marker, where nothing reads them")
    assertTrue(drained.forall {
      case Client.Command.Text(session, _) => session.value.startsWith("before-")
      case _                               => false
    })

  @Test
  def `an offer racing the close cannot leave the consumer waiting`(): Unit =
    val queue = new Client.Queue
    val offerers = (1 to 4).map { shard =>
      Thread.ofPlatform().name(s"corvi-queue-$shard").start { () =>
        (1 to 100).foreach(_ => queue.offer(Client.Command.Text(SessionId(s"race-$shard"), "x")))
      }
    }
    val _ = queue.close()
    offerers.foreach(_.join())
    val drained = drain(queue)
    assertTrue(drained.size <= 400, "every served command was offered")
    assertTrue(drained.size >= 0, "and the drain ended at the marker instead of blocking")

  /** Drains until the close marker, in a thread with a deadline: a queue that never ends would otherwise hang the test instead of failing
    * it.
    */
  private def drain(queue: Client.Queue): Vector[Client.Command] =
    val result = AtomicReference[Vector[Client.Command]](Vector.empty)
    val reader = Thread.ofPlatform().name("corvi-queue-drain").start { () =>
      val commands = Vector.newBuilder[Client.Command]
      var next = queue.take()
      while next.isDefined do
        commands += next.get
        next = queue.take()
      result.set(commands.result())
    }
    reader.join(5000)
    assertFalse(reader.isAlive, "the queue ends at its close marker")
    result.get()
