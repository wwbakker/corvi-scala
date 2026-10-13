package corvi

import scalafx.geometry.{Insets, Pos, Side}
import scalafx.scene.Node
import scalafx.scene.control.{ContextMenu, Label, MenuItem}
import scalafx.scene.layout.{FlowPane, HBox, Region}

/** The window strip: `Overview | <windows…> | new`, with the two ends fixed and never reorderable. It draws a `ShellState` and calls back
  * into the shell; it keeps no state of its own.
  */
private[corvi] object WindowTabs:
  /** What the strip asks the shell to do. */
  final case class Actions(
      selectOverview: () => Unit,
      selectWindow: Int => Unit,
      closeWindow: Int => Unit,
      openWindow: () => Unit
  )

  def strip(selection: Selection, windows: Seq[TerminalWindow], actions: Actions): FlowPane =
    new FlowPane:
      val _ = styleClass ++= Seq("window-tabs")
      hgap = 0
      vgap = Theme.tabRowSpacing
      padding = Insets(0, Theme.tabStripSidePadding, 0, Theme.tabStripSidePadding)
      // The row, plus the hairline the border adds under it.
      minHeight = Theme.tabRowHeight + Theme.stripHairline
      prefHeight = Theme.tabRowHeight + Theme.stripHairline
      children = overviewTab(selection, actions) +: (windows.map(window => windowTab(window, selection, actions)) :+ newTab(actions))

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

  private def windowTab(window: TerminalWindow, selection: Selection, actions: Actions): HBox =
    val tab = tabNode(
      label = window.label,
      icon = Some(Icons.terminal(iconClasses(window.state)*)),
      bell = window.activity == TerminalWindow.Activity.Unseen,
      selected = selection == Selection.Window(window.id),
      ended = window.state == TerminalWindow.State.Ended,
      extra = Seq.empty
    )
    tab.onMouseClicked = _ => actions.selectWindow(window.id)
    val close = new MenuItem("Close tab"):
      onAction = _ => actions.closeWindow(window.id)
    val menu = new ContextMenu(close)
    tab.onContextMenuRequested = event =>
      menu.show(tab, Side.Bottom, 0, 0)
      event.consume()
    tab

  /** The fixed right end: the terminal glyph first, then the label. */
  private def newTab(actions: Actions): HBox =
    val tab =
      tabNode(label = "new", icon = Some(Icons.terminal("tab-icon")), bell = false, selected = false, ended = false, extra = Seq("new"))
    tab.onMouseClicked = _ => actions.openWindow()
    tab

  private def tabNode(label: String, icon: Option[Node], bell: Boolean, selected: Boolean, ended: Boolean, extra: Seq[String]): HBox =
    val bellWidth = if bell then Theme.tabBellWidth else 0
    val text = new Label(label):
      // The budget takes the bell into account, so a bell tab cannot grow past the cap.
      maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth - bellWidth
    val parts: Seq[Node] = icon.toSeq ++ Seq(text) ++ (if bell then Seq(bellDot()) else Seq.empty)
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
      children = parts

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
