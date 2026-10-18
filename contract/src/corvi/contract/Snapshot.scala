package corvi.contract

/** The visible screen as the host last read it: the grid, the cursor, the modes, and how much history is behind it. The frontend draws this
  * and nothing else.
  */
final case class Snapshot(
    id: SessionId,
    size: TermSize,
    rows: Vector[Row],
    cursor: Cursor,
    modes: Modes,
    historyRows: Int
) derives CanEqual

/** One visible row that changed, and what it changed to. */
final case class RowDamage(row: Int, cells: Row) derives CanEqual

/** What changed between two frames: the rows that differ, plus the parts of a frame that are not rows. An `Option` that is `None` means
  * "unchanged", and a `Some` carries the new value.
  *
  * Rows alone are not a frame delta. A cursor move, a mode toggle (mouse reporting, bracketed paste, the alternate screen), a shell's title
  * change and history growth all change what a client draws or sends while leaving every cell alone, so a damage that carried only rows
  * would leave a client permanently behind. `size` is here for the same reason: after a resize the row indexes mean a different grid, so a
  * client that sees it takes a fresh snapshot instead of patching one.
  */
final case class Damage(
    rows: Vector[RowDamage],
    size: Option[TermSize],
    cursor: Option[Cursor],
    modes: Option[Modes],
    historyRows: Option[Int]
) derives CanEqual:
  def isEmpty: Boolean = rows.isEmpty && size.isEmpty && cursor.isEmpty && modes.isEmpty && historyRows.isEmpty

  /** The frame this delta describes, given the frame it was taken against. This is the host's own definition of a delta, so a client that
    * applies damage this way holds the same screen the host does without asking for another snapshot.
    */
  def applyTo(before: Snapshot): Snapshot =
    val target = size.getOrElse(before.size)
    // A resize re-indexes the grid: the base is the new shape, and the rows it carries are the ones the host says are visible now.
    val base = if target == before.size then before.rows else Vector.fill(target.rows)(Row(Vector.empty))
    Snapshot(
      id = before.id,
      size = target,
      rows = rows.foldLeft(base)((current, change) => current.updated(change.row, change.cells)),
      cursor = cursor.getOrElse(before.cursor),
      modes = modes.getOrElse(before.modes),
      historyRows = historyRows.getOrElse(before.historyRows)
    )

object Damage:
  val none: Damage = Damage(Vector.empty, None, None, None, None)
