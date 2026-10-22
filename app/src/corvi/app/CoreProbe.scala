package corvi.app

import corvi.contract.*
import corvi.renderer.{CellPosition, Layout, ScreenModel, Selection}
import corvi.ui.{Chords, Ending, Selection as TabSelection, ShellState, TerminalWindow, WindowId}
import java.util.concurrent.atomic.AtomicInteger

/** The pure core's regression suite: state transitions, the screen model, layout and hit-testing, and the chord matrix — no toolkit, no
  * host, no process. Everything here is a value in and a value out, so it runs in a second and needs no display.
  *
  * ./mill -i app.runMain corvi.app.CoreProbe
  */
object CoreProbe:

  def main(args: Array[String]): Unit =
    val reporter = Reporter()
    stateChecks(reporter)
    screenChecks(reporter)
    layoutChecks(reporter)
    chordChecks(reporter)
    println(if reporter.failures == 0 then "all core checks passed" else s"${reporter.failures} core check(s) failed")
    java.lang.System.exit(if reporter.failures == 0 then 0 else 1)

  // --- the shell's state machine -----------------------------------------------------------------------------------------------------

  private def stateChecks(reporter: Reporter): Unit =
    val report = reporter.report

    // Opening returns the id it minted, and selects the new window.
    val (twoWindows, first) = ShellState.initial.opened("one", session("s1"))
    val (threeWindows, second) = twoWindows.opened("two", session("s2"))
    val (all, third) = threeWindows.opened("three", session("s3"))
    report(
      "opening returns the id it minted, in order",
      Vector(first, second, third).map(_.value) == Vector(0, 1, 2),
      s"${all.windows.map(_.id.value)}"
    )
    report("and selects the window it opened", all.selection == TabSelection.Window(third), s"${all.selection}")

    // A bell, and the ways it is taken down.
    val belled = all.selected(TabSelection.Window(first)).activity(second)
    report(
      "output in a background window bells it",
      belled.window(second).exists(_.activity == TerminalWindow.Activity.Unseen),
      s"${belled.window(second).map(_.activity)}"
    )
    report(
      "selecting it clears the bell",
      belled.selected(TabSelection.Window(second)).window(second).exists(_.activity == TerminalWindow.Activity.Quiet),
      ""
    )
    report(
      "output in the selected window is not a bell, and changes nothing",
      all.activity(third) eq all,
      ""
    )
    report("a missing window has nothing to bell", all.activity(WindowId(99)) eq all, "")
    report("selecting the window that is already on screen changes nothing", all.selected(TabSelection.Window(third)) eq all, "")
    report("so does selecting a window that is not there", all.selected(TabSelection.Window(WindowId(42))) eq all, "")

    // Closing: the selection goes where the strip says it should.
    report(
      "closing the selected first window selects the one that took its place",
      all.selected(TabSelection.Window(first)).closed(first).selection == TabSelection.Window(second),
      s"${all.selected(TabSelection.Window(first)).closed(first).selection}"
    )
    report(
      "closing the selected middle window selects the next one",
      all.selected(TabSelection.Window(second)).closed(second).selection == TabSelection.Window(third),
      s"${all.selected(TabSelection.Window(second)).closed(second).selection}"
    )
    report(
      "closing the selected last window falls back to the previous one",
      all.selected(TabSelection.Window(third)).closed(third).selection == TabSelection.Window(second),
      s"${all.selected(TabSelection.Window(third)).closed(third).selection}"
    )
    val only = ShellState.initial.opened("one", session("s1"))._1
    report("closing the only window shows the Overview", only.closed(WindowId(0)).selection == TabSelection.Overview, "")
    report(
      "closing a window you are not looking at leaves the selection alone",
      all.closed(second).selection == TabSelection.Window(third),
      ""
    )
    report("closing a window that is not there changes nothing", all.closed(WindowId(99)) eq all, "")

    // Ending: how it ended is kept, and an ended window stops asking for attention.
    val exited = all.ended(second, Ended(code = Some(1), closed = false, reason = None))
    report(
      "an ended window is quiet and keeps its code",
      exited
        .window(second)
        .exists(window =>
          window.state == TerminalWindow.State.Ended && window.activity == TerminalWindow.Activity.Quiet &&
            window.ending == Some(Ending.Exited(Some(1)))
        ),
      s"${exited.window(second).flatMap(_.ending)}"
    )
    report(
      "and its label carries the code",
      exited.window(second).exists(_.display.endsWith("(exit 1)")),
      s"${exited.window(second).map(_.display)}"
    )
    report(
      "a client close is not marked as an exit",
      all
        .ended(second, Ended(code = Some(0), closed = true, reason = None))
        .window(second)
        .exists(window => window.ending == Some(Ending.Closed) && window.endingDetail.isEmpty),
      ""
    )
    report(
      "a reader failure carries its reason",
      all
        .ended(second, Ended(code = None, closed = false, reason = Some("the pty went away")))
        .window(second)
        .flatMap(_.endingDetail)
        .exists(_.contains("the pty went away")),
      ""
    )
    report(
      "an ended background window does not bell for later output",
      exited.activity(second).window(second).exists(_.activity == TerminalWindow.Activity.Quiet),
      ""
    )
    report("ending a window that is not there changes nothing", all.ended(WindowId(99), Ended(None, false, None)) eq all, "")

    // Moving: the selection is kept by window, so it follows the tab that moved.
    val movedForward = all.moved(first, third)
    report(
      "a drop carries the window to the other position",
      movedForward.windows.map(_.id) == Vector(second, third, first),
      s"${movedForward.windows.map(_.id.value)}"
    )
    report("and the selection follows the window that moved", movedForward.selection == TabSelection.Window(third), "")
    report(
      "a drop the other way brings it back to the front",
      all.moved(third, first).windows.map(_.id) == Vector(third, first, second),
      s"${all.moved(third, first).windows.map(_.id.value)}"
    )
    report("dropping a window on itself changes nothing", all.moved(second, second) eq all, "")
    report("dropping a window that is not there changes nothing", all.moved(WindowId(99), first) eq all, "")

  // --- the screen model -------------------------------------------------------------------------------------------------------------

  private def screenChecks(reporter: Reporter): Unit =
    val report = reporter.report
    val grid = Vector(row("live-0"), row("live-1"), row("live-2"))
    val model = ScreenModel.from(snapshot(grid, historyRows = 10))

    report("a fresh model is live and shows the grid", model.isLive && model.viewport == grid, s"${model.scrollOffset}")
    report("and leaves room for the host's history", model.maxScroll == 10, s"${model.maxScroll}")

    // Scrolled back with nothing fetched yet: the viewport keeps its shape, blank rows included.
    val scrolled = model.scrolledBy(2)
    report(
      "scrolling back with no history yet leaves blank rows above the grid",
      !scrolled.isLive && scrolled.missingHistory == 2 && scrolled.row(0).cells.isEmpty && scrolled.row(2) == grid.head,
      s"missing ${scrolled.missingHistory}"
    )
    report("scrolling clamps to the history the host has", model.scrolled(999).scrollOffset == 10, s"${model.scrolled(999).scrollOffset}")
    report("and never goes below the live grid", model.scrolledBy(-5).scrollOffset == 0, s"${model.scrolledBy(-5).scrollOffset}")

    // A page arrives: the newest row is nearest the grid, so the viewport reads oldest-first.
    val paged = scrolled.withHistory(Vector(row("newest"), row("older")))
    report(
      "a history page fills the rows above the grid, oldest first",
      paged.missingHistory == 0 && paged.rowText(0) == "older" && paged.rowText(1) == "newest",
      s"${paged.rowText(0)} / ${paged.rowText(1)}"
    )

    // Output while the reader is scrolled back: the offset grows so the text does not slide, and the mark is set.
    val grown = paged.withFrame(Frame.from(snapshot(grid, historyRows = 13)))
    report(
      "output while scrolled back keeps the reader where they were",
      grown.scrollOffset == paged.scrollOffset + 3 && grown.newOutput,
      s"${paged.scrollOffset} -> ${grown.scrollOffset}"
    )
    report(
      "output while live does not scroll or mark",
      !model.withFrame(Frame.from(snapshot(grid, historyRows = 11))).newOutput,
      ""
    )
    report("returning to the bottom clears the mark", grown.toLive.isLive && !grown.toLive.newOutput, "")

    // Copy: reading order, trailing blanks gone, and a wide character once.
    val wide = Row(
      Vector(
        cell("あ", CellWidth.Wide),
        cell("", CellWidth.Continuation),
        cell("b"),
        cell(" "),
        cell(" ")
      )
    )
    val copyModel = ScreenModel.from(snapshot(Vector(row("hello   "), row("world"), wide), historyRows = 0))
    report(
      "copying a partial range strips the trailing blanks",
      copyModel.text(Selection(CellPosition(0, 1), CellPosition(0, 4))) == "ello",
      s"'${copyModel.text(Selection(CellPosition(0, 1), CellPosition(0, 4)))}'"
    )
    report(
      "copying several rows joins them in reading order, each with its blanks trimmed",
      copyModel.text(Selection(CellPosition(0, 0), CellPosition(1, 3))) == "hello\nworl",
      s"'${copyModel.text(Selection(CellPosition(0, 0), CellPosition(1, 3)))}'"
    )
    report(
      "a wide character is copied once, not twice",
      copyModel.text(Selection(CellPosition(2, 0), CellPosition(2, 2))) == "あb",
      s"'${copyModel.text(Selection(CellPosition(2, 0), CellPosition(2, 2)))}'"
    )
    report(
      "a one-cell selection copies the cell under it",
      copyModel.text(Selection.at(CellPosition(1, 2))) == "r",
      s"'${copyModel.text(Selection.at(CellPosition(1, 2)))}'"
    )

  // --- layout and selection ---------------------------------------------------------------------------------------------------------

  private def layoutChecks(reporter: Reporter): Unit =
    val report = reporter.report
    val layout = Layout(cellWidth = 8.0, cellHeight = 16.0)

    report("cells fit whole columns and rows, at least one", layout.colsFor(80.0) == 10 && layout.rowsFor(40.0) == 2, "")
    report("a tiny or empty area still has one cell", layout.colsFor(0.0) == 1 && layout.rowsFor(3.0) == 1, "")
    report("a point left of the grid clamps to the first cell", layout.colAt(-4.0, 10) == 0 && layout.rowAt(-1.0, 5) == 0, "")
    report("a point past the grid clamps to the last cell", layout.colAt(10_000.0, 10) == 9 && layout.rowAt(10_000.0, 5) == 4, "")
    report("x maps to the cell that contains it", layout.colAt(8.0, 10) == 1 && layout.colAt(15.9, 10) == 1, "")

    val forward = Selection(CellPosition(0, 2), CellPosition(2, 3))
    val reversed = Selection(CellPosition(2, 3), CellPosition(0, 2))
    report("a drag in either direction is the same selection", forward.ordered == reversed.ordered, "")
    report("a click is an empty selection", Selection.at(CellPosition(1, 1)).isEmpty, "")
    report(
      "contains follows the rectangle's edges",
      forward.contains(0, 2) && forward.contains(0, 9) && !forward.contains(0, 1) &&
        forward.contains(1, 0) && forward.contains(2, 3) && !forward.contains(2, 4) && !forward.contains(3, 0),
      ""
    )
    report(
      "a one-row selection has both edges",
      Selection(CellPosition(1, 2), CellPosition(1, 4)).contains(1, 2) &&
        Selection(CellPosition(1, 2), CellPosition(1, 4)).contains(1, 4) &&
        !Selection(CellPosition(1, 2), CellPosition(1, 4)).contains(1, 5),
      ""
    )
    report(
      "dragging moves the focus, not the anchor",
      reversed.draggedTo(CellPosition(1, 1)) == Selection(CellPosition(2, 3), CellPosition(1, 1)),
      ""
    )

  // --- chords -----------------------------------------------------------------------------------------------------------------------

  private def chordChecks(reporter: Reporter): Unit =
    val report = reporter.report
    val now = 1_000_000L

    val (armed, armedOutcome) = Chords.press(Chords.initial, Chords.Key.Prefix, mac = true, now)
    report("ctrl-b arms the prefix", armedOutcome == Chords.Outcome.PrefixArmed && armed.waiting(now), s"$armedOutcome")
    val (_, selected) = Chords.press(armed, Chords.Key.Digit(1), mac = true, now + 1)
    report("and a digit selects by its 0-based position", selected == Chords.Outcome.Select(1), s"$selected")
    val (_, created) = Chords.press(armed, Chords.Key.Create, mac = true, now + 1)
    report("and c opens a window", created == Chords.Outcome.Open, s"$created")
    val (expiredState, expired) = Chords.press(armed, Chords.Key.Digit(1), mac = true, now + Chords.prefixNanos + 1)
    report(
      "a digit after the prefix expired belongs to the terminal",
      expired == Chords.Outcome.Passed && !expiredState.waiting(now),
      s"$expired"
    )
    val (_, outOfRange) = Chords.press(armed, Chords.Key.Digit(9), mac = true, now + 1)
    report(
      "an out-of-range digit is passed on, and the strip has no window for it",
      outOfRange == Chords.Outcome.Select(9) && ShellState.initial.opened("one", session("s1"))._1.windows.lift(9).isEmpty,
      s"$outOfRange"
    )

    // The typed companion: a key the strip took is swallowed once, a key it passed is not.
    val (afterPrefix, _) = Chords.typed(armed)
    report("the typed companion of a taken key is swallowed once", Chords.typed(armed)._2 && !afterPrefix.swallowTyped, "")
    report(
      "a passed key's companion belongs to the terminal",
      !Chords.typed(Chords.press(Chords.initial, Chords.Key.Other, mac = true, now)._1)._2,
      ""
    )

    // The modifier matrix lives in the one adapter that reads a KeyEvent; constructing one needs no started toolkit.
    def key(
        code: javafx.scene.input.KeyCode,
        shift: Boolean = false,
        control: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false
    ) =
      new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta)
    def mapped(event: javafx.scene.input.KeyEvent, mac: Boolean): Chords.Key = Chords.Key.from(event, mac)
    def code(name: String): javafx.scene.input.KeyCode = javafx.scene.input.KeyCode.valueOf(name)
    report("⌘T opens on macOS", mapped(key(code("T"), meta = true), true) == Chords.Key.Open, "")
    report("ctrl-alt-t opens elsewhere", mapped(key(code("T"), control = true, alt = true), false) == Chords.Key.Open, "")
    report(
      "shift is not part of any chord",
      mapped(key(code("T"), shift = true, meta = true), true) == Chords.Key.Other &&
        mapped(key(code("B"), shift = true, control = true), true) == Chords.Key.Other,
      ""
    )
    report("ctrl-b is the prefix on either platform", mapped(key(code("B"), control = true), false) == Chords.Key.Prefix, "")
    report("a plain key is what the terminal gets", mapped(key(code("C")), true) == Chords.Key.Create, "")
    report(
      "a plain digit names a position, and 9 is not one",
      mapped(key(code("DIGIT0")), true) == Chords.Key.Digit(0) &&
        mapped(key(code("DIGIT8")), true) == Chords.Key.Digit(8) &&
        mapped(key(code("DIGIT9")), true) == Chords.Key.Other,
      ""
    )

  // --- fixtures ---------------------------------------------------------------------------------------------------------------------

  private def session(id: String): SessionId = SessionId(id)

  private def cell(text: String, width: CellWidth = CellWidth.Narrow): Cell =
    Cell(text, width, None, None, bold = false, italic = false, underline = false, inverse = false)

  private def row(text: String): Row = Row(text.map(character => cell(character.toString)).toVector)

  private def snapshot(rows: Vector[Row], historyRows: Int): Snapshot =
    Snapshot(
      id = session("s1"),
      size = TermSize(cols = rows.map(_.cells.size).maxOption.getOrElse(0), rows = rows.size),
      rows = rows,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = historyRows,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private final class Reporter:
    private val failed = AtomicInteger(0)

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then
        val _ = failed.incrementAndGet()
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(2).foreach(line => println(s"      | $line"))

    def failures: Int = failed.get()
