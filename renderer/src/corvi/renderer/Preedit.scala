package corvi.renderer

import java.text.BreakIterator

/** The IME's composed (preedit) text and the caret the platform reports for it. It is renderer state only: the host never receives it, and
  * committing the composition sends the committed text through the normal `session.text` path.
  */
final case class Preedit(text: String, caret: Int) derives CanEqual

object Preedit:

  /** One grapheme cluster and the number of terminal cells it occupies. */
  final case class Cluster(text: String, width: Int) derives CanEqual

  /** A cluster placed on the visible grid. A wide cluster covers two cells; the next cluster starts after both. */
  final case class Placed(text: String, row: Int, col: Int, width: Int) derives CanEqual

  /** The cell where the IME caret sits inside the composition. */
  final case class Caret(row: Int, col: Int) derives CanEqual

  private final case class LayoutCell(placed: Placed, start: Int, end: Int)

  /** Splits by extended grapheme cluster, so a base character and its combining marks stay together. */
  def clusters(text: String): Vector[Cluster] =
    if text.isEmpty then Vector.empty
    else
      val iterator = BreakIterator.getCharacterInstance()
      iterator.setText(text)
      val builder = Vector.newBuilder[Cluster]
      var start = iterator.first()
      var end = iterator.next()
      while end != BreakIterator.DONE do
        val cluster = text.substring(start, end)
        val _ = builder += Cluster(cluster, widthOf(cluster))
        start = end
        end = iterator.next()
      builder.result()

  /** Places the composed text from the terminal cursor, wrapping to the next row only when a cluster would cross the right edge. Rows below
    * the grid are dropped: the overlay never changes the host's grid or the model's cursor.
    */
  def place(text: String, cols: Int, rows: Int, cursorRow: Int, cursorCol: Int): Vector[Placed] =
    layout(text, cols, rows, cursorRow, cursorCol).map(_.placed)

  /** The caret cell for a composition offset. A caret inside a cluster points at that cluster's first cell; a caret at the end points after
    * the last placed cell. An empty composition has no caret.
    */
  def caretCell(text: String, caret: Int, cols: Int, rows: Int, cursorRow: Int, cursorCol: Int): Option[Caret] =
    val cells = layout(text, cols, rows, cursorRow, cursorCol)
    if cells.isEmpty then None
    else
      val clamped = if caret < 0 then text.length else math.min(caret, text.length)
      cells.find(cell => clamped < cell.end) match
        case Some(cell) => Some(Caret(cell.placed.row, cell.placed.col))
        case None       =>
          cells.lastOption.map { cell =>
            // The end of a full row is one column past the canvas; keep the bar visible in the last cell instead of clipping it.
            Caret(cell.placed.row, math.min(cols - 1, cell.placed.col + cell.placed.width))
          }

  private def layout(text: String, cols: Int, rows: Int, cursorRow: Int, cursorCol: Int): Vector[LayoutCell] =
    if cols <= 0 || rows <= 0 then Vector.empty
    else
      val builder = Vector.newBuilder[LayoutCell]
      var row = cursorRow
      var col = cursorCol
      var offset = 0
      clusters(text).foreach { cluster =>
        val width = math.max(1, cluster.width)
        if col + width > cols then
          row += 1
          col = 0
        if row >= 0 && row < rows && col < cols then
          val placed = Placed(cluster.text, row, col, math.min(width, cols - col))
          val _ = builder += LayoutCell(placed, offset, offset + cluster.text.length)
          col += width
        offset += cluster.text.length
      }
      builder.result()

  /** A small wcwidth-like width: combining-only clusters take no cell, East Asian wide and emoji clusters take two, everything else one. */
  private def widthOf(cluster: String): Int =
    val codePoints = cluster.codePoints().toArray
    if codePoints.isEmpty then 0
    else if codePoints.forall(isCombining) then 0
    else if codePoints.exists(isWide) then 2
    else 1

  private def isCombining(codePoint: Int): Boolean =
    val category = Character.getType(codePoint)
    category == Character.NON_SPACING_MARK || category == Character.ENCLOSING_MARK ||
    category == Character.COMBINING_SPACING_MARK || codePoint == 0x200d // zero-width joiner

  /** The ranges a terminal treats as double width: CJK, Hangul, fullwidth forms and emoji. */
  private def isWide(codePoint: Int): Boolean =
    (codePoint >= 0x1100 && codePoint <= 0x115f) ||
      (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint != 0x303f) ||
      (codePoint >= 0xac00 && codePoint <= 0xd7a3) ||
      (codePoint >= 0xf900 && codePoint <= 0xfaff) ||
      (codePoint >= 0xfe10 && codePoint <= 0xfe19) ||
      (codePoint >= 0xfe30 && codePoint <= 0xfe6f) ||
      (codePoint >= 0xff00 && codePoint <= 0xff60) ||
      (codePoint >= 0xffe0 && codePoint <= 0xffe6) ||
      (codePoint >= 0x1f300 && codePoint <= 0x1faff) ||
      (codePoint >= 0x20000 && codePoint <= 0x3fffd)
