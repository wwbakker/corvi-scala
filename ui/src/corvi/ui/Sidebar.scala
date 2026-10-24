package corvi.ui

import scalafx.geometry.Pos
import scalafx.scene.control.Label
import scalafx.scene.layout.{HBox, Priority, Region, VBox}

/** The sidebar, fixed decoration: the workspace picker, `Changes`, the selected change's windows, and the settings cog. Nothing here is
  * interactive — it is there so the window reads as Corvi.
  */
private[corvi] object Sidebar:
  def apply(): VBox =
    new VBox:
      val _ = styleClass ++= Seq("sidebar")
      minWidth = Theme.sidebarWidth
      prefWidth = Theme.sidebarWidth
      maxWidth = Theme.sidebarWidth
      children = Seq(
        workspace(),
        navRow("Changes"),
        section("IDEAS", withNew = true),
        section("CHANGES", withNew = false),
        windowRow("scalafx-terminal-tabs", selected = true),
        windowRow("spike-notes", selected = false),
        grower(),
        footer()
      )

  /** `All work ▾` — the workspace picker. Upstream spells the caret into the label rather than drawing a glyph for it.
    */
  private def workspace(): HBox =
    new HBox:
      val _ = styleClass ++= Seq("sidebar-header")
      alignment = Pos.CenterLeft
      children = Seq(new Label("All work \u25be"))

  /** A top-level nav row, such as `Changes`. */
  private def navRow(text: String): HBox =
    row(text, selected = false)

  /** A window of the selected change; the terminal window is the selected one. */
  private def windowRow(text: String, selected: Boolean): HBox =
    row(text, selected)

  private def row(text: String, selected: Boolean): HBox =
    new HBox:
      val _ = styleClass ++= (if selected then Seq("sidebar-row", "selected") else Seq("sidebar-row"))
      alignment = Pos.CenterLeft
      children = Seq(new Label(text))

  /** A section header; `IDEAS` carries the green-washed `New` button. */
  private def section(title: String, withNew: Boolean): HBox =
    val label = new Label(title):
      val _ = styleClass ++= Seq("group-label")
    new HBox:
      val _ = styleClass ++= Seq("sidebar-section")
      alignment = Pos.CenterLeft
      children = if withNew then Seq(label, grower(), chip()) else Seq(label)

  private def chip(): Label =
    new Label("New"):
      val _ = styleClass ++= Seq("new-chip")

  /** Takes the slack so the footer sits at the bottom of the sidebar. */
  private def grower(): Region =
    val spacer = new Region
    VBox.setVgrow(spacer, Priority.Always)
    HBox.setHgrow(spacer, Priority.Always)
    spacer

  private def footer(): HBox =
    new HBox:
      val _ = styleClass ++= Seq("sidebar-footer")
      alignment = Pos.CenterLeft
      children = Seq(Icons.gear("gear-icon"))
