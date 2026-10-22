package corvi.renderer

import corvi.contract.*
import corvi.protocol.TerminalService
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kyo.*
import kyo.Frame

/** The daemon-backed window: connects to the corvi host (starting it if nothing answers), lists the sessions it already holds, attaches the
  * first one, and keeps the rest of them waiting for the tab strip that comes later.
  *
  * Closing this window does **not** stop the host: the shells belong to the daemon, so reopening the window reattaches to the same shells
  * with the same screens. `host.stop` (or killing the daemon) is what ends them.
  *
  * ./mill -i rendererApp.runMain corvi.renderer.DaemonApp
  */
object DaemonApp extends scalafx.application.JFXApp3:

  private val cols = 100
  private val rows = 28

  /** The client's scope runs on a thread of its own; the FX thread never enters an effect. */
  private val sink = ViewSink()
  private val attached = AtomicReference[Option[(TerminalService, SessionId)]](None)
  private val ready = CountDownLatch(1)
  private val closing = CountDownLatch(1)

  locally:
    val thread = Thread.ofPlatform().name("corvi-daemon-client").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity) {
        Scope.run {
          for
            service <- HostConnection.ensure(sink)
            sessions <- service.list
            _ <- Sync.defer(
              java.lang.System.err.println(
                s"corvi renderer: the host holds ${sessions.size} sessions: ${sessions.map(info => s"${info.id.value} pid ${info.shellPid}").mkString(", ")}"
              )
            )
            session <- sessions.headOption match
              case Some(existing) => Sync.defer(existing)
              case None           => service.create(java.lang.System.getProperty("user.dir"), TermSize(cols, rows))
            _ <- Sync.defer {
              attached.set(Some((service, session.id)))
              ready.countDown()
            }
            _ <- Sync.defer(closing.await())
          yield ()
        }
      } match
        case Result.Success(_)     => ()
        case Result.Failure(error) => java.lang.System.err.println(s"corvi renderer: the client stopped: $error")
        case Result.Panic(error)   => java.lang.System.err.println(s"corvi renderer: the client panicked: $error")
    }

  override def start(): Unit =
    if !ready.await(90, TimeUnit.SECONDS) then
      java.lang.System.err.println("corvi renderer: the host did not answer")
      Platform.exit()
    else
      attached.get() match
        case Some((service, session)) => show(service, session)
        case None                     => Platform.exit()

  private def show(service: TerminalService, session: SessionId): Unit =
    val metrics = TerminalMetrics.monospaced(13)
    val view = TerminalView(service, session, metrics)
    sink.plug(view)
    view.setPrefSize(metrics.layout.width(cols), metrics.layout.height(rows))
    val scene = Scene(view, metrics.layout.width(cols) + 16, metrics.layout.height(rows))
    val window = Stage()
    window.setTitle(s"Corvi — ${session.value}")
    window.setScene(scene)
    window.setOnCloseRequest(_ => {
      // The window goes; the host and its shells stay.
      view.dispose()
      closing.countDown()
      Platform.exit()
    })
    window.show()
    view.requestFocus()
    java.lang.System.err.println(s"corvi renderer: attached to ${session.value}")
