package corvi.app

import corvi.contract.*
import corvi.protocol.*
import corvi.renderer.SessionEvents
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.Frame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import scala.jdk.CollectionConverters.*

/** The client's capability-routed input commands ignore the same session-gone errors as its other commands.
  *
  * A focus or pointer event can race the shell's exit; reporting that as a window failure is noise, and a closed tab must not print "focus
  * failed: SessionEnded". The fake service raises the typed error from its `focus` call, so this exercises the route and the client's
  * filtering without a PTY.
  */
@ExtendWith(Array(classOf[ApplicationExtension]))
final class ClientCapabilityTest:

  private val session = SessionId("capability-test")

  private final class RecordingEvents extends SessionEvents:
    private val attachedLatch = new CountDownLatch(1)
    private val historyLatch = new CountDownLatch(1)
    private val failures = new ConcurrentLinkedQueue[String]()

    def attached(snapshot: Snapshot): Unit = attachedLatch.countDown()
    def history(rows: Vector[Row]): Unit = historyLatch.countDown()
    def sessionEnded(ended: Ended): Unit = ()
    def failed(message: String): Unit =
      val _ = failures.add(message)

    def awaitAttached(): Boolean = attachedLatch.await(30, TimeUnit.SECONDS)
    def awaitHistory(): Boolean = historyLatch.await(30, TimeUnit.SECONDS)
    def reportedFailures: Vector[String] = failures.iterator().asScala.toVector

  /** Only `attach`, `focus` and the history ordering barrier matter here; the rest are inert implementations of the service seam. */
  private final class FakeService(failClosed: Boolean = false) extends TerminalService:
    def hello(token: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def status(using Frame): HostStatus < (Async & Abort[TerminalError | Closed]) = Sync.defer(HostStatus(0, 0, 0))
    def identity(using Frame): HostIdentity < (Async & Abort[TerminalError | Closed]) = Sync.defer(HostIdentity(0, "test"))
    def stop(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def list(using Frame): Seq[SessionInfo] < (Async & Abort[TerminalError | Closed]) = Sync.defer(Seq.empty[SessionInfo])
    def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[TerminalError | Closed]) =
      Abort.fail(SessionFailed(SessionId("unused"), "not used"))
    def attach(id: SessionId)(using Frame): Snapshot < (Async & Abort[TerminalError | Closed]) = Sync.defer(snapshot(id))
    def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[TerminalError | Closed]) =
      Sync.defer(Vector.empty[Row])
    def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def mouse(id: SessionId, input: MouseInput)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def key(id: SessionId, input: KeyInput)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def focus(id: SessionId, focused: Boolean)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
      if failClosed then Abort.fail(new Closed("test socket", summon[Frame], "the host went away"))
      else Abort.fail(SessionEnded(id))
    def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def close(id: SessionId)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def ack(id: SessionId, seq: Long)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())

  private def snapshot(id: SessionId): Snapshot =
    Snapshot(
      id = id,
      size = TermSize(80, 24),
      rows = Vector.empty,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = 0,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  @Test
  def `a Closed command signals connection loss once`(): Unit =
    val client = Client(new FakeService(failClosed = true), new ViewSink)
    val lost = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    client.onConnectionLost(() => {
      val _ = calls.incrementAndGet()
      lost.countDown()
    })
    val serving = Thread.ofPlatform().name("closed-client").daemon(true).start { () =>
      import AllowUnsafe.embrace.danger
      val _ = KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity)(client.serve)
    }
    try
      client.focus(session, focused = true)
      assertTrue(lost.await(5, TimeUnit.SECONDS), "a Closed command wakes the supervisor")
      assertEquals(1, calls.get(), "the connection-lost signal is delivered once")
    finally
      client.stop()
      serving.join(5000)

  @Test
  def `a focus event that races an ended session is ignored`(): Unit =
    val client = Client(new FakeService(), new ViewSink)
    val events = new RecordingEvents
    val serving = Thread.ofPlatform().name("capability-client").daemon(true).start { () =>
      import AllowUnsafe.embrace.danger
      val _ = KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity)(client.serve)
    }
    try
      client.attach(session, events)
      assertTrue(events.awaitAttached(), "the client attached to the session")

      client.focus(session, focused = true)
      // The history callback runs through the same FX queue after the focus command, so it is the barrier that proves the focus command
      // has been served and any failure it queued has already run.
      client.history(session, from = 1, count = 10)
      assertTrue(events.awaitHistory(), "a later command was served after the focus command")

      assertEquals(Vector.empty, events.reportedFailures, "a session-gone focus failure is not shown to the view")
    finally
      client.stop()
      serving.join(5000)
