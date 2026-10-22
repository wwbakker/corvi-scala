package corvi.renderer

/** Where a cell is on the canvas, given a font's metrics.
  *
  * This is the only place that knows how a `Frame`'s grid maps to pixels, and it knows nothing else: no JavaFX type, no terminal type. That
  * is what lets a probe check the mapping — and hit-testing and resizing — without a toolkit, and it keeps the canvas a painter rather than
  * a layout engine.
  */
final case class Layout(cellWidth: Double, cellHeight: Double):

  /** How many whole cells fit in `width`. At least one, so a canvas never asks the host for a zero-column grid. */
  def colsFor(width: Double): Int = math.max(1, math.floor(width / cellWidth).toInt)

  def rowsFor(height: Double): Int = math.max(1, math.floor(height / cellHeight).toInt)

  def width(cols: Int): Double = cols * cellWidth

  def height(rows: Int): Double = rows * cellHeight

  def cellX(col: Int): Double = col * cellWidth

  def cellY(row: Int): Double = row * cellHeight

  /** The cell a point falls in, clamped to the grid: a click on the right or bottom edge belongs to the last cell, not outside the grid. */
  def colAt(x: Double, cols: Int): Int = math.max(0, math.min(cols - 1, math.floor(x / cellWidth).toInt))

  def rowAt(y: Double, rows: Int): Int = math.max(0, math.min(rows - 1, math.floor(y / cellHeight).toInt))
