package corvi.contract

/** A session's identity, kept distinct from its position in any list. */
opaque type SessionId = String

object SessionId:
  def apply(value: String): SessionId = value

  extension (id: SessionId) def value: String = id

  /** Two ids are the same when they name the same session: the opaque type hides `String`'s equality, and this says the comparison that is
    * meant. The companion is where `strictEquality` looks for it.
    */
  given CanEqual[SessionId, SessionId] = CanEqual.derived

/** The terminal's grid, in cells. */
final case class TermSize(cols: Int, rows: Int) derives CanEqual

/** What the host knows about a session without sending its screen: enough for a tab strip.
  *
  * `index` is the order the host created sessions in, which is the order a tab strip shows them: sorting by id would put `session-10`
  * before `session-2`. The client should draw from a `Snapshot` rather than from this: the title, the theme and the cells all live in the
  * frame.
  */
final case class SessionInfo(
    id: SessionId,
    index: Int,
    size: TermSize,
    historyRows: Int,
    running: Boolean,
    shellPid: Long
) derives CanEqual
