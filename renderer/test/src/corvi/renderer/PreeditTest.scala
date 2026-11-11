package corvi.renderer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The composition overlay's cell arithmetic: grapheme clusters, wide characters and wrapping, without a toolkit. */
final class PreeditTest:

  @Test def `clusters keep combining marks with their base`(): Unit =
    assertEquals(Vector(Preedit.Cluster("e\u0301", 1)), Preedit.clusters("e\u0301"))
    assertEquals(Vector(Preedit.Cluster("a", 1), Preedit.Cluster("b", 1)), Preedit.clusters("ab"))

  @Test def `wide characters occupy two cells`(): Unit =
    assertEquals(Vector(Preedit.Cluster("日", 2), Preedit.Cluster("本", 2)), Preedit.clusters("日本"))
    assertEquals(2, Preedit.clusters("🙂").head.width)

  @Test def `placement starts at the cursor and wraps a wide character that would cross the edge`(): Unit =
    assertEquals(
      Vector(Preedit.Placed("a", 0, 3, 1), Preedit.Placed("b", 0, 4, 1)),
      Preedit.place("ab", cols = 5, rows = 2, cursorRow = 0, cursorCol = 3)
    )
    assertEquals(
      Vector(Preedit.Placed("日", 0, 3, 2), Preedit.Placed("本", 1, 0, 2)),
      Preedit.place("日本", cols = 5, rows = 2, cursorRow = 0, cursorCol = 3)
    )

  @Test def `placement stops at the bottom of the grid`(): Unit =
    assertEquals(
      Vector(Preedit.Placed("a", 1, 0, 1)),
      Preedit.place("ab", cols = 1, rows = 2, cursorRow = 1, cursorCol = 0)
    )

  @Test def `the composition caret follows its offset`(): Unit =
    assertEquals(Some(Preedit.Caret(0, 4)), Preedit.caretCell("ab", 1, cols = 5, rows = 2, cursorRow = 0, cursorCol = 3))
    assertEquals(Some(Preedit.Caret(0, 4)), Preedit.caretCell("ab", 2, cols = 5, rows = 2, cursorRow = 0, cursorCol = 3))
    assertEquals(Some(Preedit.Caret(1, 0)), Preedit.caretCell("日本", 1, cols = 5, rows = 2, cursorRow = 0, cursorCol = 3))
    assertEquals(None, Preedit.caretCell("", 0, cols = 5, rows = 2, cursorRow = 0, cursorCol = 3))
