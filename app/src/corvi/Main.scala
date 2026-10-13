package corvi

import scalafx.application.JFXApp3

/** The application: Corvi's window, built by `AppWindow` so the snapshot harness renders the same one. Terminals, tab behaviour and
  * shortcuts land here as the following chunks add them.
  */
object Main extends JFXApp3:
  override def start(): Unit =
    val shell = new CorviShell
    stage = AppWindow.stage(AppWindow.scene(shell), () => shell.shutdown())
