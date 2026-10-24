package corvi.renderer

import corvi.contract.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Literal search over the live grid and loaded history, with cell columns rather than UTF-16 offsets. */
final class TerminalSearchTest:

  private val theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))

  private def cell(text: String, width: CellWidth = CellWidth.Narrow): Cell =
    Cell(text, width, None, None, false, false, false, false)

  private def row(text: String): Row = Row(text.map(character => cell(character.toString)).toVector)

  private def frame(rows: Vector[Row], historyRows: Int): Frame =
    Frame(
      id = SessionId("search"),
      size = TermSize(cols = rows.map(_.cells.size).maxOption.getOrElse(0), rows = rows.size),
      rows = rows,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = historyRows,
      theme = theme
    )

  private def model(live: Vector[Row], history: Vector[Row] = Vector.empty): ScreenModel =
    ScreenModel(frame(live, historyRows = history.size), history, scrollOffset = 0, newOutput = false)

  @Test def `search is literal and case-insensitive`(): Unit =
    assertEquals(Vector(TerminalSearch.Match(0, 2, 4)), TerminalSearch.search(model(Vector(row("xxABCxx"))), "abc"))
    assertEquals(Vector.empty, TerminalSearch.search(model(Vector(row("abc"))), "b.d"))

  @Test def `case-insensitive matching keeps the original index when lowercasing expands`(): Unit =
    assertEquals(Vector(TerminalSearch.Match(0, 0, 0)), TerminalSearch.search(model(Vector(row("İstanbul"))), "i"))
    assertEquals(Vector(TerminalSearch.Match(0, 0, 0)), TerminalSearch.search(model(Vector(row("İstanbul"))), "İ"))

  @Test def `overlapping matches are all reported`(): Unit =
    assertEquals(
      Vector(
        TerminalSearch.Match(0, 0, 1),
        TerminalSearch.Match(0, 1, 2),
        TerminalSearch.Match(0, 2, 3)
      ),
      TerminalSearch.search(model(Vector(row("aaaa"))), "aa")
    )

  @Test def `wide characters report the cells they occupy`(): Unit =
    val wide = Row(Vector(cell("a"), cell("世", CellWidth.Wide), cell("", CellWidth.Continuation), cell("b")))
    assertEquals(Vector(TerminalSearch.Match(0, 1, 3)), TerminalSearch.search(model(Vector(wide)), "世b"))

  @Test def `loaded history comes before the live grid`(): Unit =
    val history = Vector(row("newer history"), row("older history")) // newest first
    val found = TerminalSearch.search(model(Vector(row("live row")), history), "history")
    assertEquals(
      Vector(TerminalSearch.Match(0, 6, 12), TerminalSearch.Match(1, 6, 12)),
      found
    )

  @Test def `a match in history maps to its viewport row and scroll offset`(): Unit =
    val history = Vector(row("newer"), row("older"))
    val screen = model(Vector(row("live")), history)
    assertEquals(None, TerminalSearch.viewportRow(screen, 0), "history is above the live grid at offset zero")
    assertEquals(Some(0), TerminalSearch.viewportRow(screen, 2), "the live row is visible")
    assertEquals(2, TerminalSearch.scrollOffsetFor(screen, 0), "the oldest row needs two history rows above the grid")
    assertEquals(1, TerminalSearch.scrollOffsetFor(screen, 1))
    assertEquals(0, TerminalSearch.scrollOffsetFor(screen, 2))

  @Test def `the viewport row follows the scroll offset`(): Unit =
    val history = Vector(row("newer"), row("older"))
    val screen = model(Vector(row("live0"), row("live1"), row("live2")), history).scrolled(2)
    assertEquals(Some(0), TerminalSearch.viewportRow(screen, 0))
    assertEquals(Some(1), TerminalSearch.viewportRow(screen, 1))
    assertEquals(Some(2), TerminalSearch.viewportRow(screen, 2))
    assertEquals(None, TerminalSearch.viewportRow(screen, 3))
