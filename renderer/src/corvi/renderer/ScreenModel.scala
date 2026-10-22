package corvi.renderer

import corvi.contract.*

/** What the canvas draws: the live grid, or history rows above it when the user has scrolled back, plus the selection state that goes with
  * it. Pure: everything here can be checked without a toolkit, which is where most of the renderer's behaviour lives.
  */
final case class ScreenModel(frame: Frame, history: Vector[Row], scrollOffset: Int, newOutput: Boolean) derives CanEqual:

  def rows: Int = frame.size.rows

  def cols: Int = frame.size.cols

  def isLive: Boolean = scrollOffset == 0

  /** How far back the user can go: the host's history depth for this session. */
  def maxScroll: Int = frame.historyRows

  /** The rows to draw, top to bottom: the newest `scrollOffset` history rows (oldest of those first, so they read in order) followed by the
    * top of the live grid. At offset zero that is just the grid.
    *
    * Rows the client has not been given yet are blank rather than filled with grid rows: while a page is in flight the screen keeps its
    * shape instead of jumping, and the rows fill in when the page arrives.
    */
  def viewport: Vector[Row] =
    if scrollOffset == 0 then frame.rows
    else
      val missing = missingHistory
      val known = history.take(scrollOffset - missing).reverse
      val above = Vector.fill(missing)(Row(Vector.empty)) ++ known
      above ++ frame.rows.take(math.max(0, rows - above.size))

  /** How many of the rows the user wants to see the client has not asked the host for yet. */
  def missingHistory: Int = math.max(0, scrollOffset - history.size)

  def row(index: Int): Row = viewport.lift(index).getOrElse(Row(Vector.empty))

  def rowText(index: Int): String = row(index).cells.map(_.text).mkString

  /** Scrolling clamps to the host's history; reaching the bottom returns to the live grid and clears the new-output mark. */
  def scrolled(offset: Int): ScreenModel =
    val clamped = math.max(0, math.min(maxScroll, offset))
    copy(scrollOffset = clamped, newOutput = if clamped == 0 then false else newOutput)

  def scrolledBy(delta: Int): ScreenModel = scrolled(scrollOffset + delta)

  def withHistory(pages: Vector[Row]): ScreenModel = copy(history = pages)

  /** A new frame. While the user is scrolled back, output that scrolls rows into history would otherwise move the text under the reader, so
    * the offset grows with the history and the reader stays where they were; and the "there is output you cannot see" mark is set.
    */
  def withFrame(next: Frame): ScreenModel =
    if isLive then copy(frame = next, newOutput = newOutput)
    else
      val grown = next.historyRows - frame.historyRows
      val anchored = math.max(0, math.min(next.historyRows, scrollOffset + math.max(0, grown)))
      copy(frame = next, scrollOffset = anchored, newOutput = true)

  def toLive: ScreenModel = copy(scrollOffset = 0, newOutput = false)

  /** The text a selection covers, the way a terminal copies it: cells in reading order, wide characters once (their continuation cells
    * carry no text), and no trailing blanks on each line.
    */
  def text(selection: Selection): String =
    val (first, last) = selection.ordered
    val lastRow = math.min(last.row, rows - 1)
    if lastRow < first.row then ""
    else
      (first.row to lastRow)
        .map { row =>
          val from = if row == first.row then first.col else 0
          val to = if row == lastRow then last.col else cols - 1
          val cells = this.row(row).cells
          cells.zipWithIndex
            .collect { case (cell, col) if col >= from && col <= to && cell.width != CellWidth.Continuation => cell.text }
            .mkString
            .stripTrailing()
        }
        .mkString("\n")
        .stripTrailing()

object ScreenModel:
  def from(snapshot: Snapshot): ScreenModel = ScreenModel(Frame.from(snapshot), Vector.empty, 0, newOutput = false)
