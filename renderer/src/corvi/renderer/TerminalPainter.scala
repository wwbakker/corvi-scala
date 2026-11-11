package corvi.renderer

import corvi.contract.*
import javafx.scene.canvas.GraphicsContext
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

  def paint(
      gc: GraphicsContext,
      model: ScreenModel,
      selection: Option[Selection],
      preedit: Option[Preedit],
      findMatches: Vector[TerminalSearch.Match],
      findCurrent: Option[Int],
      hovered: Option[CellPosition],
      cursorOn: Boolean,
      focused: Boolean
  ): Unit =
    val theme = model.frame.theme
    val width = layout.width(model.cols)
    val height = layout.height(model.rows)
    gc.setFill(TerminalStyle.toColor(theme.background))
    gc.fillRect(0, 0, width, height)
    gc.setTextAlign(TextAlignment.LEFT)

    val viewport = model.viewport
    val selected = selection.filterNot(_.isEmpty)
    val findSpans = findSpansFor(model, findMatches, findCurrent)
    val hoveredLink = hovered.flatMap(position => model.row(position.row).cells.lift(position.col)).flatMap(_.link)
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
        findSpans.get((row, col)).foreach { current =>
          gc.setFill(if current then style.findCurrentFill else style.findFill)
          gc.fillRect(x, y, layout.cellWidth, layout.cellHeight)
        }
      paintTextRow(gc, cells, row, theme)
      for col <- 0 until math.min(model.cols, cells.size) do
        val cell = cells(col)
        val x = layout.cellX(col)
        val y = layout.cellY(row)
        if hoveredLink.exists(uri => hovered.exists(_.row == row) && cell.link.contains(uri)) then
          gc.setStroke(style.cursor)
          gc.setLineWidth(1)
          val width = layout.cellWidth * (if cell.width == CellWidth.Wide then 2 else 1)
          gc.strokeLine(x, y + layout.cellHeight - 1.5, x + width, y + layout.cellHeight - 1.5)

    // The cursor belongs to the live grid. Scrolled back, the rows under it are history, and a block drawn over them would point at a cell
    // the terminal is not on.
    if cursorOn && model.isLive then paintCursor(gc, model, theme, focused)
    if model.newOutput then paintNewOutput(gc, model)
    // The IME's composition is chrome on top of the grid, the way a terminal draws its preedit over the cursor cell. It is painted after
    // the cursor so a block cursor cannot hide it, and it never touches the host's cells.
    if model.isLive then preedit.foreach(value => paintPreedit(gc, model, theme, value))

  /** Paints a row's text in style runs instead of one `fillText` per cell. A terminal row is usually one colour/font for its whole width;
    * grouping turns 15k canvas text calls into a few dozen. Spaces are kept inside the run so glyph positions still follow the cell grid;
    * continuation cells and wide characters keep their two-cell glyph.
    */
  private def paintTextRow(gc: GraphicsContext, cells: Vector[Cell], row: Int, theme: TerminalTheme): Unit =
    var col = 0
    while col < cells.size do
      val first = cells(col)
      val colour = style.foreground(first, theme)
      val font = fontFor(first)
      val text = new java.lang.StringBuilder()
      val start = col
      var hasGlyph = false
      while col < cells.size && sameTextStyle(cells(col), first) do
        val cell = cells(col)
        if cell.width != CellWidth.Continuation then
          if cell.text.isEmpty then { val _ = text.append(' ') }
          else
            text.append(cell.text)
            if cell.text != " " then hasGlyph = true
        col += 1
      if hasGlyph then
        gc.setFont(font)
        gc.setFill(colour)
        gc.fillText(text.toString, layout.cellX(start), layout.cellY(row) + metrics.ascent)
        if first.underline then
          gc.setStroke(colour)
          gc.setLineWidth(1)
          gc.strokeLine(
            layout.cellX(start),
            layout.cellY(row) + layout.cellHeight - 1.5,
            layout.cellX(col),
            layout.cellY(row) + layout.cellHeight - 1.5
          )
      else if first.underline then
        gc.setStroke(colour)
        gc.setLineWidth(1)
        gc.strokeLine(
          layout.cellX(start),
          layout.cellY(row) + layout.cellHeight - 1.5,
          layout.cellX(col),
          layout.cellY(row) + layout.cellHeight - 1.5
        )

  private def sameTextStyle(cell: Cell, first: Cell): Boolean =
    cell.bold == first.bold && cell.italic == first.italic && cell.underline == first.underline &&
      cell.foreground == first.foreground && cell.background == first.background && cell.inverse == first.inverse

  private def fontFor(cell: Cell): Font =
    (cell.bold, cell.italic) match
      case (true, true)   => boldItalic
      case (true, false)  => bold
      case (false, true)  => italic
      case (false, false) => metrics.font

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

  /** Maps document matches onto viewport cells; the current match wins when highlights overlap. */
  private def findSpansFor(
      model: ScreenModel,
      matches: Vector[TerminalSearch.Match],
      current: Option[Int]
  ): Map[(Int, Int), Boolean] =
    val spans = scala.collection.mutable.HashMap.empty[(Int, Int), Boolean]
    matches.zipWithIndex.foreach { case (match_, index) =>
      TerminalSearch.viewportRow(model, match_.row).foreach { row =>
        (match_.start to match_.end).foreach { col =>
          val selected = current.contains(index)
          spans.update((row, col), selected || spans.getOrElse((row, col), false))
        }
      }
    }
    spans.toMap

  /** Draws the composed text from the cursor cell, using the same placement rules a terminal uses for wide characters. */
  private def paintPreedit(gc: GraphicsContext, model: ScreenModel, theme: TerminalTheme, preedit: Preedit): Unit =
    val cursor = model.frame.cursor
    val placed = Preedit.place(preedit.text, model.cols, model.rows, cursor.y, cursor.x)
    placed.foreach { cluster =>
      val x = layout.cellX(cluster.col)
      val y = layout.cellY(cluster.row)
      gc.setFill(style.selectionFill)
      gc.fillRect(x, y, layout.cellWidth * cluster.width, layout.cellHeight)
      gc.setFont(metrics.font)
      gc.setFill(TerminalStyle.toColor(theme.foreground))
      gc.fillText(cluster.text, x, y + metrics.ascent)
      gc.setStroke(style.cursor)
      gc.setLineWidth(1)
      gc.strokeLine(x, y + layout.cellHeight - 1.5, x + layout.cellWidth * cluster.width, y + layout.cellHeight - 1.5)
    }
    Preedit.caretCell(preedit.text, preedit.caret, model.cols, model.rows, cursor.y, cursor.x).foreach { caret =>
      val x = layout.cellX(caret.col)
      val y = layout.cellY(caret.row)
      gc.setFill(style.cursor)
      gc.fillRect(x, y, math.max(1.0, layout.cellWidth / 10), layout.cellHeight)
    }

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
