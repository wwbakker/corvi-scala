package corvi.ui

import scalafx.geometry.Pos
import scalafx.scene.control.Label
import scalafx.scene.layout.VBox

/** What the Overview tab shows: the change the window is about. Decoration, like the sidebar — the spike is about the terminals, and the
  * placeholder gives the tab strip something to point at when nothing is running.
  */
private[corvi] object Overview:
  def apply(): VBox =
    new VBox:
      val _ = styleClass ++= Seq("overview")
      alignment = Pos.TopLeft
      spacing = 10
      children = Seq(
        new Label("scalafx-terminal-tabs") { val _ = styleClass ++= Seq("overview-title") },
        new Label("The Corvi terminal, rebuilt: the host owns the screens, the window only paints them.") {
          val _ = styleClass ++= Seq("overview-body")
        },
        new Label("Open a terminal with the new tab, or with ⌘T.") { val _ = styleClass ++= Seq("overview-body") }
      )
