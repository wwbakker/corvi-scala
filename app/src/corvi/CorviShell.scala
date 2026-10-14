package corvi

import corvi.terminal.{SessionForker, TerminalSession}
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
  * marshals) arrives on that thread — so the holders below need no synchronisation. They are JavaFX properties and one atomic-backed holder
  * rather than `var`s, confined to this class, which is what the skill asks for where a foreign toolkit owns the thread.
  */
private[corvi] final class CorviShell(forker: SessionForker):
  /** Where a terminal opens: the app's own working directory, which is the change. */
  private val cwd: Path = Paths.get(sys.props("user.dir"))

  private val state: ObjectProperty[ShellState] = ObjectProperty(ShellState.initial)

  /** The chord state: the prefix, and the typed companion still to swallow. It lives in its own holder because a key press must not
    * re-render the strip.
    */
  private val chords: ObjectProperty[Chords.State] = ObjectProperty(Chords.initial)

  /** The drag in flight, and the render that may be waiting for it. */
  private val tabDrag: TabDragState = new TabDragState(() => flushRender())

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

  /** Opens a login shell in a new window and selects it. */
  def openTerminal(): Unit =
    val id = state.value.nextId
    val label = CorviShell.windowLabel(id)
    val session = TerminalSession.create(
      id = id.value,
      label = label,
      cwd = cwd,
      // The read happens on the emulator's thread, so the state change is marshalled to the FX thread.
      onOutput = _ => noteOutput(id),
      onExited = _ => update(_.ended(id))
    )
    val _ = well.children += session.pane
    state.value = state.value.opened(label, session)
    // The new window is the one on screen, so the keyboard belongs in it now — the same thing `select` does
    // when a tab is clicked.
    session.requestFocus()
    // The session is created here, on the FX thread, and watched inside the app's scope: the FX thread cannot
    // fork, so this hands it over to the scope's loop.
    forker.watch(session)

  /** Selecting a window shows it, takes its bell down and puts the keyboard in it; selecting the overview shows the placeholder instead.
    */
  def select(selection: Selection): Unit =
    update(_.selected(selection))
    selection match
      case Selection.Window(id) => state.value.session(id).foreach(_.requestFocus())
      case Selection.Overview   => ()

  /** Closes a window and the shell it was running. */
  def closeTerminal(id: WindowId): Unit =
    val closing = state.value.session(id)
    update(_.closed(id))
    closing.foreach { session =>
      val _ = well.children -= session.pane
      session.close()
    }

  /** Ends every session — the app's quit runs this before the toolkit goes. */
  def shutdown(): Unit = state.value.sessions.values.foreach(_.close())

  /** The state, for the shell's own rendering and for the harnesses. FX-thread-only. */
  def shellState: ShellState = state.value

  /** The selected terminal's screen, as text. FX-thread-only, like the screen it reads. The snapshot harness waits for this to stop
    * changing before it captures, so its PNG does not depend on where the shell's resize landed.
    */
  def selectedScreen: String = state.value.selectedSession.map(_.screenText).getOrElse("")

  /** True once the selected window's shell has written something — its prompt. FX-thread-only.
    */
  def terminalHasOutput: Boolean = selectedScreen.exists(!_.isWhitespace)

  /** Takes a key press for the strip's chords, answering whether it is consumed. The scene's filter (see `AppWindow`) runs this before
    * anything else, so the keys the strip uses never reach a shell.
    */
  def chord(event: KeyEvent): Boolean =
    val (next, outcome) = Chords.press(chords.value, Chords.Key.from(event, Chords.isMacPlatform), Chords.isMacPlatform, System.nanoTime())
    chords.value = next
    outcome match
      case Chords.Outcome.Passed        => false
      case Chords.Outcome.PrefixArmed   => true
      case Chords.Outcome.Open          => openTerminal(); true
      case Chords.Outcome.CloseSelected => closeSelected(); true
      case Chords.Outcome.Select(index) => selectNumber(index); true

  /** Whether a `KEY_TYPED` belongs to a key the strip already took. Consuming a press does not stop the typed the platform sends with it,
    * so this is what keeps `ctrl-b c` from typing a `c` into the terminal it is leaving. The scene's other filter (`AppWindow`) runs this.
    */
  def typedKey(): Boolean =
    val (next, swallow) = Chords.typed(chords.value)
    chords.value = next
    swallow

  /** What the strip's intents mean. One entry per case, so a new gesture is one case here as well as there. */
  private def intend(intent: WindowIntent): Unit =
    intent match
      case WindowIntent.SelectOverview       => select(Selection.Overview)
      case WindowIntent.SelectWindow(id)     => select(Selection.Window(id))
      case WindowIntent.CloseWindow(id)      => closeTerminal(id)
      case WindowIntent.OpenWindow           => openTerminal()
      case WindowIntent.MoveWindow(from, to) => update(_.moved(from, to))

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

  /** Applies a pure transition. FX-thread-only, like everything that touches `state`. */
  private def update(transition: ShellState => ShellState): Unit = state.value = transition(state.value)

  /** Asks for a render: it happens now, or waits for a drag that is in flight, because a strip rebuilt mid-drag would detach the nodes the
    * gesture is grabbing.
    */
  private def render(current: ShellState): Unit =
    tabDrag.drag match
      case drag if drag.inFlight => tabDrag.waited()
      case _                     =>
        tabDrag.rendered()
        rebuild(current)

  /** The gesture ended: the render that had to wait runs, with the move it was waiting for already applied. */
  private def flushRender(): Unit =
    if tabDrag.renderWaiting then
      tabDrag.rendered()
      rebuild(state.value)

  /** The strip and the well follow the state: the strip is rebuilt (cheap at this scale), and the panes are only shown or hidden — they
    * stay mounted, so their shells keep running.
    */
  private def rebuild(current: ShellState): Unit =
    stripHost.children = Seq(WindowTabs.strip(current.selection, current.windows, tabDrag, intend))
    overview.visible = current.selection == Selection.Overview
    current.windows.foreach { window =>
      current.session(window.id).foreach(_.pane.visible = current.isSelected(window.id))
    }

  /** Output arrived, on the emulator's thread: raise that window's bell on the FX thread. A toolkit on its way out is not a failure — the
    * session is being closed anyway.
    */
  private def noteOutput(id: WindowId): Unit =
    try Platform.runLater(() => update(_.activity(id)))
    catch case _: IllegalStateException => ()

private[corvi] object CorviShell:
  /** What a fresh window is called: Corvi's tabs carry the window's own name, and a plain shell has none to carry.
    */
  private def windowLabel(id: WindowId): String = s"terminal ${id.value + 1}"

  /** Opens the window the app shows: one terminal, so it starts looking like Corvi rather than empty. The constructor stays plain; a window
    * that opens its own terminal is a decision, and it lives here.
    */
  def open(forker: SessionForker): CorviShell =
    val shell = new CorviShell(forker)
    shell.openTerminal()
    shell

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
