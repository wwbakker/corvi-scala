package corvi.renderer

import javafx.animation.{Animation, KeyFrame, Timeline}
import javafx.scene.canvas.Canvas
import javafx.util.Duration

/** The canvas: it holds what to draw and hands every pixel to the painter. Input handling lives in `TerminalView`, so this stays a thing
  * that draws — and the drawing is a function of the model, so a renderer can be checked without sending it any events.
  *
  * Cursor blink and focus are overrides rather than derived state: a capture wants the cursor steady whatever the frame says, and a probe
  * wants both the focused and the unfocused cursor without a window manager. Neither the frame nor the toolkit can undo them.
  */
final class TerminalCanvas(metrics: TerminalMetrics, style: TerminalStyle) extends Canvas:
  private val painter = TerminalPainter(metrics, style)

  private var shown: Option[ScreenModel] = None
  private var selection: Option[Selection] = None
  private var cursorOn: Boolean = true
  private var blinkOverride: Option[Boolean] = None
  private var baseFocus: Boolean = false
  private var focusOverride: Option[Boolean] = None

  /** Cursor blink: a timeline that flips the flag, which a frame's blinking cursor switches on. */
  private val blink = Timeline(KeyFrame(Duration.millis(530), _ => toggleCursor()))
  blink.setCycleCount(Animation.INDEFINITE)

  def model: Option[ScreenModel] = shown

  def selected: Option[Selection] = selection

  /** Draws this model and selection now: the model is the whole of the input, which is what makes the canvas testable. */
  def show(model: ScreenModel, selection: Option[Selection]): Unit =
    shown = Some(model)
    this.selection = selection
    resizeTo(model.cols, model.rows)
    applyBlink(blinkOverride.getOrElse(model.frame.cursor.blinking))
    repaint()

  def select(selection: Option[Selection]): Unit =
    this.selection = selection
    repaint()

  /** Blinking, or its absence, as an override: the frame can ask for blinking on every damage and this still wins. */
  def setBlinking(enabled: Boolean): Unit =
    blinkOverride = Some(enabled)
    applyBlink(enabled)
    repaint()

  /** The focus the view has, which is what decides whether the cursor is filled or hollow in a real window: a canvas never takes the
    * keyboard, the view around it does.
    */
  def setBaseFocus(focused: Boolean): Unit =
    baseFocus = focused
    if focusOverride.isEmpty then repaint()

  /** Focus as an override, for a harness without a window manager. */
  def showFocus(focused: Boolean): Unit =
    focusOverride = Some(focused)
    repaint()

  /** Where a point in the canvas falls, in viewport cells. */
  def positionAt(x: Double, y: Double): CellPosition =
    shown match
      case Some(model) => CellPosition(metrics.layout.rowAt(y, model.rows), metrics.layout.colAt(x, model.cols))
      case None        => CellPosition(0, 0)

  /** The canvas is exactly the grid it draws: the view decides how much room it has, and asks the host for a grid that fits. */
  def resizeTo(cols: Int, rows: Int): Unit =
    setWidth(metrics.layout.width(cols))
    setHeight(metrics.layout.height(rows))

  def repaint(): Unit =
    shown.foreach(model => painter.paint(getGraphicsContext2D, model, selection, cursorOn, focusOverride.getOrElse(baseFocus)))

  private def applyBlink(enabled: Boolean): Unit =
    if enabled then blink.play()
    else
      blink.stop()
      cursorOn = true

  private def toggleCursor(): Unit =
    cursorOn = !cursorOn
    repaint()
