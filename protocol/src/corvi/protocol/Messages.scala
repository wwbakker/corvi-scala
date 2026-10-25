package corvi.protocol

import corvi.contract.*
import kyo.*

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

  /** `session.mouse`: one pointer event, in cells, for the host to encode in the session's current mouse mode. */
  final case class MouseParams(id: SessionId, input: MouseInput) derives Schema, CanEqual

  /** `session.key`: one non-text key, for the host to encode in the session's current key modes. */
  final case class KeyParams(id: SessionId, input: KeyInput) derives Schema, CanEqual

  /** `session.focus`: the window gained or lost the keyboard, for a program that asked for `1004` reports. */
  final case class FocusParams(id: SessionId, focused: Boolean) derives Schema, CanEqual

  /** `session.resize`: the grid the client wants. */
  final case class ResizeParams(id: SessionId, size: TermSize) derives Schema, CanEqual

  /** `session.damage`: a frame delta, which carries the session because a `Damage` is about a screen and not a session. The grid is a
    * compact binary payload (`DamageCodec`); the JSON envelope carries only the session id and the encoded bytes.
    */
  final case class DamageParams(id: SessionId, payload: String) derives Schema, CanEqual

  /** `host.hello`: the token from the daemon's token file. The first thing every connection says. */
  final case class HelloParams(token: String) derives Schema, CanEqual

  /** `session.ended`: how the session ended, once. */
  final case class EndedParams(id: SessionId, ended: Ended) derives Schema, CanEqual
