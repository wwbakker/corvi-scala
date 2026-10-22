package corvi.ui

import javafx.scene.Node as JfxNode
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton as JfxMouseButton
import scala.jdk.CollectionConverters.*
import scalafx.geometry.{Insets, Pos, Side}
import scalafx.scene.Node
import scalafx.scene.control.{ContextMenu, Label, MenuItem}
import scalafx.scene.layout.{FlowPane, HBox, Region}

/** The window strip, as a live row: `Overview | <windows…> | new`, with the two ends fixed and never reorderable.
  *
  * Every window keeps its tab node for as long as it exists, and a render updates that node in place — label, bell, style classes, tooltip.
  * Rebuilding the strip instead would take the node a tab's context menu hangs off out of the scene, so a bell arriving on another tab
  * could close a menu you are using. The children list is the strip's state: each window tab carries its own parts as user data, so there
  * is nothing to keep in step with it.
  */
private[corvi] final class TabStrip(drag: TabDragState, intent: WindowIntent => Unit) extends FlowPane:
  import TabStrip.{Tab, dragging}

  /** The fixed ends, built once: they are not windows and never change. */
  private val overview: HBox = overviewTab()
  private val fresh: HBox = newTab()

  locally:
    val _ = delegate.getStyleClass.add("window-tabs")
    hgap = 0
    vgap = Theme.tabRowSpacing
    padding = Insets(0, Theme.tabStripSidePadding, 0, Theme.tabStripSidePadding)
    // The row, plus the hairline the border adds under it.
    minHeight = Theme.tabRowHeight + Theme.stripHairline
    prefHeight = Theme.tabRowHeight + Theme.stripHairline
    children = Seq(overview, fresh)

  /** Draws the strip from the state: a tab is created the first time its window is seen, then only what changed about it is redrawn. The
    * children list is only touched when the set or the order of the windows changed.
    */
  def show(selection: Selection, windows: Seq[TerminalWindow]): Unit =
    val mounted: Map[WindowId, Tab] = tabs.map(tab => tab.id -> tab).toMap
    val drawn: Vector[Tab] = windows.toVector.zipWithIndex.map { (window, index) =>
      val tab = mounted.getOrElse(window.id, windowTab(window.id))
      update(tab, window, index, selection)
      tab
    }
    val desired: Vector[JfxNode] = overview.delegate +: drawn.map(tab => tab.node.delegate) :+ fresh.delegate
    val current: Vector[JfxNode] = children.toVector
    val sameOrder = current.size == desired.size && current.indices.forall(index => current(index) eq desired(index))
    if !sameOrder then
      children.clear()
      val _ = children ++= desired

  // --- the tabs on screen ------------------------------------------------------------------------------------------------------------

  /** The window tabs on screen, in order. The fixed ends carry no user data, so they are not among them. */
  private def tabs: Vector[Tab] =
    children.toVector.flatMap { node =>
      node.getUserData match
        case tab: Tab => Some(tab)
        case _        => None
    }

  /** Everything a tab's own dress depends on, in the order `corvi.css` expects to cascade: a current ended tab stays white, and a dragged
    * tab stays faded across a render that lands mid-gesture.
    */
  private def tabClasses(window: TerminalWindow, selection: Selection): Seq[String] =
    Seq("window-tab") ++
      (if selection == Selection.Window(window.id) then Seq("current") else Seq.empty) ++
      (if window.state == TerminalWindow.State.Ended then Seq("ended") else Seq.empty) ++
      (if drag.drag == TabDrag.Carrying(window.id) then Seq(dragging) else Seq.empty)

  /** The tab's own colour is the icon's when the window has no state to report (the `new` tab); a window adds its own, and that keeps its
    * colour even while the tab is current.
    */
  private def iconClasses(state: TerminalWindow.State): Seq[String] =
    state match
      case TerminalWindow.State.Idle  => Seq("tab-icon", "idle")
      case TerminalWindow.State.Ended => Seq("tab-icon", "ended")

  private def update(tab: Tab, window: TerminalWindow, index: Int, selection: Selection): Unit =
    tab.label.text = window.display
    tab.tip.setText(tooltip(window, index))
    dress(tab.node.delegate, tabClasses(window, selection))
    dressIcon(tab.icon, iconClasses(window.state))
    showBell(tab, window.activity == TerminalWindow.Activity.Unseen)

  /** What the tab's tooltip says: the chord that selects it, and how its shell ended when that is worth telling. */
  private def tooltip(window: TerminalWindow, index: Int): String =
    val chord = s"ctrl-b $index"
    window.endingDetail.map(detail => s"$chord · $detail").getOrElse(chord)

  private def dress(node: JfxNode, classes: Seq[String]): Unit =
    if node.getStyleClass.asScala.toVector != classes then
      val _ = node.getStyleClass.setAll(classes.asJava)

  /** The glyph's style classes live on the shapes inside its icon box, because a JavaFX parent cannot style its children's strokes for
    * them.
    */
  private def dressIcon(icon: Node, classes: Seq[String]): Unit =
    icon.delegate match
      case box: javafx.scene.layout.Pane => box.getChildren.asScala.foreach(child => dress(child, classes))
      case other                         => dress(other, classes)

  /** The `--warn` dot for output in a tab you were not looking at. The label's budget takes the bell into account, so a bell tab cannot
    * grow past the strip's tab cap.
    */
  private def showBell(tab: Tab, unseen: Boolean): Unit =
    val shown = tab.node.children.collectFirst { case region: JfxNode if region.getStyleClass.contains("bell") => region }
    (unseen, shown) match
      case (true, None) =>
        tab.node.children.insert(tab.node.children.size, bellDot().delegate)
        tab.label.maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth - Theme.tabBellWidth
      case (false, Some(dot)) =>
        val _ = tab.node.children.remove(tab.node.children.indexOf(dot))
        tab.label.maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth
      case _ => ()

  // --- building a tab ---------------------------------------------------------------------------------------------------------------

  private def windowTab(id: WindowId): Tab =
    val label = new Label("")
    label.maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth
    val icon = Icons.terminal("tab-icon")
    val node = new HBox:
      val _ = styleClass ++= Seq("window-tab")
      minHeight = Theme.tabRowHeight
      prefHeight = Theme.tabRowHeight
      maxWidth = Theme.tabMaxWidth
      alignment = Pos.CenterLeft
      spacing = Theme.tabContentSpacing
      focusTraversable = false
      children = Seq(icon, label)
    val tab = Tab(id, node, icon, label, Tooltip(""))
    node.delegate.setUserData(tab)
    Tooltip.install(node.delegate, tab.tip)
    closeMenu(node, id)
    attachDrag(node, id)
    tab

  private def tabNode(label: String, icon: Option[Node], extra: Seq[String]): HBox =
    val text = new Label(label):
      maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth
    new HBox:
      val _ = styleClass ++= (Seq("window-tab") ++ extra)
      minHeight = Theme.tabRowHeight
      prefHeight = Theme.tabRowHeight
      maxWidth = Theme.tabMaxWidth
      alignment = Pos.CenterLeft
      spacing = Theme.tabContentSpacing
      focusTraversable = false
      children = icon.toSeq :+ text

  /** The fixed left end, and the way back to the placeholder: a label and nothing else — upstream gives the Overview tab no icon. */
  private def overviewTab(): HBox =
    val tab = tabNode(label = "Overview", icon = None, extra = Seq("overview"))
    tab.onMouseClicked = _ => intent(WindowIntent.SelectOverview)
    tab

  /** The fixed right end: the terminal glyph first, then the label. */
  private def newTab(): HBox =
    val tab = tabNode(label = "new", icon = Some(Icons.terminal("tab-icon")), extra = Seq("new"))
    tab.onMouseClicked = _ => intent(WindowIntent.OpenWindow)
    tab

  private def bellDot(): Region =
    new Region:
      val _ = styleClass ++= Seq("bell")

  // --- gestures ----------------------------------------------------------------------------------------------------------------------

  /** Right-clicking a window tab offers to close it. */
  private def closeMenu(tab: HBox, window: WindowId): Unit =
    val close = new MenuItem("Close tab"):
      onAction = _ => intent(WindowIntent.CloseWindow(window))
    val menu = new ContextMenu(close)
    tab.onContextMenuRequested = event =>
      menu.show(tab, Side.Bottom, 0, 0)
      event.consume()

  /** Drags are wired on the JavaFX nodes: a pick result and the button of a press are JavaFX types, and ScalaFX's wrappers would only be in
    * the way. The tab under the pointer is found by walking the strip's tabs, because the pointer is over a label or an icon as often as it
    * is over the tab itself.
    */
  private def attachDrag(tab: HBox, id: WindowId): Unit =
    val node = tab.delegate
    node.setOnMousePressed(event => if event.getButton == JfxMouseButton.PRIMARY then drag.update(TabDrag.Pressed(id)))
    node.setOnMouseDragged(event =>
      drag.drag match
        // Carrying starts when the pointer reaches another tab. That is what matters for the drop — the tab
        // under the pointer — and it means a click that wobbles never becomes a drag.
        case TabDrag.Pressed(from) if windowAt(Option(event.getPickResult.getIntersectedNode)).exists(_ != from) =>
          drag.update(TabDrag.Carrying(from))
          tabs.find(_.id == from).foreach(carrying => showDragging(carrying, dragging = true))
        case _ => ()
    )
    node.setOnMouseReleased(event =>
      drag.drag match
        case TabDrag.Carrying(from) =>
          tabs.find(_.id == from).foreach(carrying => showDragging(carrying, dragging = false))
          windowAt(Option(event.getPickResult.getIntersectedNode)).filter(_ != from) match
            case Some(to) =>
              drag.update(TabDrag.Dropped(from))
              intent(WindowIntent.MoveWindow(from, to))
            case None => drag.update(TabDrag.Idle)
        // A press that never became a carry is a click. Clearing it here is what lets the click that follows select, and what
        // lets the next render paint: a render during a gesture is deferred, and a press left behind would defer every one after it.
        case TabDrag.Pressed(_) => drag.update(TabDrag.Idle)
        case _                  => ()
    )
    tab.onMouseClicked = _ =>
      drag.drag match
        case TabDrag.Dropped(_) => drag.update(TabDrag.Idle) // the click a drag leaves behind
        case _                  => intent(WindowIntent.SelectWindow(id))

  /** The fade a carried tab wears. A render that lands mid-drag has already set it from the drag state, so adding it again must not stack.
    */
  private def showDragging(tab: Tab, dragging: Boolean): Unit =
    if dragging then
      if !tab.node.styleClass.contains(TabStrip.dragging) then { val _ = tab.node.styleClass += TabStrip.dragging }
    else
      val _ = tab.node.styleClass -= TabStrip.dragging

  /** The window whose tab holds that node. */
  private def windowAt(node: Option[JfxNode]): Option[WindowId] =
    node.flatMap(at => tabs.find(tab => isInside(at, tab.node.delegate)).map(_.id))

  private def isInside(node: JfxNode, ancestor: JfxNode): Boolean =
    (node ne null) && ((node eq ancestor) || isInside(node.getParent, ancestor))

private[corvi] object TabStrip:

  /** The style class a tab carries while a drag is carrying it. */
  private val dragging: String = "dragging"

  /** One tab's parts, kept as the node's user data so an update can find them again without re-creating the tab. */
  private final case class Tab(id: WindowId, node: HBox, icon: Node, label: Label, tip: Tooltip)
