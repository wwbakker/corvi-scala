package corvi.renderer

import corvi.contract.{CellWidth, Row}

/** Literal, case-insensitive search over the loaded history and the live grid.
  *
  * Matches are reported as document rows and terminal-cell columns: history rows come first in reading order, then the visible frame. A
  * wide character counts as the single cell it starts in, so a match's span is what the painter should highlight. Overlaps are reported
  * separately: searching `aa` in `aaa` finds both positions.
  */
object TerminalSearch:

  final case class Match(row: Int, start: Int, end: Int) derives CanEqual

  /** The cells a document row exposes to the search, with each UTF-16 index mapped back to its terminal column. */
  private final case class SearchRow(text: String, columns: Vector[Int])

  def search(model: ScreenModel, query: String): Vector[Match] =
    if query.isEmpty then Vector.empty
    else
      val rows = model.history.reverseIterator.toVector ++ model.frame.rows
      rows.zipWithIndex.flatMap { case (row, rowIndex) => matchesIn(row, query, rowIndex) }

  /** The viewport row a document row occupies, if it is on screen at this scroll offset. */
  def viewportRow(model: ScreenModel, row: Int): Option[Int] =
    if row < 0 then None
    else if row < model.history.size then
      val placed = model.scrollOffset - model.history.size + row
      Option.when(placed >= 0 && placed < model.rows)(placed)
    else
      val placed = model.scrollOffset + (row - model.history.size)
      Option.when(placed >= 0 && placed < model.rows)(placed)

  /** The scroll offset that puts a document row at the top of the viewport. A live-grid row returns to offset zero. */
  def scrollOffsetFor(model: ScreenModel, row: Int): Int =
    if row < model.history.size then math.max(0, model.history.size - row)
    else 0

  private def matchesIn(row: Row, needle: String, rowIndex: Int): Vector[Match] =
    val searchable = searchableRow(row)
    val haystack = searchable.text
    if haystack.length < needle.length then Vector.empty
    else
      val builder = Vector.newBuilder[Match]
      // Keep the original text and its cell mapping intact: Unicode lowercasing can expand one code unit into several (İ -> i + a
      // combining dot), so a lowercased haystack cannot be indexed against the original columns. `regionMatches` ignores case in place.
      var from = 0
      while from <= haystack.length - needle.length do
        if haystack.regionMatches(true, from, needle, 0, needle.length) then
          val start = searchable.columns(from)
          val end = searchable.columns(from + needle.length - 1)
          val _ = builder += Match(rowIndex, start, end)
        from += 1
      builder.result()

  private def searchableRow(row: Row): SearchRow =
    val text = new StringBuilder
    val columns = Vector.newBuilder[Int]
    row.cells.zipWithIndex.foreach { case (cell, column) =>
      if cell.width != CellWidth.Continuation then
        text.append(cell.text)
        columns ++= Vector.fill(cell.text.length)(column)
    }
    SearchRow(text.toString, columns.result())
