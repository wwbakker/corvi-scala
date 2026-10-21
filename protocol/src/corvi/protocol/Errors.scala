package corvi.protocol

import corvi.contract.*
import kyo.*
import kyo.Frame

/** A failure a client can match on instead of reading a message.
  *
  * These are `JsonRpcApplicationError`s because that is what kyo-jsonrpc puts on the wire, and their codes sit outside the
  * implementation-reserved range so a peer decodes them back as application errors rather than as an internal one. The route that can raise
  * one registers it with `.error[E](code, message)`, which also encodes the value into the error's `data`, so a client rebuilds the same
  * value rather than the same words.
  */
sealed abstract class TerminalError(code: Int, message: String)(using Frame) extends JsonRpcApplicationError(code, message)

object TerminalError:
  val UnknownSessionCode: Int = 4001
  val SessionFailedCode: Int = 4002
  val SessionEndedCode: Int = 4003
  val ClientBoundCode: Int = 4004

/** Asked for a session the host does not have, or one that has already been closed. */
final case class UnknownSession(id: SessionId)(using Frame)
    extends TerminalError(TerminalError.UnknownSessionCode, s"no session '${id.value}'")

/** A session could not be started. */
final case class SessionFailed(id: SessionId, reason: String)(using Frame)
    extends TerminalError(TerminalError.SessionFailedCode, s"session '${id.value}' failed: $reason")

/** The session is still listed, so a client can read its final screen, but its shell is gone: `session.text` and `session.resize` would
  * have nowhere to go.
  */
final case class SessionEnded(id: SessionId)(using Frame)
    extends TerminalError(TerminalError.SessionEndedCode, s"session '${id.value}' has ended")

/** A client id is already bound to this host. Host-side only: it never travels on the wire. */
final case class ClientAlreadyBound(id: ClientId)(using Frame)
    extends TerminalError(TerminalError.ClientBoundCode, s"client '${id.value}' is already connected")

/** A wire error that is not one of the typed cases above, kept as the peer described it. */
final case class RemoteError(override val code: Int, label: String)(using Frame) extends TerminalError(code, label)
