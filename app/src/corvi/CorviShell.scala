package corvi

import scalafx.geometry.Pos
import scalafx.scene.control.Label
import scalafx.scene.layout.{BorderPane, FlowPane, Priority, StackPane, VBox}

/** The window: the sidebar, the tab strip, and the area the terminals mount into. Chunk 2 renders a fixed strip and the Overview
  * placeholder; chunk 4 replaces both with real sessions.
  */
private[corvi] final class CorviShell:
  private val strip: FlowPane = WindowTabs.strip(overviewSelected = true, tabs = CorviShell.demoTabs)

  private val well: StackPane = new StackPane:
    val _ = styleClass ++= Seq("terminal-area")
    children = Seq(CorviShell.overview())

  val root: BorderPane = new BorderPane:
    left = Sidebar()
    center = new VBox:
      children = Seq(strip, well)
      VBox.setVgrow(well, Priority.Always)

  /** No sessions exist yet; chunk 4 closes them here. */
  def shutdown(): Unit = ()

private[corvi] object CorviShell:
  /** The strip chunk 2 shows: one working window and one with activity. Chunk 4 replaces this with the shell's session state.
    */
  private val demoTabs: Seq[WindowTabs.Tab] = Seq(
    WindowTabs.Tab("corvi", WindowTabs.State.Working, activity = false, selected = false),
    WindowTabs.Tab("shell", WindowTabs.State.Idle, activity = true, selected = false)
  )

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
