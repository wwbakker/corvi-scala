package corvi.renderer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Falling a pixel into a cell, and a drag into a selection: the two places where the renderer's arithmetic meets the pointer. */
final class LayoutSelectionTest:

  private val layout: Layout = Layout(cellWidth = 8.0, cellHeight = 16.0)

  @Test def `cells fit whole columns and rows`(): Unit =
    assertEquals(10, layout.colsFor(80.0))
    assertEquals(2, layout.rowsFor(40.0))

  @Test def `a tiny or empty area still has one cell`(): Unit =
    assertEquals(1, layout.colsFor(0.0))
    assertEquals(1, layout.rowsFor(3.0))

  @Test def `a point left of the grid clamps to the first cell`(): Unit =
    assertEquals(0, layout.colAt(-4.0, 10))
    assertEquals(0, layout.rowAt(-1.0, 5))

  @Test def `a point past the grid clamps to the last cell`(): Unit =
    assertEquals(9, layout.colAt(10_000.0, 10))
    assertEquals(4, layout.rowAt(10_000.0, 5))

  @Test def `x maps to the cell that contains it`(): Unit =
    assertEquals(1, layout.colAt(8.0, 10))
    assertEquals(1, layout.colAt(15.9, 10))

  @Test def `a drag in either direction is the same selection`(): Unit =
    val forward = Selection(CellPosition(0, 2), CellPosition(2, 3))
    val reversed = Selection(CellPosition(2, 3), CellPosition(0, 2))
    assertEquals(forward.ordered, reversed.ordered)

  @Test def `a click is an empty selection`(): Unit =
    assertTrue(Selection.at(CellPosition(1, 1)).isEmpty)

  @Test def `contains follows the rectangle's edges`(): Unit =
    val selection = Selection(CellPosition(0, 2), CellPosition(2, 3))
    assertTrue(selection.contains(0, 2))
    assertTrue(selection.contains(0, 9))
    assertFalse(selection.contains(0, 1))
    assertTrue(selection.contains(1, 0))
    assertTrue(selection.contains(2, 3))
    assertFalse(selection.contains(2, 4))
    assertFalse(selection.contains(3, 0))

  @Test def `a one-row selection has both edges`(): Unit =
    val selection = Selection(CellPosition(1, 2), CellPosition(1, 4))
    assertTrue(selection.contains(1, 2))
    assertTrue(selection.contains(1, 4))
    assertFalse(selection.contains(1, 5))

  @Test def `dragging moves the focus, not the anchor`(): Unit =
    val reversed = Selection(CellPosition(2, 3), CellPosition(0, 2))
    assertEquals(Selection(CellPosition(2, 3), CellPosition(1, 1)), reversed.draggedTo(CellPosition(1, 1)))
