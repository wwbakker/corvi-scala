package corvi

import corvi.terminal.TerminalSession
import java.nio.file.{Path, Paths}
import javafx.application.Platform
import javafx.scene.input.KeyEvent
import scalafx.beans.property.ObjectProperty
import scalafx.geometry.Pos
import scalafx.scene.control.Label
import scalafx.scene.layout.{BorderPane, Priority, StackPane, VBox}

/** The window: the sidebar, the tab strip, and the well the terminals live in. It owns the sessions and which one is on screen; the strip
  * and the well are a render of `ShellState`, and every change comes from one of the transitions on it.
  *
  * The state is FX-thread-only — JavaFX is single threaded, and every path in (a click, a chord, the `Platform.runLater` a session
  * marshals) arrives on that thread — so the single mutable reference below needs no synchronisation. It is a JavaFX property rather than a
  * `var`, confined to this class, which is what the skill asks for where a foreign toolkit owns the thread.
  */
private[corvi] final class CorviShell:
  /** Where a terminal opens: the app's own working directory, which is the change. */
  private val cwd: Path = Paths.get(sys.props("user.dir"))

  private val state: ObjectProperty[ShellState] = ObjectProperty(ShellState.initial)

  private val actions: WindowTabs.Actions = WindowTabs.Actions(
    selectOverview = () => select(Selection.Overview),
    selectWindow = id => select(Selection.Window(id)),
    closeWindow = closeTerminal,
    openWindow = () => openTerminal(),
    moveWindow = (from, to) => update(_.moved(from, to)),
    dragging = drag => noteDrag(drag)
  )

  /** The chord state: the tmux-style prefix is all there is to remember. It lives in its own holder because a key press must not re-render
    * the strip.
    */
  private val chords: ObjectProperty[Chords.State] = ObjectProperty(Chords.initial)

  /** The strip's drag, and whether a render is waiting for it. FX-thread-only, and apart from `ShellState` because a drag must not
    * re-render: rebuilding the strip would detach the very nodes the gesture is grabbing, and the release would land in nothing.
    */
  private val dragging: ObjectProperty[CorviShell.DragRender] = ObjectProperty(CorviShell.DragRender.idle)

  /** The strip is rebuilt from the state, so it lives in a one-child host that the render swaps. */
  private val stripHost: VBox = new VBox

  private val overview: VBox = CorviShell.overview()

  /** Every session's pane stays mounted here: selecting only shows and hides them, so the shells behind them keep running, and keep being
    * told about resizes.
    */
  private val well: StackPane = new StackPane:
    val _ = styleClass ++= Seq("terminal-area")
    children = Seq(overview)

  val root: BorderPane = new BorderPane:
    left = Sidebar()
    center = new VBox:
      children = Seq(stripHost, well)
      VBox.setVgrow(well, Priority.Always)

  // Every state change re-renders: the strip wholesale, the panes in their visibility.
  private val _ = state.onChange((_, _, current) => render(current))

  // The window opens with one terminal rather than empty.
  openTerminal()

  /** Opens a login shell in a new window and selects it. */
  def openTerminal(): Unit =
    val id = state.value.nextId
    val label = CorviShell.windowLabel(id)
    val session = TerminalSession.open(
      id = id,
      label = label,
      cwd = cwd,
      // The read happens on the emulator's thread, so the state change is marshalled to the FX thread.
      onOutput = _ => noteOutput(id),
      onExited = _ => update(_.ended(id))
    )
    val _ = well.children += session.pane
    update(_.opened(label, session))
    // The new window is the one on screen, so the keyboard belongs in it now — the same thing
    // `select` does when a tab is clicked.
    session.requestFocus()

  /** Selecting a window shows it, takes its bell down and puts the keyboard in it; selecting the overview shows the placeholder instead.
    */
  def select(selection: Selection): Unit =
    update(_.selected(selection))
    selection match
      case Selection.Window(id) => state.value.session(id).foreach(_.requestFocus())
      case Selection.Overview   => ()

  /** Closes a window and the shell it was running. */
  def closeTerminal(id: Int): Unit =
    val closing = state.value.session(id)
    update(_.closed(id))
    closing.foreach { session =>
      val _ = well.children -= session.pane
      session.close()
    }

  /** Takes a key press for the strip's chords, answering whether it is consumed. The scene's filter (see `AppWindow`) runs this before
    * anything else, so the keys the strip uses never reach a shell.
    */
  /** Whether a `KEY_TYPED` belongs to a key the strip already took. Consuming a press does not stop the typed the platform sends with it,
    * so this is what keeps `ctrl-b c` from typing a `c` into the terminal it is leaving. The scene's other filter (`AppWindow`) runs this.
    */
  def typedKey(): Boolean =
    val (next, swallow) = Chords.typed(chords.value)
    chords.value = next
    swallow

  def chord(event: KeyEvent): Boolean =
    val (next, outcome) = Chords.press(chords.value, event, Chords.isMacPlatform, System.nanoTime())
    chords.value = next
    outcome match
      case Chords.Outcome.Passed        => false
      case Chords.Outcome.PrefixArmed   => true
      case Chords.Outcome.Open          => openTerminal(); true
      case Chords.Outcome.CloseSelected => closeSelected(); true
      case Chords.Outcome.Select(index) => selectNumber(index); true

  /** Ends every session — the app's quit runs this before the toolkit goes. */
  def shutdown(): Unit = state.value.sessions.values.foreach(_.close())

  /** The state, for the shell's own rendering and for the tab harness. FX-thread-only. */
  def shellState: ShellState = state.value

  /** True once the selected window's shell has written something — its prompt. The snapshot harness waits for this so its PNG shows a
    * terminal rather than an empty well. FX-thread-only.
    */
  def terminalHasOutput: Boolean = state.value.selectedSession.exists(_.screenText.exists(!_.isWhitespace))

  /** Closes the window on screen; the overview has nothing to close. */
  private def closeSelected(): Unit =
    state.value.selection match
      case Selection.Window(id) => closeTerminal(id)
      case Selection.Overview   => ()

  /** The `ctrl-b <n>` chord: windows count from zero, as upstream's tabs and tmux count them. A number with no window behind it is a
    * consumed no-op.
    */
  private def selectNumber(index: Int): Unit =
    state.value.windows.lift(index).foreach(window => select(Selection.Window(window.id)))

  /** The strip saying which drag it is running: nothing re-renders while one is carried, and the render that had to wait runs the moment it
    * ends — with the move it was waiting for already applied.
    */
  private def noteDrag(drag: WindowTabs.Drag): Unit =
    val wasCarrying = dragging.value.drag != WindowTabs.Drag.Idle
    if wasCarrying && drag == WindowTabs.Drag.Idle then
      val waiting = dragging.value.renderWaiting
      dragging.value = CorviShell.DragRender.idle
      if waiting then rebuild(state.value)
    else dragging.value = CorviShell.DragRender(drag, dragging.value.renderWaiting)

  /** Applies a pure transition. FX-thread-only, like everything that touches `state`. */
  private def update(transition: ShellState => ShellState): Unit = state.value = transition(state.value)

  /** The strip and the well follow the state: the strip is rebuilt (cheap at this scale), and the panes are only shown or hidden — they
    * stay mounted, so their shells keep running.
    */
  private def render(current: ShellState): Unit =
    if dragging.value.drag == WindowTabs.Drag.Idle then rebuild(current)
    else dragging.value = dragging.value.copy(renderWaiting = true)

  /** The strip and the well follow the state: the strip is rebuilt (cheap at this scale), and the panes are only shown or hidden — they
    * stay mounted, so their shells keep running.
    */
  private def rebuild(current: ShellState): Unit =
    stripHost.children = Seq(WindowTabs.strip(current.selection, current.windows, actions))
    overview.visible = current.selection == Selection.Overview
    current.windows.foreach { window =>
      current.session(window.id).foreach(_.pane.visible = current.isSelected(window.id))
    }

  /** Output arrived, on the emulator's thread: raise that window's bell on the FX thread. A toolkit on its way out is not a failure — the
    * session is being closed anyway.
    */
  private def noteOutput(id: Int): Unit =
    try Platform.runLater(() => update(_.activity(id)))
    catch case _: IllegalStateException => ()

private[corvi] object CorviShell:
  /** What the shell knows about a strip drag: the gesture, and whether a render had to wait for it. */
  final case class DragRender(drag: WindowTabs.Drag, renderWaiting: Boolean)

  object DragRender:
    val idle: DragRender = DragRender(WindowTabs.Drag.Idle, renderWaiting = false)

  /** What a fresh window is called: Corvi's tabs carry the window's own name, and a plain shell has none to carry.
    */
  private def windowLabel(id: Int): String = s"terminal ${id + 1}"

  /** The Overview tab's content — the way back out of a terminal. */
  private def overview(): VBox =
    val title = new Label("Overview"):
      val _ = styleClass ++= Seq("overview-title")
    val hint = new Label("Every terminal lives in a tab above.")
    new VBox:
      val _ = styleClass ++= Seq("overview-placeholder")
      alignment = Pos.Center
      spacing = 6
      children = Seq(title, hint)
