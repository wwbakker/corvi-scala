package corvi.protocol

import corvi.contract.*
import kyo.*

/** How a session ended.
  *
  * `closed` says who ended it: a client that called `session.close` gets `closed = true`, and a shell that exited on its own gets
  * `closed = false`. The exit code is advisory either way — a host-initiated close reports whatever the PTY made of the signal — so a
  * client should treat "ended" as the signal and show the code only as detail. `reason` carries what ended the reader when it was not a
  * clean exit.
  */
final case class Ended(code: Option[Int], closed: Boolean, reason: Option[String]) derives Schema, CanEqual

object Ended:
  val unknownCode: Int = -1

/** The params of every route, as their own types: the contract's DTOs are the payloads, these are the envelopes around them. */
object Messages:
  import Schemas.given

  /** `session.create`: where to start the shell, and the size it should start at. */
  final case class CreateParams(cwd: String, size: TermSize) derives Schema, CanEqual

  /** `session.attach`, `session.close` and the notifications' session part. */
  final case class SessionParams(id: SessionId) derives Schema, CanEqual

  /** `session.history`: `count` rows, `from` counting back from the most recent (1-based). */
  final case class HistoryParams(id: SessionId, from: Int, count: Int) derives Schema, CanEqual

  /** `session.text`: bytes for the shell's input, as if pasted. Key and mouse events come with the renderer, which knows the modes. */
  final case class TextParams(id: SessionId, text: String) derives Schema, CanEqual

  /** `session.resize`: the grid the client wants. */
  final case class ResizeParams(id: SessionId, size: TermSize) derives Schema, CanEqual

  /** `session.damage`: a frame delta, which carries the session because a `Damage` is about a screen and not a session. */
  final case class DamageParams(id: SessionId, damage: Damage) derives Schema, CanEqual

  /** `session.ended`: how the session ended, once. */
  final case class EndedParams(id: SessionId, ended: Ended) derives Schema, CanEqual
