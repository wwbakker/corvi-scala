package corvi.ui

import corvi.contract.{Ended, SessionInfo, TerminalView, TerminalViewFactory}
import javafx.scene.Node as JfxNode
import javafx.scene.Scene
import javafx.scene.input.KeyEvent
import scalafx.beans.property.ObjectProperty
import scalafx.scene.layout.{BorderPane, Priority, StackPane, VBox}

/** The window's content: the sidebar, the tab strip, and every terminal mounted with one of them on screen.
  *
  * The state is one immutable `ShellState`; every gesture becomes a `WindowIntent`, the shell applies it, and then paints whatever the new
  * state says. Views are created through `TerminalViewFactory` and kept, so switching tabs is a visibility change and the keyboard moving,
  * not a rebuild — which is also what makes the tabs feel instant.
  *
  * Bells come from the view's activity signal and only for a tab that is not selected; an ended shell comes from the view's ended signal.
  * The shell holds no Kyo effects: the factory owns the client runtime, and the two host calls a tab makes — create and close — return at
  * once and report back on this thread, so a new tab appears when the host has one and not before.
  *
  * The holders are properties rather than `var`s and are FX-thread-confined: JavaFX is single threaded, and every path in — a click, a
  * chord, the `Platform.runLater` a view marshals — arrives on that thread.
  */
private[corvi] final class ShellView(factory: TerminalViewFactory[JfxNode]) extends BorderPane:

  /** The views the shell has mounted, and the session each was opened from: the session half of the state, keyed the same way. */
  private final case class Mounted(views: Map[WindowId, TerminalView[JfxNode]], infos: Map[WindowId, SessionInfo])

  private val state: ObjectProperty[ShellState] = ObjectProperty(ShellState.initial)
  private val mounted: ObjectProperty[Mounted] = ObjectProperty(Mounted(Map.empty, Map.empty))
  private val chords: ObjectProperty[Chords.State] = ObjectProperty(Chords.initial)
  private val mac: Boolean = Chords.isMacPlatform

  private val workspace: VBox = new VBox:
    val _ = styleClass ++= Seq("workspace")

  private val overview: VBox = Overview()

  private val terminalArea: StackPane = new StackPane:
    val _ = styleClass ++= Seq("terminal-area")

  /** The drag in flight: the strip's handlers write it, and a render that would update their nodes waits for the gesture to end. */
  private val drag: TabDragState = TabDragState(() => flushRender())

  private val strip: TabStrip = TabStrip(drag, handle)

  locally:
    left = Sidebar()
    VBox.setVgrow(terminalArea, Priority.Always)
    workspace.children = Seq(strip, terminalArea)
    val _ = terminalArea.children += overview
    center = workspace
    paint()

  def current: ShellState = state.value

  /** A session the host already had: a reattach after a UI restart, or the first window of a fresh UI. */
  def open(info: SessionInfo): Unit =
    val label = s"${info.id.value}"
    val (next, id) = current.opened(label, info.id)
    state.value = next
    val view = factory.open(
      info,
      () => activity(id),
      ended => acceptedEnded(id, ended)
    )
    val node = view.node
    node.setVisible(false)
    val _ = terminalArea.children += node
    val mounting = mounted.value
    mounted.value = Mounted(mounting.views.updated(id, view), mounting.infos.updated(id, info))
    paint()

  /** A new terminal, from the strip's `new` tab or a chord: the host starts the shell, and the tab appears when it answers. */
  def newSession(): Unit = newWindow()

  /** Closes a window by id, which is what the tab's context menu asks for and what a probe drives. */
  def closeById(id: WindowId): Unit = closeWindow(id)

  def windowsInOrder: Vector[WindowId] = current.windows.map(_.id)

  /** The whole window is going: release every view, but leave the host and its shells alone. */
  def dispose(): Unit =
    mounted.value.views.values.foreach(_.dispose())
    mounted.value = Mounted(Map.empty, Map.empty)

  /** The chords are a scene filter, above the terminal: the machine decides what the shell does and whether the key goes on to the view. */
  def installChords(scene: Scene): Unit =
    val _ = scene.addEventFilter(
      KeyEvent.KEY_PRESSED,
      (event: KeyEvent) => {
        val key = Chords.Key.from(event, mac)
        val (next, outcome) = Chords.press(chords.value, key, mac, java.lang.System.nanoTime())
        chords.value = next
        outcome match
          case Chords.Outcome.Passed         => ()
          case Chords.Outcome.PassedShortcut => ()
          case Chords.Outcome.PrefixArmed    => event.consume()
          case Chords.Outcome.Open           =>
            event.consume()
            newWindow()
          case Chords.Outcome.CloseSelected =>
            event.consume()
            selectedWindow.foreach(closeWindow)
          case Chords.Outcome.Select(index) =>
            event.consume()
            current.windows.lift(index).foreach(window => select(Selection.Window(window.id)))
      }
    )
    val _ = scene.addEventFilter(
      KeyEvent.KEY_TYPED,
      (event: KeyEvent) => {
        val (next, swallowed) = Chords.typed(chords.value, java.lang.System.nanoTime())
        chords.value = next
        if swallowed then event.consume()
      }
    )

  // --- gestures ----------------------------------------------------------------------------------------------------------------------

  private def selectedWindow: Option[WindowId] = current.selection match
    case Selection.Window(id) => Some(id)
    case Selection.Overview   => None

  private def select(selection: Selection): Unit = applied(current.selected(selection))

  /** The host is asked for the grid the terminal area has now, so its first frame is already the shape of the window. Asked before the
    * first layout the area has no size, and the factory's default stands in.
    */
  private def newWindow(): Unit =
    factory.create(
      factory.gridFor(terminalArea.getWidth, terminalArea.getHeight),
      {
        case Some(info) => open(info)
        case None       => java.lang.System.err.println("corvi ui: the host would not start a session")
      }
    )

  private def closeWindow(id: WindowId): Unit =
    val mounting = mounted.value
    mounting.infos.get(id).foreach(factory.close)
    mounting.views.get(id).foreach(_.dispose())
    mounting.views.get(id).foreach(view => { val _ = terminalArea.children -= view.node })
    mounted.value = Mounted(mounting.views - id, mounting.infos - id)
    state.value = current.closed(id)
    paint()

  private def activity(id: WindowId): Unit = applied(current.activity(id))

  /** The shell exited: the tab says so and how, and stops asking for attention. */
  private def acceptedEnded(id: WindowId, ended: Ended): Unit = applied(current.ended(id, ended))

  /** A transition re-renders only when it changed the state. Output in the window you are looking at is an `activity` that changes nothing,
    * and repainting for it would be work with nothing to show.
    */
  private def applied(next: ShellState): Unit =
    if next != current then
      state.value = next
      render()

  private[corvi] def handle(intent: WindowIntent): Unit =
    intent match
      case WindowIntent.SelectOverview       => select(Selection.Overview)
      case WindowIntent.SelectWindow(id)     => select(Selection.Window(id))
      case WindowIntent.CloseWindow(id)      => closeWindow(id)
      case WindowIntent.OpenWindow           => newWindow()
      case WindowIntent.MoveWindow(from, to) => applied(current.moved(from, to))

  // --- rendering ---------------------------------------------------------------------------------------------------------------------

  /** A render now would update the nodes a drag is running on, so the render waits for the gesture to finish. */
  private def render(): Unit =
    drag.drag match
      case gesture if gesture.inFlight => drag.waited()
      case _                           =>
        drag.rendered()
        paint()

  /** The gesture ended: the render that had to wait runs, with the move it was waiting for already applied. */
  private def flushRender(): Unit =
    if drag.renderWaiting then
      drag.rendered()
      paint()

  /** The strip is drawn into its existing tabs, and the panes are only shown or hidden — they stay mounted, so their shells keep running.
    */
  private def paint(): Unit =
    strip.show(current.selection, current.windows)
    val selection = current.selection
    val views = mounted.value.views
    selection match
      case Selection.Overview =>
        overview.setVisible(true)
        views.values.foreach(view => view.node.setVisible(false))
      case Selection.Window(id) =>
        overview.setVisible(false)
        views.foreach { case (windowId, view) => view.node.setVisible(windowId == id) }
        views.get(id).foreach(_.focus())
