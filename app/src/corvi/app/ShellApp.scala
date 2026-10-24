package corvi.app

import corvi.renderer.TerminalMetrics
import java.util.logging.{LogRecord, Logger}
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

  /** Mill runs JavaFX from the classpath, so JavaFX prints its unnamed-module warning on every start. That is expected here; the app is
    * already a named module only in a packaged build. Drop this one message and leave every other JavaFX warning visible.
    */
  override def main(args: Array[String]): Unit =
    val logger = Logger.getLogger("javafx")
    val previous = logger.getFilter
    logger.setFilter((record: LogRecord) =>
      val expected = Option(record.getMessage).exists(_.startsWith("Unsupported JavaFX configuration"))
      !expected && (previous == null || previous.isLoggable(record))
    )
    super.main(args)

  override def start(): Unit =
    JFXApp3.AutoShow = false
    stage = new PrimaryStage
    AppWindow(stage, TerminalMetrics.monospaced(13), java.lang.System.getProperty("user.dir")).connect()
