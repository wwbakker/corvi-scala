package corvi.protocol

import kyo.*
import kyo.Frame

/** The method names, and the routes the client peers answer.
  *
  * The host's answering routes live in the `host` module (`HostRoutes`), because they need the live sessions. Both sides use the names
  * here, so the wire is the same whether the peers are in memory or on a socket.
  */
object Methods:
  val List: String = "sessions.list"
  val Create: String = "session.create"
  val Attach: String = "session.attach"
  val History: String = "session.history"
  val Close: String = "session.close"
  val Text: String = "session.text"
  val Resize: String = "session.resize"
  val Damage: String = "session.damage"
  val Ended: String = "session.ended"

  // The daemon's own methods, which every connection needs before it can touch a session.
  val Hello: String = "host.hello"
  val Stop: String = "host.stop"
  val Status: String = "host.status"

/** The routes a client peer answers: the host's two pushes. */
object ClientRoutes:
  import Messages.*

  def routes(sink: FrameSink)(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
    Seq(
      JsonRpcRoute.notification[DamageParams](Methods.Damage)((params, _) => sink.damage(params.id, params.damage)),
      JsonRpcRoute.notification[EndedParams](Methods.Ended)((params, _) => sink.ended(params.id, params.ended))
    )
