package corvi.ui

import corvi.contract.{Ended, SessionId}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The shell's whole model: one pure transition per gesture, and the edges the strip depends on (which window a close selects, when a bell
  * is raised, what an ending records). No toolkit: a state in, a state out.
  */
final class ShellStateTest:

  private def session(id: String): SessionId = SessionId(id)

  /** Three windows, the last one selected: the arrangement most of these transitions are about. */
  private def threeWindows: (ShellState, WindowId, WindowId, WindowId) =
    val (withOne, first) = ShellState.initial.opened("one", session("s1"))
    val (withTwo, second) = withOne.opened("two", session("s2"))
    val (withThree, third) = withTwo.opened("three", session("s3"))
    (withThree, first, second, third)

  // --- opening -----------------------------------------------------------------------------------------------------------------------

  @Test def `opening returns the ids it minted, in order`(): Unit =
    val (state, first, second, third) = threeWindows
    assertEquals(Vector(0, 1, 2), Vector(first, second, third).map(_.value))
    assertEquals(Vector(first, second, third), state.windows.map(_.id))

  @Test def `opening selects the window it opened`(): Unit =
    val (_, _, _, third) = threeWindows
    assertEquals(Selection.Window(third), threeWindows._1.selection)

  // --- bells -------------------------------------------------------------------------------------------------------------------------

  @Test def `output in a background window bells it`(): Unit =
    val (state, first, second, _) = threeWindows
    val belled = state.selected(Selection.Window(first)).activity(second)
    assertEquals(Some(TerminalWindow.Activity.Unseen), belled.window(second).map(_.activity))

  @Test def `selecting a window clears its bell`(): Unit =
    val (state, first, second, _) = threeWindows
    val belled = state.selected(Selection.Window(first)).activity(second)
    assertEquals(Some(TerminalWindow.Activity.Quiet), belled.selected(Selection.Window(second)).window(second).map(_.activity))

  @Test def `output in the selected window is not a bell, and changes nothing`(): Unit =
    val (state, _, _, third) = threeWindows
    assertSame(state, state.activity(third))

  @Test def `output for a window that is not there changes nothing`(): Unit =
    val (state, _, _, _) = threeWindows
    assertSame(state, state.activity(WindowId(99)))

  @Test def `selecting the window already on screen changes nothing`(): Unit =
    val (state, _, _, third) = threeWindows
    assertSame(state, state.selected(Selection.Window(third)))

  @Test def `selecting a window that is not there changes nothing`(): Unit =
    val (state, _, _, _) = threeWindows
    assertSame(state, state.selected(Selection.Window(WindowId(42))))

  // --- closing -----------------------------------------------------------------------------------------------------------------------

  @Test def `closing the selected first window selects the one that took its place`(): Unit =
    val (state, first, second, _) = threeWindows
    assertEquals(Selection.Window(second), state.selected(Selection.Window(first)).closed(first).selection)

  @Test def `closing the selected middle window selects the next one`(): Unit =
    val (state, _, second, third) = threeWindows
    assertEquals(Selection.Window(third), state.selected(Selection.Window(second)).closed(second).selection)

  @Test def `closing the selected last window falls back to the previous one`(): Unit =
    val (state, _, second, third) = threeWindows
    assertEquals(Selection.Window(second), state.selected(Selection.Window(third)).closed(third).selection)

  @Test def `closing the only window shows the Overview`(): Unit =
    val (only, first) = ShellState.initial.opened("one", session("s1"))
    assertEquals(Selection.Overview, only.closed(first).selection)

  @Test def `closing a window that is not selected leaves the selection alone`(): Unit =
    val (state, _, second, third) = threeWindows
    assertEquals(Selection.Window(third), state.closed(second).selection)

  @Test def `closing a window that is not there changes nothing`(): Unit =
    val (state, _, _, _) = threeWindows
    assertSame(state, state.closed(WindowId(99)))

  // --- ending ------------------------------------------------------------------------------------------------------------------------

  @Test def `an ended window is quiet and keeps its code`(): Unit =
    val (state, _, second, _) = threeWindows
    val ended = state.ended(second, Ended(code = Some(1), closed = false, reason = None))
    val window = ended.window(second)
    assertEquals(Some(TerminalWindow.State.Ended), window.map(_.state))
    assertEquals(Some(TerminalWindow.Activity.Quiet), window.map(_.activity))
    assertEquals(Some(Ending.Exited(Some(1))), window.flatMap(_.ending))

  @Test def `an ended window's label carries the code`(): Unit =
    val (state, _, second, _) = threeWindows
    val ended = state.ended(second, Ended(code = Some(1), closed = false, reason = None))
    assertEquals(Some("two (exit 1)"), ended.window(second).map(_.display))

  @Test def `a client close is not marked as an exit`(): Unit =
    val (state, _, second, _) = threeWindows
    val ended = state.ended(second, Ended(code = Some(0), closed = true, reason = None))
    assertEquals(Some(Ending.Closed), ended.window(second).flatMap(_.ending))
    assertEquals(Some("two"), ended.window(second).map(_.display))
    assertEquals(None, ended.window(second).flatMap(_.endingDetail))

  @Test def `a reader failure carries its reason`(): Unit =
    val (state, _, second, _) = threeWindows
    val ended = state.ended(second, Ended(code = None, closed = false, reason = Some("the pty went away")))
    assertEquals(Some(Ending.Failed("the pty went away")), ended.window(second).flatMap(_.ending))
    assertTrue(ended.window(second).flatMap(_.endingDetail).exists(_.contains("the pty went away")))

  @Test def `an ended background window does not bell for later output`(): Unit =
    val (state, _, second, _) = threeWindows
    val ended = state.ended(second, Ended(None, closed = false, reason = None))
    assertEquals(Some(TerminalWindow.Activity.Quiet), ended.activity(second).window(second).map(_.activity))

  @Test def `ending a window that is not there changes nothing`(): Unit =
    val (state, _, _, _) = threeWindows
    assertSame(state, state.ended(WindowId(99), Ended(None, closed = false, reason = None)))

  // --- reordering --------------------------------------------------------------------------------------------------------------------

  @Test def `a drop carries the window to the other position`(): Unit =
    val (state, first, second, third) = threeWindows
    assertEquals(Vector(second, third, first), state.moved(first, third).windows.map(_.id))

  @Test def `the selection follows the window that moved`(): Unit =
    val (state, first, second, third) = threeWindows
    val moved = state.moved(first, third)
    assertEquals(Selection.Window(third), moved.selection)
    assertEquals(Vector(second, third, first), moved.windows.map(_.id))

  @Test def `a drop the other way brings the window to the front`(): Unit =
    val (state, first, second, third) = threeWindows
    assertEquals(Vector(third, first, second), state.moved(third, first).windows.map(_.id))

  @Test def `dropping a window on itself changes nothing`(): Unit =
    val (state, _, second, _) = threeWindows
    assertSame(state, state.moved(second, second))

  @Test def `dropping a window that is not there changes nothing`(): Unit =
    val (state, first, _, _) = threeWindows
    assertSame(state, state.moved(WindowId(99), first))
