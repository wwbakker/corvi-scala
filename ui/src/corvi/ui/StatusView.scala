package corvi.ui

import scalafx.geometry.Pos
import scalafx.scene.control.Label
import scalafx.scene.layout.{StackPane, VBox}

/** What the window shows before the shell has a client, and when it could not get one: the well's colour and one line of text.
  *
  * It exists so the window has something in it from the first frame: the shell needs the host, and the host may take a moment to start, but
  * a window with no scene at all reads as a black screen.
  */
private[corvi] object StatusView:
  def apply(message: String): StackPane =
    new StackPane:
      val _ = styleClass ++= Seq("status-view")
      children = Seq(
        new VBox:
          val _ = styleClass ++= Seq("status-message")
          alignment = Pos.Center
          children = Seq(new Label(message))
      )
