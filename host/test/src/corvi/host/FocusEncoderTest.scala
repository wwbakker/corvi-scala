package corvi.host

import corvi.contract.MouseMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** DECSET 1004's two reports, and the mode that decides whether either is sent. */
final class FocusEncoderTest:

  @Test def `focus and blur are reported only in focus mode`(): Unit =
    assertEquals(Some("\u001b[I"), FocusEncoder.encode(focused = true, MouseMode.Focus))
    assertEquals(Some("\u001b[O"), FocusEncoder.encode(focused = false, MouseMode.Focus))
    assertTrue(FocusEncoder.encode(focused = true, MouseMode.Off).isEmpty)
    assertTrue(FocusEncoder.encode(focused = false, MouseMode.Click).isEmpty)
    assertTrue(FocusEncoder.encode(focused = true, MouseMode.AnyMotion).isEmpty)
