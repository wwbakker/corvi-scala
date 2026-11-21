package corvi.app

import scala.concurrent.duration.*

/** Bounded exponential backoff for a host that has gone away.
  *
  * The first retry is quick enough to recover from a host restart, and the cap keeps a broken host from producing an unbounded loop or a
  * busy one. The policy is pure so the retry schedule can be checked without sleeping.
  */
final case class ReconnectPolicy(initial: FiniteDuration, maximum: FiniteDuration):
  def next(delay: FiniteDuration): FiniteDuration =
    if delay >= maximum then maximum
    else
      val doubled = delay * 2
      if doubled >= maximum then maximum else doubled

object ReconnectPolicy:
  val default: ReconnectPolicy = ReconnectPolicy(initial = 250.millis, maximum = 4.seconds)
