package corvi.app

import corvi.contract.*
import corvi.protocol.*
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javafx.application.Platform
import javafx.scene.control.Label
import javafx.stage.Stage
import kyo.*
import kyo.Frame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{AfterEach, Test}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The window's connection supervisor, driven with fake clients so the reconnect state, empty replacement and backoff are deterministic. */
@ExtendWith(Array(classOf[ApplicationExtension]))
final class ReconnectTest:

  private val stage = AtomicReference[Option[Stage]](None)
  private val app = AtomicReference[Option[AppWindow]](None)

  @Start
  def start(stage: Stage): Unit = this.stage.set(Some(stage))

  @AfterEach
  def stop(): Unit =
    app.getAndSet(None).foreach(window => onFx(window.close()))

  @Test
  def `a lost connection shows reconnecting and lands on Overview when the replacement is empty`(): Unit =
    val first = sessionInfo("first")
    val clients = Vector(newClient(first), newClient())
    val next = new AtomicInteger(0)
    val window = createWindow(
      () => Sync.defer(clients(next.getAndIncrement())),
      sleepFor = _ => Async.sleep(kyo.Duration.fromScala(250.millis))
    )
    assertTrue(await(10.seconds)(window.shell.isDefined), "the first connection mounts the shell")
    val liveShell = window.shell.get
    assertTrue(await(10.seconds)(onFx(liveShell.current.windows.nonEmpty)), "the first session opened")
    clients(0).signalConnectionLost().asInstanceOf[Unit]

    assertTrue(await(10.seconds)(statusOf.exists(_.contains("Host lost; reconnecting"))), s"status ${statusOf}")
    assertTrue(
      await(10.seconds)(onFx(window.shell.exists(_.current.windows.isEmpty))),
      "an empty replacement does not open a blank terminal"
    )
    assertTrue(await(10.seconds)(statusOf.isEmpty), "the replacement shell replaces the reconnect status")

  @Test
  def `a replacement host reattaches the sessions it lists`(): Unit =
    val first = newClient(sessionInfo("first"))
    val replacement = sessionInfo("replacement")
    val clients = Vector(first, newClient(replacement))
    val next = new AtomicInteger(0)
    val window = createWindow(() => Sync.defer(clients(next.getAndIncrement())))
    assertTrue(await(10.seconds)(window.shell.exists(_.current.windows.nonEmpty)), "the first session opened")
    first.signalConnectionLost().asInstanceOf[Unit]
    val replacementMounted = await(10.seconds)(
      onFx(
        window.shell.exists(shell =>
          shell.current.windows.headOption.flatMap(window => shell.current.session(window.id)).contains(SessionId("replacement"))
        )
      )
    )
    assertTrue(replacementMounted, "the replacement session is mounted")
    assertEquals(
      Some(SessionId("replacement")),
      onFx(window.shell.flatMap(shell => shell.current.windows.headOption.flatMap(window => shell.current.session(window.id))))
    )

  @Test
  def `a failed connection backs off without spinning`(): Unit =
    val delays = new ConcurrentLinkedQueue[FiniteDuration]()
    val attempted = new CountDownLatch(3)
    val window = createWindow(
      () => Sync.defer(throw new RuntimeException("no host")),
      sleepFor = delay =>
        Sync
          .defer {
            delays.add(delay)
            attempted.countDown()
            ()
          }
          .andThen(Async.sleep(kyo.Duration.fromScala(25.millis)))
    )
    assertTrue(attempted.await(10, TimeUnit.SECONDS), "the supervisor kept trying")
    assertTrue(
      await(5.seconds)(statusOf.exists(text => text.contains("Retrying") && text.contains("no host"))),
      s"status ${statusOf}"
    )
    val observed = delays.iterator().asScala.toVector
    assertEquals(ReconnectPolicy.default.initial, observed.head)
    assertEquals(Vector(250L, 500L, 1000L), observed.take(3).map(_.toMillis), s"backoff was $observed")
    window.close()

  @Test
  def `closing during a blocked connect leaves no shell and stops the client`(): Unit =
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val client = newClient(sessionInfo("slow"))
    val window = createWindow(() =>
      Sync.defer {
        entered.countDown()
        val _ = release.await(5, TimeUnit.SECONDS)
        client
      }
    )
    assertTrue(entered.await(5, TimeUnit.SECONDS), "the connect attempt started")
    onFx(window.close())
    release.countDown()

    assertTrue(await(5.seconds)(client.isStopped), "the late client was stopped")
    assertTrue(await(5.seconds)(window.shell.isEmpty), "no shell was shown after close")

  @Test
  def `a transient failed startup still creates the initial shell when a later attempt succeeds`(): Unit =
    val attempts = new AtomicInteger(0)
    val client = newClient()
    val connect: () => Client < (Async & Scope & Abort[TerminalError | Closed]) = () =>
      if attempts.getAndIncrement() == 0 then Sync.defer(throw new RuntimeException("no host"))
      else Sync.defer(client)
    val window = createWindow(connect)
    assertTrue(
      await(10.seconds)(onFx(window.shell.exists(_.current.windows.nonEmpty))),
      "the first successful show creates the initial shell"
    )

  private def createWindow(
      connect: () => Client < (Async & Scope & Abort[TerminalError | Closed]),
      sleepFor: FiniteDuration => Unit < Async = _ => Sync.defer(())
  ): AppWindow =
    val created = onFx {
      AppWindow(
        stage.get().getOrElse(fail("no stage")),
        "/tmp",
        connectHost = connect,
        reconnectPolicy = ReconnectPolicy(initial = 250.millis, maximum = 2.seconds),
        sleepFor = sleepFor
      )
    }
    app.set(Some(created))
    created.connect()
    created

  private def sessionInfo(id: String): SessionInfo =
    SessionInfo(SessionId(id), 0, TermSize(20, 4), 0, running = true, shellPid = 1, ended = None)

  private def newClient(sessions: SessionInfo*): Client =
    Client(new FakeService(sessions.toVector), new ViewSink)

  private def statusOf: Option[String] =
    onFx {
      val stage = this.stage.get().getOrElse(fail("no stage"))
      Option(stage.getScene)
        .flatMap(scene => Option(scene.getRoot.lookup(".status-view")))
        .flatMap(view => Option(view.lookup(".label")))
        .collect { case label: Label => label.getText }
    }

  private def await(timeout: FiniteDuration)(condition: => Boolean): Boolean =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && java.lang.System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition
    satisfied

  private def onFx[A](action: => A): A =
    if Platform.isFxApplicationThread then action
    else
      val result = AtomicReference[Either[Throwable, A]]()
      val latch = CountDownLatch(1)
      Platform.runLater { () =>
        try result.set(Right(action))
        catch case error: Throwable => result.set(Left(error))
        latch.countDown()
      }
      if !latch.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("the FX thread did not answer")
      result.get() match
        case Right(value) => value
        case Left(error)  => throw error

  private final class FakeService(private var sessions: Vector[SessionInfo]) extends TerminalService:
    def hello(token: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def status(using Frame): HostStatus < (Async & Abort[TerminalError | Closed]) = Sync.defer(HostStatus(0, sessions.size, 0))
    def identity(using Frame): HostIdentity < (Async & Abort[TerminalError | Closed]) = Sync.defer(HostIdentity(0, "test"))
    def stop(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def list(using Frame): Seq[SessionInfo] < (Async & Abort[TerminalError | Closed]) = Sync.defer(sessions)
    def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[TerminalError | Closed]) =
      val info = SessionInfo(SessionId(s"created-${sessions.size + 1}"), sessions.size, size, 0, running = true, shellPid = 1, ended = None)
      sessions = sessions :+ info
      Sync.defer(info)
    def attach(id: SessionId)(using Frame): Snapshot < (Async & Abort[TerminalError | Closed]) =
      sessions.find(_.id == id) match
        case Some(info) => Sync.defer(snapshot(info.id))
        case None       => Abort.fail(UnknownSession(id))
    def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[TerminalError | Closed]) =
      Sync.defer(Vector.empty[Row])
    def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def mouse(id: SessionId, input: MouseInput)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def key(id: SessionId, input: KeyInput)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def focus(id: SessionId, focused: Boolean)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def close(id: SessionId)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())
    def ack(id: SessionId, seq: Long)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) = Sync.defer(())

  private def snapshot(id: SessionId): Snapshot =
    Snapshot(
      id = id,
      size = TermSize(20, 4),
      rows = Vector.fill(4)(Row(Vector.empty)),
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = 0,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )
