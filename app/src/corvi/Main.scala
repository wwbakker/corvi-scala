package corvi

import javafx.application.Platform
import scalafx.application.JFXApp3

/** The application: Corvi's window, built by `AppWindow` so the snapshot harness renders the same one. Terminals, tab behaviour and
  * shortcuts land here as the following chunks add them.
  */
object Main extends JFXApp3:
  override def start(): Unit =
    // Quitting is ours to sequence. With implicit exit on, JavaFX would take the toolkit down as
    // soon as the window hides — half way through stopping the terminals. `AppWindow.quit` does the
    // teardown first and exits after it.
    Platform.setImplicitExit(false)
    val shell = new CorviShell
    stage = AppWindow.stage(AppWindow.scene(shell), () => AppWindow.quit(shell))
