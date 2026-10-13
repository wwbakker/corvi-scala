package corvi

import corvi.terminal.TerminalSession
import java.nio.file.{Path, Paths}
import scalafx.scene.layout.{BorderPane, FlowPane, Priority, StackPane, VBox}

/** The window: the sidebar, the tab strip, and the well the terminals live in. Chunk 3 puts one real terminal in the well; chunk 4 makes
  * sessions something a tab owns.
  */
private[corvi] final class CorviShell:
  /** Where a terminal opens: the app's own working directory, which is the change. */
  private val cwd: Path = Paths.get(sys.props("user.dir"))

  private val session: TerminalSession = TerminalSession.open(
    id = 0,
    label = "corvi",
    cwd = cwd,
    // Chunk 4 turns these into the tab's bell and its ended state; until then a session is on its
    // own with them.
    onOutput = _ => (),
    onExited = _ => ()
  )

  private val strip: FlowPane = WindowTabs.strip(overviewSelected = false, tabs = CorviShell.demoTabs)

  private val well: StackPane = new StackPane:
    val _ = styleClass ++= Seq("terminal-area")
    children = Seq(session.pane)

  val root: BorderPane = new BorderPane:
    left = Sidebar()
    center = new VBox:
      children = Seq(strip, well)
      VBox.setVgrow(well, Priority.Always)

  /** True once the shell has written something — its prompt. The snapshot harness waits for this so its PNG shows a terminal rather than an
    * empty well.
    */
  def terminalHasOutput: Boolean = session.screenText.exists(!_.isWhitespace)

  /** Ends the session's shell, synchronously: the emulator stops and the process dies before this returns, which is what the app's quit
    * handler needs before it exits the toolkit.
    */
  def shutdown(): Unit = session.close()

private[corvi] object CorviShell:
  /** The strip chunk 3 shows: the terminal's window is the current one, a second one is quiet with activity. Chunk 4 replaces this with the
    * session state.
    */
  private val demoTabs: Seq[WindowTabs.Tab] = Seq(
    WindowTabs.Tab("corvi", WindowTabs.State.Working, activity = false, selected = true),
    WindowTabs.Tab("shell", WindowTabs.State.Idle, activity = true, selected = false)
  )
