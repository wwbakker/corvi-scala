package corvi.contract

/** A screen as a client holds it: what a renderer paints, and what damage is applied to.
  *
  * `Frame` is the *consumer's* half of the pipeline. A client seeds one from a `Snapshot`, then applies each `Damage` as it arrives; the
  * host defines what a delta means in exactly one place, `Damage.applyTo`, which is this class too, so a client and the host cannot
  * disagree about the screen a delta describes.
  */
final case class Frame(
    id: SessionId,
    size: TermSize,
    rows: Vector[Row],
    cursor: Cursor,
    modes: Modes,
    historyRows: Int,
    theme: TerminalTheme
) derives CanEqual:

  /** The frame that damage describes, given this one. A delta that carries a `size` re-indexes the grid, so the rows it does not mention
    * are the new shape's (empty) rows rather than the old frame's.
    *
    * Applying damage is idempotent, which is what lets a client apply whatever arrives without tracking versions: a row out of the new
    * grid's range is a stale delta's leftover and is dropped rather than thrown, and a row inside it replaces the row it names.
    */
  def apply(damage: Damage): Frame =
    val target = damage.size.getOrElse(size)
    val base = if target == size then rows else Vector.fill(target.rows)(Row(Vector.empty))
    Frame(
      id = id,
      size = target,
      rows = damage.rows.foldLeft(base) { (current, change) =>
        if change.row >= 0 && change.row < current.size then current.updated(change.row, change.cells) else current
      },
      cursor = damage.cursor.getOrElse(cursor),
      modes = damage.modes.getOrElse(modes),
      historyRows = damage.historyRows.getOrElse(historyRows),
      theme = theme
    )

  /** The same screen as a snapshot: the other direction, for a client that has to hand its frame back. */
  def snapshot: Snapshot = Snapshot(id, size, rows, cursor, modes, historyRows, theme)

object Frame:
  def from(snapshot: Snapshot): Frame =
    Frame(snapshot.id, snapshot.size, snapshot.rows, snapshot.cursor, snapshot.modes, snapshot.historyRows, snapshot.theme)
