package corvi.renderer

/** A cell in the viewport: a row of the live grid, or of the history shown above it. */
final case class CellPosition(row: Int, col: Int) derives CanEqual

/** A drag selection, in viewport cells.
  *
  * Viewport coordinates are what the mouse and the painter work in, and they are only meaningful for the rows currently on screen: the view
  * clears the selection when the viewport moves, so a range can never highlight, or copy, cells that have scrolled underneath it. Anchoring
  * to absolute lines is what scrollback copying will need, and that is a change to this type, not a workaround in the view.
  */
final case class Selection(anchor: CellPosition, focus: CellPosition) derives CanEqual:

  /** The earlier corner first, so a drag in any direction is the same selection. */
  def ordered: (CellPosition, CellPosition) =
    val before = anchor.row < focus.row || (anchor.row == focus.row && anchor.col <= focus.col)
    if before then (anchor, focus) else (focus, anchor)

  def isEmpty: Boolean = anchor == focus

  def contains(row: Int, col: Int): Boolean =
    val (first, last) = ordered
    if row < first.row || row > last.row then false
    else if row == first.row && row == last.row then col >= first.col && col <= last.col
    else if row == first.row then col >= first.col
    else if row == last.row then col <= last.col
    else true

  /** The same selection anchored at `from` and dragged to `to`. */
  def draggedTo(to: CellPosition): Selection = copy(focus = to)

object Selection:
  def at(position: CellPosition): Selection = Selection(position, position)
