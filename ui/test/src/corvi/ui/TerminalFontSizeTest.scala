package corvi.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The font-size preference's arithmetic and its bounds. */
final class TerminalFontSizeTest:

  @Test def `increase and decrease step by one`(): Unit =
    assertEquals(14.0, TerminalFontSize.increased(13.0), 0.001)
    assertEquals(12.0, TerminalFontSize.decreased(13.0), 0.001)
    assertEquals(13.0, TerminalFontSize.reset, 0.001)

  @Test def `the size stays inside the supported range`(): Unit =
    assertEquals(TerminalFontSize.maximum, TerminalFontSize.increased(TerminalFontSize.maximum), 0.001)
    assertEquals(TerminalFontSize.minimum, TerminalFontSize.decreased(TerminalFontSize.minimum), 0.001)
