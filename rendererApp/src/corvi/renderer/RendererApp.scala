package corvi.renderer

import corvi.host.HostSession
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kyo.Duration
import scala.concurrent.duration.*

/** The window harness: an in-memory host, a shell, and one terminal view.
  *
  * ./mill -i rendererApp.run
  */
object RendererApp extends scalafx.application.JFXApp3:

  private val cols = 100
  private val rows = 28

  /** A push tick every frame, so the window shows what the shell wrote rather than what it wrote a moment ago. */
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(16.millis)

  override def start(): Unit =
    val metrics = TerminalMetrics.monospaced(13)
    val running = Harness.start(cols, rows, pushInterval, HostSession.loginShell)
    val view = TerminalView(running.service, running.session, metrics)
    running.sink.plug(view)
    view.setPrefSize(metrics.layout.width(cols), metrics.layout.height(rows))

    val scene = Scene(view, metrics.layout.width(cols) + 16, metrics.layout.height(rows))
    val window = Stage()
    window.setTitle("Corvi — renderer")
    window.setScene(scene)
    window.setOnCloseRequest(_ => {
      // A closing tab releases its view before the host goes: the resize timer and the client's runtime would outlive it otherwise.
      view.dispose()
      running.stop()
      Platform.exit()
    })
    window.show()
    view.requestFocus()
