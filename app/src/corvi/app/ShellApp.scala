package corvi.app

import corvi.renderer.TerminalMetrics
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage

/** The `corvi-ui` process: JavaFX's primary stage is the one Corvi window, and it is on screen from the first frame — with a placeholder
  * scene until the daemon has answered, then the shell.
  *
  * `AutoShow` must be off: JavaFX would otherwise show the same primary stage itself before this app has put a scene in it, which is a
  * second, empty window (and the black screen of a window with nothing in it).
  *
  * ./mill --no-daemon app.run
  */
object ShellApp extends JFXApp3:

  override def start(): Unit =
    JFXApp3.AutoShow = false
    stage = new PrimaryStage
    AppWindow(stage, TerminalMetrics.monospaced(13), java.lang.System.getProperty("user.dir")).connect()
