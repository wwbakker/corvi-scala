package corvi.renderer

import corvi.contract.*
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.{Font, Text, TextAlignment}

/** Paints a `ScreenModel`. All the arithmetic is `Layout` and all the colours are `TerminalStyle`, so this only decides the order of the
  * passes: background, then per cell the cell background, the selection and the glyph, then the cursor, then the new-output mark.
  */
final class TerminalPainter(metrics: TerminalMetrics, style: TerminalStyle):

  private val layout: Layout = metrics.layout
  private val bold: Font = metrics.bold
  private val italic: Font = metrics.italic
  private val boldItalic: Font = metrics.boldItalic
  private val newOutputLabel = "new output"
  private val newOutputWidth: Double =
    val measured = new Text(newOutputLabel)
    measured.setFont(metrics.font)
    math.ceil(measured.getLayoutBounds.getWidth) + layout.cellWidth

  def paint(gc: GraphicsContext, model: ScreenModel, selection: Option[Selection], cursorOn: Boolean, focused: Boolean): Unit =
    val theme = model.frame.theme
    val width = layout.width(model.cols)
    val height = layout.height(model.rows)
    gc.setFill(TerminalStyle.toColor(theme.background))
    gc.fillRect(0, 0, width, height)
    gc.setTextAlign(TextAlignment.LEFT)

    val viewport = model.viewport
    val selected = selection.filterNot(_.isEmpty)
    val defaultBackground = theme.background
    for row <- 0 until math.min(model.rows, viewport.size) do
      val cells = viewport(row).cells
      for col <- 0 until math.min(model.cols, cells.size) do
        val cell = cells(col)
        val x = layout.cellX(col)
        val y = layout.cellY(row)
        // Compared as the RGB the cell resolved to, so a default background costs no allocation per cell.
        val background = style.backgroundRgb(cell, theme)
        if background != defaultBackground then
          gc.setFill(TerminalStyle.toColor(background))
          gc.fillRect(x, y, layout.cellWidth, layout.cellHeight)
        if selected.exists(range => range.contains(row, col)) then
          gc.setFill(style.selectionFill)
          gc.fillRect(x, y, layout.cellWidth, layout.cellHeight)
        if cell.text.nonEmpty && cell.text != " " then drawGlyph(gc, cell, x, y, style.foreground(cell, theme))

    if cursorOn then paintCursor(gc, model, theme, focused)
    if model.newOutput then paintNewOutput(gc, model)

  /** A glyph, in the font its attributes ask for (bold and italic together when both are set), with the underline drawn as a hairline under
    * the cell.
    */
  private def drawGlyph(gc: GraphicsContext, cell: Cell, x: Double, y: Double, colour: Color): Unit =
    gc.setFont((cell.bold, cell.italic) match
      case (true, true)   => boldItalic
      case (true, false)  => bold
      case (false, true)  => italic
      case (false, false) => metrics.font)
    gc.setFill(colour)
    gc.fillText(cell.text, x, y + metrics.ascent)
    if cell.underline then
      gc.setStroke(colour)
      gc.setLineWidth(1)
      gc.strokeLine(x, y + layout.cellHeight - 1.5, x + layout.cellWidth, y + layout.cellHeight - 1.5)

  /** The cursor in the shape the terminal asked for. `None` is a block, which is what a terminal without DECSCUSR shows.
    *
    * A window without the keyboard draws the cursor hollow, the way a terminal does: it says where the cursor would be, not that it has
    * focus. Hollow means *stroke only* — no fill and no glyph redraw — so the cell keeps the colour it had.
    */
  private def paintCursor(gc: GraphicsContext, model: ScreenModel, theme: TerminalTheme, focused: Boolean): Unit =
    val cursor = model.frame.cursor
    if cursor.visible && cursor.y >= 0 && cursor.y < model.rows && cursor.x >= 0 && cursor.x < model.cols then
      val x = layout.cellX(cursor.x)
      val y = layout.cellY(cursor.y)
      val barWidth = math.max(2.0, layout.cellWidth / 6)
      if focused then
        gc.setFill(style.cursor)
        cursor.shape.getOrElse(CursorShape.Block) match
          case CursorShape.Block =>
            gc.fillRect(x, y, layout.cellWidth, layout.cellHeight)
            // The character under a filled block is drawn again in the background colour so it stays readable.
            model.frame.rows.lift(cursor.y).flatMap(_.cells.lift(cursor.x)).filter(_.text.nonEmpty).foreach { cell =>
              gc.setFont(if cell.bold then bold else metrics.font)
              gc.setFill(TerminalStyle.toColor(theme.background))
              gc.fillText(cell.text, x, y + metrics.ascent)
            }
          case CursorShape.VerticalBar => gc.fillRect(x, y, barWidth, layout.cellHeight)
          case CursorShape.Underline   => gc.fillRect(x, y + layout.cellHeight - 2, layout.cellWidth, 2)
      else
        gc.setStroke(style.cursor)
        gc.setLineWidth(1)
        cursor.shape.getOrElse(CursorShape.Block) match
          case CursorShape.Block       => gc.strokeRect(x + 0.5, y + 0.5, layout.cellWidth - 1, layout.cellHeight - 1)
          case CursorShape.VerticalBar => gc.strokeRect(x + 0.5, y + 0.5, barWidth, layout.cellHeight - 1)
          case CursorShape.Underline   => gc.strokeRect(x + 0.5, y + layout.cellHeight - 2.5, layout.cellWidth - 1, 2)

  /** The mark that says output arrived while the user was reading history. */
  private def paintNewOutput(gc: GraphicsContext, model: ScreenModel): Unit =
    gc.setFont(metrics.font)
    val label = newOutputLabel
    val width = newOutputWidth
    val height = layout.cellHeight
    val x = layout.width(model.cols) - width - layout.cellWidth
    val y = layout.height(model.rows) - height - layout.cellHeight / 2
    gc.setFill(style.newOutputFill)
    gc.fillRoundRect(x, y, width, height, height, height)
    gc.setFill(style.newOutputText)
    gc.fillText(label, x + layout.cellWidth / 2, y + metrics.ascent)
