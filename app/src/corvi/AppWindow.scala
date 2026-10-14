package corvi

import javafx.application.Platform
import javafx.scene.input.KeyEvent
import scalafx.Includes.*
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.scene.Scene

/** Corvi's window, built in one place so that `Main` and the snapshot harness in `SnapshotMain` always show the same thing: chunk 3 and
  * later add the terminals and shortcuts here and the snapshot picks them up for free.
  */
private[corvi] object AppWindow:
  private val windowTitle: String = "Corvi"

  /** The scene the app shows: the shell, with Corvi's stylesheet attached. Building the scene applies an implicit conversion from
    * `scalafx.Includes`, which is what loads that object at runtime — see the `javafx.media` note in `build.mill`.
    */
  def scene(shell: CorviShell): Scene =
    val stylesheet: String = getClass.getResource("/corvi.css").toExternalForm
    val scene = new Scene(shell.root):
      val _ = stylesheets += stylesheet
    // The strip's chords are heard on the way down, before any node: a key the strip uses is consumed
    // here, so it never reaches a terminal.
    val _ = scene.delegate.addEventFilter(
      KeyEvent.KEY_PRESSED,
      (event: KeyEvent) => if shell.chord(event) then event.consume()
    )
    // The typed companion of a consumed press arrives anyway — the platform sends both for one
    // keystroke — so it needs swallowing separately, or `ctrl-b c` types a `c` into the shell.
    val _ = scene.delegate.addEventFilter(
      KeyEvent.KEY_TYPED,
      (event: KeyEvent) => if shell.typedKey() then event.consume()
    )
    scene

  /** The primary stage around a scene; `onClose` runs when the window is asked to close. */
  def stage(window: Scene, onClose: () => Unit): PrimaryStage =
    new PrimaryStage:
      title = AppWindow.windowTitle
      width = Theme.stageWidth
      height = Theme.stageHeight
      minWidth = Theme.stageMinWidth
      minHeight = Theme.stageMinHeight
      this.scene = window
      onCloseRequest = () => onClose()

  /** Quitting: every terminal stops on the FX thread — its shell killed and its emulator stopped — and only then does the toolkit go. The
    * order is the point: `Platform.exit()` hides the window and ends the toolkit, so anything still being torn down after it may never
    * finish, which used to leave a login shell and a non-daemon emulator thread behind.
    */
  def quit(shell: CorviShell): Unit =
    shell.shutdown()
    Platform.exit()
