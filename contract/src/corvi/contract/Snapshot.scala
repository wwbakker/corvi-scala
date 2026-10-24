package corvi.contract

/** The visible screen as the host last read it: the grid, the cursor, the modes, how much history is behind it, and the colours it falls
  * back on. A client that has only attached can draw everything from this.
  */
final case class Snapshot(
    id: SessionId,
    size: TermSize,
    rows: Vector[Row],
    cursor: Cursor,
    modes: Modes,
    historyRows: Int,
    theme: TerminalTheme
) derives CanEqual

/** One visible row that changed, and what it changed to. */
final case class RowDamage(row: Int, cells: Row) derives CanEqual

/** What changed between two frames: the rows that differ, plus the parts of a frame that are not rows. An `Option` that is `None` means
  * "unchanged", and a `Some` carries the new value.
  *
  * Rows alone are not a frame delta. A cursor move, a mode toggle (mouse reporting, bracketed paste, the alternate screen), a shell's title
  * change and history growth all change what a client draws or sends while leaving every cell alone, so a damage that carried only rows
  * would leave a client permanently behind.
  *
  * A delta is *absolute*: every row it carries is the row's whole contents, and applying it twice gives the same frame as applying it once.
  * That only works if the rows it carries are the rows that exist, so a delta that changes `size` carries **every** row of the new grid -
  * the row indexes have moved, and a surviving row that is not resent would keep contents that no longer belong to it.
  */
final case class Damage(
    rows: Vector[RowDamage],
    size: Option[TermSize],
    cursor: Option[Cursor],
    modes: Option[Modes],
    historyRows: Option[Int]
) derives CanEqual:
  def isEmpty: Boolean = rows.isEmpty && size.isEmpty && cursor.isEmpty && modes.isEmpty && historyRows.isEmpty

  /** The frame this delta describes, given the frame it was taken against. The definition lives in `Frame`, which is the type a client
    * holds, so the host's idea of a delta and a renderer's cannot drift apart.
    */
  def applyTo(before: Snapshot): Snapshot = Frame.from(before).apply(this).snapshot

object Damage:
  val none: Damage = Damage(Vector.empty, None, None, None, None)
