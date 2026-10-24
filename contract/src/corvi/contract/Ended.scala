package corvi.contract

/** How a session ended.
  *
  * `closed` says who ended it: a client that called `session.close` gets `closed = true`, and a shell that exited on its own gets
  * `closed = false`. The exit code is advisory either way — a host-initiated close reports whatever the PTY made of the signal — so a
  * client should treat "ended" as the signal and show the code only as detail. `reason` carries what ended the reader when it was not a
  * clean exit.
  */
final case class Ended(code: Option[Int], closed: Boolean, reason: Option[String]) derives CanEqual
