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

/** What the host knows about a session without sending its screen: enough for a tab strip, and the theme the renderer draws cells with. The
  * title is not here: it changes with the terminal's state, so it is part of `Modes` and arrives with a frame.
  */
final case class SessionInfo(
    id: SessionId,
    size: TermSize,
    historyRows: Int,
    theme: TerminalTheme,
    running: Boolean
) derives CanEqual
