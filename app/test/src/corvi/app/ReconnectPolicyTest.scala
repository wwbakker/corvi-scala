package corvi.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scala.concurrent.duration.*

/** The retry schedule: bounded, exponential and never a busy loop. */
final class ReconnectPolicyTest:

  @Test def `the delay doubles and stops at the cap`(): Unit =
    val policy = ReconnectPolicy(initial = 250.millis, maximum = 2.seconds)
    assertEquals(500.millis, policy.next(250.millis))
    assertEquals(1.second, policy.next(500.millis))
    assertEquals(2.seconds, policy.next(1.second))
    assertEquals(2.seconds, policy.next(2.seconds))
