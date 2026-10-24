package corvi.host

import corvi.protocol.*
import java.util.concurrent.atomic.AtomicBoolean

/** One client connection to the daemon: its id, and whether it has said `host.hello` with the token from the token file.
  *
  * The check is per route rather than a kyo-jsonrpc message gate because a gate's rejection carries the *handshake* request's id, and a
  * client waiting on its own call would never see it. A route that aborts with `Unauthorized` answers the call it was asked, with the error
  * the client can match.
  */
private[corvi] final class Connection(val id: ClientId, token: String):
  private val authorized = new AtomicBoolean(false)

  /** Constant-time comparison of the two secrets, because a token file will be copied around and a byte-by-byte early exit is a timing
    * oracle.
    */
  def authorize(candidate: String): Boolean =
    val matches = java.security.MessageDigest.isEqual(
      candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    if matches then authorized.set(true)
    matches

  def isAuthorized: Boolean = authorized.get

private[corvi] object Connection:

  /** A connection that is already authorized, for the in-memory wiring: there is no door to knock on when both peers are in one process. */
  def trusted(id: ClientId): Connection =
    val connection = new Connection(id, "")
    val _ = connection.authorize("")
    connection
