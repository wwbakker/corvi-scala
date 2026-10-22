package corvi.protocol

import corvi.contract.*
import kyo.*

/** The codecs the wire needs.
  *
  * They live here rather than in `contract` so the DTOs stay free of kyo: a renderer that draws a `Snapshot` has no reason to depend on a
  * serialisation library. `SessionId` and `ClientId` are opaque, so their schemas are the string's with the two conversions; everything
  * else derives.
  */
object Schemas:
  given Schema[SessionId] = Schema.stringSchema.transform[SessionId](SessionId.apply)(_.value)
  given Schema[ClientId] = Schema.stringSchema.transform[ClientId](ClientId.apply)(_.value)

  given Schema[TermSize] = Schema.derived
  given Schema[TerminalTheme] = Schema.derived
  given Schema[Rgb] = Schema.derived
  given Schema[CellWidth] = Schema.derived
  given Schema[Cell] = Schema.derived
  given Schema[Row] = Schema.derived
  given Schema[CursorShape] = Schema.derived
  given Schema[Cursor] = Schema.derived
  given Schema[MouseMode] = Schema.derived
  given Schema[MouseFormat] = Schema.derived
  given Schema[Modes] = Schema.derived
  given Schema[Snapshot] = Schema.derived
  given Schema[RowDamage] = Schema.derived
  given Schema[SessionInfo] = Schema.derived
  given Schema[Damage] = Schema.derived

  // The typed route errors carry their own payloads: a case class with a `using Frame` cannot be derived, and the wire only needs the
  // fields a client can act on.
  given Schema[UnknownSession] =
    Schema.stringSchema.transform[UnknownSession](raw => UnknownSession(SessionId(raw)))(_.id.value)
  given Schema[SessionEnded] =
    Schema.stringSchema.transform[SessionEnded](raw => SessionEnded(SessionId(raw)))(_.id.value)
  given Schema[ClientAlreadyBound] =
    Schema.stringSchema.transform[ClientAlreadyBound](raw => ClientAlreadyBound(ClientId(raw)))(_.id.value)
  given Schema[Unauthorized] =
    Schema.stringSchema.transform[Unauthorized](reason => Unauthorized(reason))(_.reason)
  given Schema[HostUnavailable] =
    Schema.stringSchema.transform[HostUnavailable](reason => HostUnavailable(reason))(_.reason)
  given Schema[SessionFailed] =
    summon[Schema[FailurePayload]].transform[SessionFailed](payload => SessionFailed(SessionId(payload.id), payload.reason))(failed =>
      FailurePayload(failed.id.value, failed.reason)
    )

/** What the daemon says about itself: enough for a client to tell it is talking to the host it left, and how much that host is holding. */
final case class HostStatus(pid: Long, sessions: Int, uptimeMillis: Long) derives Schema, CanEqual

/** What `SessionFailed` puts on the wire. */
final case class FailurePayload(id: String, reason: String) derives Schema, CanEqual
