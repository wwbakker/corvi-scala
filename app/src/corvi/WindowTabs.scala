package corvi

import javafx.scene.Node as JfxNode
import javafx.scene.input.MouseButton as JfxMouseButton
import scalafx.geometry.{Insets, Pos, Side}
import scalafx.scene.Node
import scalafx.scene.control.{ContextMenu, Label, MenuItem}
import scalafx.scene.layout.{FlowPane, HBox, Region}

/** The window strip: `Overview | <windows…> | new`, with the two ends fixed and never reorderable. It draws a `ShellState`, reports the
  * gestures it recognises as `WindowIntent`s, and keeps no state of its own — the drag in flight lives in the `TabDragState` it is handed,
  * because a drag must not re-render the strip and the shell is the one that decides when a render may run.
  */
private[corvi] object WindowTabs:
  /** The style class a tab carries while a drag is carrying it. */
  private val dragging: String = "dragging"

  def strip(
      selection: Selection,
      windows: Seq[TerminalWindow],
      drag: TabDragState,
      intent: WindowIntent => Unit
  ): FlowPane =
    val windowTabs: Seq[(WindowId, HBox)] = windows.map(window => window.id -> windowTab(window, selection, drag))

    /** The window whose tab holds that node: the pointer is over a label or an icon as often as it is over the tab itself.
      */
    def windowAt(node: Option[JfxNode]): Option[WindowId] =
      node.flatMap(at => windowTabs.collectFirst { case (id, tab) if isInside(at, tab.delegate) => id })

    def isInside(node: JfxNode, ancestor: JfxNode): Boolean =
      if node == null then false else (node eq ancestor) || isInside(node.getParent, ancestor)

    def tabFor(id: WindowId): Option[HBox] = windowTabs.collectFirst { case (each, tab) if each == id => tab }

    /** Drags are wired on the JavaFX nodes: a pick result and the button of a press are JavaFX types, and ScalaFX's wrappers would only be
      * in the way.
      */
    def attachDrag(tab: HBox, id: WindowId): Unit =
      val node = tab.delegate
      node.setOnMousePressed(event => if event.getButton == JfxMouseButton.PRIMARY then drag.update(TabDrag.Pressed(id)))
      node.setOnMouseDragged(event =>
        drag.drag match
          // Carrying starts when the pointer reaches another tab. That is what matters for the drop — the tab
          // under the pointer — and it means a click that wobbles never becomes a drag.
          case TabDrag.Pressed(from) if windowAt(Option(event.getPickResult.getIntersectedNode)).exists(_ != from) =>
            drag.update(TabDrag.Carrying(from))
            tabFor(from).foreach(carrying => { val _ = carrying.styleClass += dragging })
          case _ => ()
      )
      node.setOnMouseReleased(event =>
        drag.drag match
          case TabDrag.Carrying(from) =>
            tabFor(from).foreach(carrying => { val _ = carrying.styleClass -= dragging })
            windowAt(Option(event.getPickResult.getIntersectedNode)).filter(_ != from) match
              case Some(to) =>
                drag.update(TabDrag.Dropped(from))
                intent(WindowIntent.MoveWindow(from, to))
              case None => drag.update(TabDrag.Idle)
          case _ => ()
      )
      tab.onMouseClicked = _ =>
        drag.drag match
          case TabDrag.Dropped(_) => drag.update(TabDrag.Idle) // the click a drag leaves behind
          case _                  => intent(WindowIntent.SelectWindow(id))

    windowTabs.foreach { case (id, tab) =>
      closeMenu(tab, id, intent)
      attachDrag(tab, id)
    }

    new FlowPane:
      val _ = styleClass ++= Seq("window-tabs")
      hgap = 0
      vgap = Theme.tabRowSpacing
      padding = Insets(0, Theme.tabStripSidePadding, 0, Theme.tabStripSidePadding)
      // The row, plus the hairline the border adds under it.
      minHeight = Theme.tabRowHeight + Theme.stripHairline
      prefHeight = Theme.tabRowHeight + Theme.stripHairline
      children = overviewTab(selection, intent) +: (windowTabs.map(_._2) :+ newTab(intent))

  /** The fixed left end, and the way back to the placeholder: a label and nothing else — upstream gives the Overview tab no icon.
    */
  private def overviewTab(selection: Selection, intent: WindowIntent => Unit): HBox =
    val tab = tabNode(
      label = "Overview",
      icon = None,
      bell = false,
      selected = selection == Selection.Overview,
      ended = false,
      extra = Seq("overview")
    )
    tab.onMouseClicked = _ => intent(WindowIntent.SelectOverview)
    tab

  private def windowTab(window: TerminalWindow, selection: Selection, drag: TabDragState): HBox =
    val tab = tabNode(
      label = window.label,
      icon = Some(Icons.terminal(iconClasses(window.state)*)),
      bell = window.activity == TerminalWindow.Activity.Unseen,
      selected = selection == Selection.Window(window.id),
      ended = window.state == TerminalWindow.State.Ended,
      extra = Seq.empty
    )
    // A render that arrived mid-drag was deferred, so the fade is re-applied here when one runs between
    // gestures rather than being lost with the nodes.
    if drag.drag == TabDrag.Carrying(window.id) then { val _ = tab.styleClass += dragging }
    tab

  /** The fixed right end: the terminal glyph first, then the label. */
  private def newTab(intent: WindowIntent => Unit): HBox =
    val tab =
      tabNode(label = "new", icon = Some(Icons.terminal("tab-icon")), bell = false, selected = false, ended = false, extra = Seq("new"))
    tab.onMouseClicked = _ => intent(WindowIntent.OpenWindow)
    tab

  /** Right-clicking a window tab offers to close it. */
  private def closeMenu(tab: HBox, window: WindowId, intent: WindowIntent => Unit): Unit =
    val close = new MenuItem("Close tab"):
      onAction = _ => intent(WindowIntent.CloseWindow(window))
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
