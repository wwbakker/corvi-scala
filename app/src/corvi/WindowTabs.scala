package corvi

import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Node
import scalafx.scene.control.Label
import scalafx.scene.layout.{FlowPane, HBox, Region}

/** The window strip: `Overview | <windows…> | new`, with the two ends fixed and never reorderable. Upstream draws it as a wrapping flex row
  * whose tabs sit flush and whose rows are 2 px apart. Chunk 2 renders a fixed set of tabs; chunk 4 drives this from the shell's sessions.
  */
private[corvi] object WindowTabs:
  /** What a terminal tab's icon reports. Per Corvi the colour belongs to the state, not the tab, so a working icon stays green even on the
    * current tab.
    */
  enum State:
    case Working, Idle, Ended

  /** One terminal window. `activity` is the `--warn` bell for output that arrived while you were looking at another tab.
    */
  final case class Tab(label: String, state: State, activity: Boolean, selected: Boolean)

  def strip(overviewSelected: Boolean, tabs: Seq[Tab]): FlowPane =
    new FlowPane:
      val _ = styleClass ++= Seq("window-tabs")
      hgap = 0
      vgap = Theme.tabRowSpacing
      padding = Insets(0, Theme.tabStripSidePadding, 0, Theme.tabStripSidePadding)
      // The row, plus the hairline the border adds under it.
      minHeight = Theme.tabRowHeight + Theme.stripHairline
      prefHeight = Theme.tabRowHeight + Theme.stripHairline
      children = overview(overviewSelected) +: (tabs.map(window) :+ newTab())

  /** The fixed left end, and the way back out of a terminal: a label and nothing else — upstream gives the Overview tab no icon.
    */
  private def overview(selected: Boolean): HBox =
    tabNode(label = "Overview", icon = None, bell = false, selected = selected, extra = Seq("overview"))

  private def window(tab: Tab): HBox =
    tabNode(
      label = tab.label,
      icon = Some(Icons.terminal(iconClasses(tab.state)*)),
      bell = tab.activity,
      selected = tab.selected,
      extra = Seq.empty
    )

  /** The fixed right end: the terminal glyph first, then the label. */
  private def newTab(): HBox =
    tabNode(label = "new", icon = Some(Icons.terminal("tab-icon")), bell = false, selected = false, extra = Seq("new"))

  private def tabNode(label: String, icon: Option[Node], bell: Boolean, selected: Boolean, extra: Seq[String]): HBox =
    val bellWidth = if bell then Theme.tabBellWidth else 0
    val text = new Label(label):
      // The budget takes the bell into account, so a bell tab cannot grow past the cap.
      maxWidth = Theme.tabMaxWidth - Theme.tabChromeWidth - bellWidth
    val parts: Seq[Node] = icon.toSeq ++ Seq(text) ++ (if bell then Seq(bellDot()) else Seq.empty)
    new HBox:
      val _ = styleClass ++= (Seq("window-tab") ++ (if selected then Seq("current") else Seq.empty) ++ extra)
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

  /** The tab's own colour is the icon's when the state has nothing to say (the `new` tab); a window adds its state, and the state keeps
    * that colour even while the tab is current.
    */
  private def iconClasses(state: State): Seq[String] =
    state match
      case State.Working => Seq("tab-icon", "working")
      case State.Idle    => Seq("tab-icon", "idle")
      // Chunk 4 gives `ended` a look of its own; until then it reads as a quiet window.
      case State.Ended => Seq("tab-icon", "idle")
