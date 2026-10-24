package corvi.renderer

import corvi.contract.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The screen model is where the renderer's behaviour lives: what rows the viewport shows at a scroll offset, how a resize or output moves
  * it, and what copy takes out of it. Values in, values out — no toolkit.
  */
final class ScreenModelTest:

  private def cell(text: String, width: CellWidth = CellWidth.Narrow): Cell =
    Cell(text, width, None, None, bold = false, italic = false, underline = false, inverse = false)

  private def row(text: String): Row = Row(text.map(character => cell(character.toString)).toVector)

  private def snapshot(rows: Vector[Row], historyRows: Int): Snapshot =
    Snapshot(
      id = SessionId("s1"),
      size = TermSize(cols = rows.map(_.cells.size).maxOption.getOrElse(0), rows = rows.size),
      rows = rows,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = historyRows,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private val grid: Vector[Row] = Vector(row("live-0"), row("live-1"), row("live-2"))

  private def model(historyRows: Int = 10): ScreenModel = ScreenModel.from(snapshot(grid, historyRows))

  @Test def `a fresh model is live and shows the grid`(): Unit =
    val fresh = model()
    assertTrue(fresh.isLive)
    assertEquals(grid, fresh.viewport)

  @Test def `the history depth is the host's`(): Unit =
    assertEquals(10, model().maxScroll)

  @Test def `scrolling back with no history yet keeps the viewport's shape`(): Unit =
    val scrolled = model().scrolledBy(2)
    assertFalse(scrolled.isLive)
    assertEquals(2, scrolled.missingHistory)
    assertTrue(scrolled.row(0).cells.isEmpty)
    assertEquals(grid.head, scrolled.row(2))

  @Test def `scrolling clamps to the history the host has`(): Unit =
    assertEquals(10, model().scrolled(999).scrollOffset)

  @Test def `scrolling never goes below the live grid`(): Unit =
    assertEquals(0, model().scrolledBy(-5).scrollOffset)

  @Test def `a history page fills the rows above the grid, oldest first`(): Unit =
    val paged = model().scrolledBy(2).withHistory(Vector(row("newest"), row("older")))
    assertEquals(0, paged.missingHistory)
    assertEquals("older", paged.rowText(0))
    assertEquals("newest", paged.rowText(1))

  @Test def `output while scrolled back keeps the reader where they were`(): Unit =
    val paged = model().scrolledBy(2).withHistory(Vector(row("newest"), row("older")))
    val grown = paged.withFrame(Frame.from(snapshot(grid, historyRows = 13)))
    assertEquals(paged.scrollOffset + 3, grown.scrollOffset)
    assertTrue(grown.newOutput)

  @Test def `output while live does not scroll or mark`(): Unit =
    val grown = model().withFrame(Frame.from(snapshot(grid, historyRows = 11)))
    assertTrue(grown.isLive)
    assertFalse(grown.newOutput)

  @Test def `returning to the bottom clears the mark`(): Unit =
    val scrolled = model().scrolledBy(2).withFrame(Frame.from(snapshot(grid, historyRows = 13)))
    assertTrue(scrolled.newOutput)
    assertTrue(scrolled.toLive.isLive)
    assertFalse(scrolled.toLive.newOutput)

  @Test def `a partial range is copied with its trailing blanks trimmed`(): Unit =
    val copy = ScreenModel.from(snapshot(Vector(row("hello   ")), historyRows = 0))
    assertEquals("ello", copy.text(Selection(CellPosition(0, 1), CellPosition(0, 4))))

  @Test def `several rows are copied in reading order, each trimmed`(): Unit =
    val copy = ScreenModel.from(snapshot(Vector(row("hello   "), row("world")), historyRows = 0))
    assertEquals("hello\nworl", copy.text(Selection(CellPosition(0, 0), CellPosition(1, 3))))

  @Test def `a wide character is copied once, not twice`(): Unit =
    val wide = Row(Vector(cell("あ", CellWidth.Wide), cell("", CellWidth.Continuation), cell("b")))
    val copy = ScreenModel.from(snapshot(Vector(wide), historyRows = 0))
    assertEquals("あb", copy.text(Selection(CellPosition(0, 0), CellPosition(0, 2))))

  @Test def `a one-cell selection copies the cell under it`(): Unit =
    val copy = ScreenModel.from(snapshot(Vector(row("world")), historyRows = 0))
    assertEquals("r", copy.text(Selection.at(CellPosition(0, 2))))

  @Test def `a row past the viewport reads as empty`(): Unit =
    assertTrue(model().row(99).cells.isEmpty)
