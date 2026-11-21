package corvi.renderer

import javafx.geometry.Pos
import javafx.scene.control.{Button, Label, TextField}
import javafx.scene.layout.HBox

/** The find bar's chrome: a query field, a match counter, and previous/next/close buttons. Behaviour lives in `TerminalView`; this only
  * keeps the widgets and their style classes in one place.
  */
private[corvi] final class FindBar extends HBox:
  val field: TextField = new TextField()
  val previous: Button = new Button("↑")
  val next: Button = new Button("↓")
  val close: Button = new Button("×")
  private val status: Label = new Label()

  locally:
    getStyleClass.add("find-bar")
    setAlignment(Pos.CENTER_LEFT)
    setSpacing(6)
    setPrefHeight(32)
    getChildren.addAll(field, status, previous, next, close)
    field.setPromptText("Find")
    field.setPrefColumnCount(24)
    field.getStyleClass.add("find-field")
    status.getStyleClass.add("find-status")
    previous.setFocusTraversable(false)
    next.setFocusTraversable(false)
    close.setFocusTraversable(false)
    setVisible(false)
    setManaged(false)

  def query: String = field.getText

  def setStatusText(text: String): Unit = status.setText(text)

  def isOpen: Boolean = isVisible

  def open(): Unit =
    setVisible(true)
    setManaged(true)
    field.requestFocus()
    field.selectAll()

  def hide(): Unit =
    setVisible(false)
    setManaged(false)
    field.clear()
