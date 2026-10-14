package corvi

import corvi.terminal.SessionForker
import javafx.application.Platform
import java.util.concurrent.atomic.AtomicReference
import scalafx.application.JFXApp3

/** The window application: JavaFX's side of the arrangement. It owns nothing concurrent — every long-lived piece of work belongs to the
  * scope `Main` opened — and it is handed the bridge to that scope before JavaFX starts, because a fork cannot be started from the FX
  * thread (see `SessionForker`).
  */
private[corvi] object CorviApp extends JFXApp3:
  /** The bridge, set once by `Main` on the process's main thread before JavaFX starts and read by `start` on the FX thread. One reference
    * for the one value that has to cross between the two worlds.
    */
  private val bridge: AtomicReference[SessionForker | Null] = new AtomicReference(null)

  /** Starts the application inside `Main`'s scope. Blocks until JavaFX exits, as `Application.launch` does. */
  def launch(forker: SessionForker, args: Array[String]): Unit =
    bridge.set(forker)
    main(args)

  override def start(): Unit =
    // Quitting is ours to sequence. With implicit exit on, JavaFX would take the toolkit down as soon as
    // the window hides — half way through stopping the terminals. `AppWindow.quit` does the teardown
    // first and exits after it.
    Platform.setImplicitExit(false)
    val shell = CorviShell.open(bridge.get.nn)
    stage = AppWindow.stage(AppWindow.scene(shell), () => AppWindow.quit(shell))
