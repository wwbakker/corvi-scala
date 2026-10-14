package corvi

import javafx.scene.Node as JfxNode
import javafx.scene.input.MouseButton as JfxMouseButton
import scalafx.geometry.{Insets, Pos, Side}
import scalafx.scene.Node
import scalafx.scene.control.{ContextMenu, Label, MenuItem}
import scalafx.scene.layout.{FlowPane, HBox, Region}

/** The window strip: `Overview | <windows…> | new`, with the two ends fixed and never reorderable. It draws a `ShellState` and calls back
  * into the shell; the only state of its own is a drag that is in flight.
  */
private[corvi] object WindowTabs:
  /** A tab drag in flight, as the shell has to know it: rendering waits for a carry, because a strip rebuilt mid-drag would detach the very
    * nodes the gesture is grabbing.
    */
  enum Drag:
    case Idle
    case Carrying(from: Int)

  /** What the strip asks the shell to do. */
  final case class Actions(
      selectOverview: () => Unit,
      selectWindow: Int => Unit,
      closeWindow: Int => Unit,
      openWindow: () => Unit,
      moveWindow: (Int, Int) => Unit,
      dragging: Drag => Unit
  )

  /** The style class a tab carries while a drag is carrying it. */
  private val dragging: String = "dragging"

  def strip(selection: Selection, windows: Seq[TerminalWindow], actions: Actions): FlowPane =
    // The drag in flight: what was pressed, what it is carrying, and whether the click that follows a
    // drag is to be swallowed. Deliberately not in `ShellState`: a drag must not re-render the strip,
    // because that would rebuild the very nodes the gesture is running on. The cost is that a
    // re-render for some other reason (output arriving in a background window) drops the fade — the
    // drag itself still lands, since the gesture keeps the handlers it started with.
    var pressed: Option[Int] = None
    var carried: Option[Int] = None
    // Set only when a drag actually moved a window: the click the platform sends afterwards is not a
    // selection then. It is cleared at the start of every gesture, so a carry that lands back where it
    // started cannot swallow the next click.
    var suppressClick = false

    val windowTabs: Seq[(Int, HBox)] = windows.map(window => window.id -> windowTab(window, selection))

    /** The window whose tab holds that node: the pointer is over a label or an icon as often as it is over the tab itself.
      */
    def windowAt(node: Option[JfxNode]): Option[Int] =
      node.flatMap(at => windowTabs.collectFirst { case (id, tab) if isInside(at, tab.delegate) => id })

    def isInside(node: JfxNode, ancestor: JfxNode): Boolean =
      if node == null then false else (node eq ancestor) || isInside(node.getParent, ancestor)

    def tabFor(id: Int): Option[HBox] = windowTabs.collectFirst { case (each, tab) if each == id => tab }

    /** Drags are wired on the JavaFX nodes: a pick result and the button of a press are JavaFX types, and ScalaFX's wrappers would only be
      * in the way.
      */
    def attachDrag(tab: HBox, id: Int, actions: Actions): Unit =
      val node = tab.delegate
      node.setOnMousePressed(event =>
        suppressClick = false
        if event.getButton == JfxMouseButton.PRIMARY then
          pressed = Some(id)
          carried = None
      )
      node.setOnMouseDragged(event =>
        pressed match
          case Some(from) if carried.isEmpty && windowAt(Option(event.getPickResult.getIntersectedNode)).exists(_ != from) =>
            carried = Some(from)
            tabFor(from).foreach(carrying => { val _ = carrying.styleClass += dragging })
            actions.dragging(Drag.Carrying(from))
          case _ => ()
      )
      node.setOnMouseReleased(event =>
        carried.foreach { from =>
          tabFor(from).foreach(carrying => { val _ = carrying.styleClass -= dragging })
          windowAt(Option(event.getPickResult.getIntersectedNode)).filter(_ != from) match
            case Some(to) =>
              actions.moveWindow(from, to)
              suppressClick = true
            case None => ()
          // The render the move asked for has been waiting, and runs here.
          actions.dragging(Drag.Idle)
        }
        pressed = None
        carried = None
      )
      tab.onMouseClicked = _ => if suppressClick then suppressClick = false else actions.selectWindow(id)

    windowTabs.foreach { case (id, tab) =>
      closeMenu(tab, id, actions)
      attachDrag(tab, id, actions)
    }

    new FlowPane:
      val _ = styleClass ++= Seq("window-tabs")
      hgap = 0
      vgap = Theme.tabRowSpacing
      padding = Insets(0, Theme.tabStripSidePadding, 0, Theme.tabStripSidePadding)
      // The row, plus the hairline the border adds under it.
      minHeight = Theme.tabRowHeight + Theme.stripHairline
      prefHeight = Theme.tabRowHeight + Theme.stripHairline
      children = overviewTab(selection, actions) +: (windowTabs.map(_._2) :+ newTab(actions))

  /** The fixed left end, and the way back to the placeholder: a label and nothing else — upstream gives the Overview tab no icon.
    */
  private def overviewTab(selection: Selection, actions: Actions): HBox =
    val tab = tabNode(
      label = "Overview",
      icon = None,
      bell = false,
      selected = selection == Selection.Overview,
      ended = false,
      extra = Seq("overview")
    )
    tab.onMouseClicked = _ => actions.selectOverview()
    tab

  private def windowTab(window: TerminalWindow, selection: Selection): HBox =
    tabNode(
      label = window.label,
      icon = Some(Icons.terminal(iconClasses(window.state)*)),
      bell = window.activity == TerminalWindow.Activity.Unseen,
      selected = selection == Selection.Window(window.id),
      ended = window.state == TerminalWindow.State.Ended,
      extra = Seq.empty
    )

  /** The fixed right end: the terminal glyph first, then the label. */
  private def newTab(actions: Actions): HBox =
    val tab =
      tabNode(label = "new", icon = Some(Icons.terminal("tab-icon")), bell = false, selected = false, ended = false, extra = Seq("new"))
    tab.onMouseClicked = _ => actions.openWindow()
    tab

  /** Right-clicking a window tab offers to close it. */
  private def closeMenu(tab: HBox, window: Int, actions: Actions): Unit =
    val close = new MenuItem("Close tab"):
      onAction = _ => actions.closeWindow(window)
    val menu = new ContextMenu(close)
    tab.onContextMenuRequested = event =>
      menu.show(tab, Side.Bottom, 0, 0)
      event.consume()

  private def tabNode(label: String, icon: Option[Node], bell: Boolean, selected: Boolean, ended: Boolean, extra: Seq[String]): HBox =
    val bellWidth = if bell then Theme.tabBellWidth else 0
    val text = new Label(label):
      // The budget takes the bell into account, so a bell tab cannot grow past the cap.
      maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth - bellWidth
    new HBox:
      val _ = styleClass ++= (
        Seq("window-tab")
          ++ (if selected then Seq("current") else Seq.empty)
          ++ (if ended then Seq("ended") else Seq.empty)
          ++ extra
      )
      minHeight = Theme.tabRowHeight
      prefHeight = Theme.tabRowHeight
      maxWidth = Theme.tabMaxWidth
      alignment = Pos.CenterLeft
      spacing = Theme.tabContentSpacing
      focusTraversable = false
      children = icon.toSeq ++ Seq(text) ++ (if bell then Seq(bellDot()) else Seq.empty)

  /** The `--warn` dot for output that arrived in a tab you were not looking at. */
  private def bellDot(): Region =
    new Region:
      val _ = styleClass ++= Seq("bell")

  /** The tab's own colour is the icon's when the window has no state to report (the `new` tab); a window adds its own, and that keeps its
    * colour even while the tab is current.
    */
  private def iconClasses(state: TerminalWindow.State): Seq[String] =
    state match
      case TerminalWindow.State.Idle  => Seq("tab-icon", "idle")
      case TerminalWindow.State.Ended => Seq("tab-icon", "ended")
