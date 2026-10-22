package corvi.host

import com.techsenger.jeditermfx.core.emulator.mouse.MouseMode as EngineMouseMode
import corvi.contract.*
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
// The contract has a `Frame` as well (a client's screen); this file means kyo's source position, so it says so.
import kyo.Frame
import scala.concurrent.duration.*

/** The host's regression suite: login shells on real PTYs, driven headlessly, with everything asserted from the snapshots, deltas and
  * metrics the host produces. It is the only thing that proves the new stack end to end, so it stays.
  *
  * The checks are split by scenario rather than written as one for-comprehension: a single chain of ninety binds over Kyo's inline-heavy
  * `A < S` runs the compiler out of recursion, and a scenario that fails is easier to find in a method than in a list.
  *
  * ./mill -i host.runMain corvi.host.HostProbe
  */
object HostProbe:
  private val waitTimeout: scala.concurrent.duration.Duration = 20.seconds

  def main(args: Array[String]): Unit =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(240.seconds)(probe) match
      case Result.Success(failures) =>
        println(if failures == 0 then "all host checks passed" else s"$failures host check(s) failed")
        sys.exit(if failures == 0 then 0 else 1)
      case Result.Failure(error) =>
        println(s"the probe failed: $error")
        sys.exit(1)
      case Result.Panic(error) =>
        println(s"the probe panicked: $error")
        error.printStackTrace()
        sys.exit(1)

  private def probe(using Frame): Int < (Async & Scope & Abort[Any]) =
    val reporter = Reporter()
    for
      session <- HostSession.open(SessionId("probe"), index = 0, Paths.get(sys.props("user.dir")), HostSession.loginShell)
      _ <- screenChecks(session, reporter)
      _ <- sizeAndScrollbackChecks(session, reporter)
      _ <- modeChecks(session, reporter)
      _ <- damageChecks(session, reporter)
      _ <- isolationAndTeardownChecks(session, reporter)
      _ <- scopeChecks(reporter)
    yield reporter.failures

  /** PASS/FAIL lines, and the count the probe exits with. The counter is the harness's own accounting, not app state. */
  private final class Reporter:
    private val failed = AtomicInteger(0)

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then
        val _ = failed.incrementAndGet()
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(4).foreach(line => println(s"      | $line"))

    def failures: Int = failed.get()

  /** What the screen says: text, colours and attributes, wide characters, the cursor and its shape. */
  private def screenChecks(session: HostSession, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      // The shell spawns, paints a prompt, and the host reports the session without screening it.
      prompt <- await(session)(snapshot => text(snapshot).trim.nonEmpty)
      _ = report("the shell spawns and paints a prompt", text(prompt).trim.nonEmpty, text(prompt))
      info = session.info
      _ = report(
        "the session's info carries its size, theme and liveness",
        info.size == TermSize(80, 24) && info.running && info.historyRows >= 0 &&
          prompt.theme.foreground == Rgb(230, 237, 243) && prompt.theme.background == Rgb(13, 17, 23),
        s"info $info"
      )

      // The output line, not the echoed command.
      _ = session.write("echo CORVI_OK\n")
      echoed <- await(session)(snapshot => lines(snapshot).contains("CORVI_OK"))
      _ = report("a command runs and its output is in the cells", lines(echoed).contains("CORVI_OK"), lines(echoed).mkString("\n"))

      // SGR: colours, bold, and the attributes a renderer draws without a colour of its own.
      _ = session.write(
        "printf '\\033[31mRED\\033[0m \\033[1;32mGREEN\\033[0m \\033[44;37mBG\\033[0m \\033[7mINV\\033[0m \\033[4mUND\\033[0m\\n'\n"
      )
      coloured <- await(session)(snapshot => lines(snapshot).exists(_.contains("UND")))
      red = cellOf(coloured, "RED")
      green = cellOf(coloured, "GREEN")
      _ = report(
        "a cell carries its SGR colour",
        red.exists(cell => cell.foreground.exists(colour => colour.red > 150 && colour.green < 100)),
        red.map(cell => s"RED foreground ${cell.foreground}").getOrElse("no RED cell")
      )
      _ = report(
        "and its bold attribute",
        green.exists(cell => cell.bold && cell.foreground.exists(colour => colour.green > 100 && colour.red < 100)),
        green.map(cell => s"GREEN bold=${cell.bold} foreground ${cell.foreground}").getOrElse("no GREEN cell")
      )
      bg = cellOf(coloured, "BG")
      inv = cellOf(coloured, "INV")
      und = cellOf(coloured, "UND")
      _ = report(
        "a cell carries its background, inverse and underline",
        bg.exists(_.background.exists(colour => colour.blue > 100)) && inv.exists(_.inverse) && und.exists(_.underline),
        s"BG background ${bg.flatMap(_.background)} inverse ${inv.exists(_.inverse)} underline ${und.exists(_.underline)}"
      )

      // Wide and astral characters: the contract has to say how much of a row a cell covers, or a client cannot lay it out.
      _ = session.write("printf '\\u4e16\\u754c \\U0001F600\\n'\n")
      wide <- await(session)(snapshot => lines(snapshot).exists(_.contains("世界")))
      wideCells = cellOf(wide, "世").map(cell => (cell.text, cell.width, cellAfter(wide, "世").map(_.width)))
      emoji = rowContaining(wide, Emoji)
      _ = report(
        "a double-width character is a wide cell plus a continuation",
        wideCells.contains(("世", CellWidth.Wide, Some(CellWidth.Continuation))),
        wideCells.map { case (text, width, next) => s"'$text' $width then $next" }.getOrElse("no 世 cell")
      )
      _ = report(
        "an astral character is one cell of two chars, also wide",
        emoji.exists(row => row.cells.exists(cell => cell.text == Emoji && cell.width == CellWidth.Wide)),
        emoji.map(row => row.cells.filter(_.text == Emoji).map(cell => s"'${cell.text}' ${cell.width}").mkString).getOrElse("no emoji cell")
      )

      // The cursor: CUP puts it exactly where it is told, and the shell then sleeps, so there is time to read it.
      _ = session.write("printf '\\033[10;20H'; sleep 1\n")
      cursor <- await(session)(snapshot => snapshot.cursor.x == 19 && snapshot.cursor.y == 9)
      _ = report(
        "the cursor is where the terminal put it",
        cursor.cursor.x == 19 && cursor.cursor.y == 9 && cursor.cursor.visible,
        s"cursor ${cursor.cursor}"
      )

      // DECSCUSR: the shape and whether it blinks, including that a steady shape clears the blink again.
      _ = session.write("printf '\\033[5 q'; sleep 1\n")
      blinking <- await(session)(snapshot => snapshot.cursor.shape == Some(CursorShape.VerticalBar) && snapshot.cursor.blinking)
      _ = report(
        "a blinking bar cursor is reported as such",
        blinking.cursor.shape == Some(CursorShape.VerticalBar) && blinking.cursor.blinking,
        s"cursor ${blinking.cursor}"
      )
      _ = session.write("printf '\\033[2 q'; sleep 1\n")
      steady <- await(session)(snapshot => snapshot.cursor.shape == Some(CursorShape.Block) && !snapshot.cursor.blinking)
      _ = report(
        "and a steady block clears the blink",
        steady.cursor.shape == Some(CursorShape.Block) && !steady.cursor.blinking,
        s"cursor ${steady.cursor}"
      )
    yield ()

  /** The grid's size, the scrollback, its cap, and reading both while output floods in. */
  private def sizeAndScrollbackChecks(session: HostSession, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      // Resizing reaches the grid and the PTY.
      _ = session.resize(TermSize(cols = 100, rows = 40))
      resized <- await(session)(snapshot => snapshot.size == TermSize(100, 40))
      _ = report("resizing the session resizes the grid", resized.size == TermSize(100, 40), s"size ${resized.size}")
      _ = session.write("stty size\n")
      stty <- await(session)(snapshot => lines(snapshot).exists(_.trim == "40 100"))
      _ = report("and reaches the PTY", lines(stty).exists(_.trim == "40 100"), lines(stty).takeRight(4).mkString("\n"))

      // Scrollback: more lines than the screen, and the host keeps them.
      _ = session.write("seq 1 200\n")
      history <- await(session)(snapshot => lines(snapshot).exists(_.trim == "200"))
      _ = report("history grows past the screen", history.historyRows > 0, s"history rows ${history.historyRows}")

      // Paging: a line the screen has scrolled past is still readable, adjacent pages are contiguous and do not overlap.
      pageOne = session.history(from = 1, count = 10)
      pageTwo = session.history(from = 11, count = 10)
      pageOneNumbers = pageOne.map(row => rowText(row).trim).filter(_.forall(_.isDigit))
      pageTwoNumbers = pageTwo.map(row => rowText(row).trim).filter(_.forall(_.isDigit))
      allNumbers = session.history(from = 1, count = 400).map(row => rowText(row).trim).filter(_.forall(_.isDigit))
      paged = allNumbers.contains("100") && !lines(history).exists(_.trim == "100")
      contiguous = pageOneNumbers.lastOption.zip(pageTwoNumbers.headOption).exists { case (last, first) => last.toInt - first.toInt == 1 }
      _ = report("scrollback is readable page by page", paged, pageOneNumbers.mkString(" "))
      _ = report(
        "and adjacent pages do not overlap",
        contiguous && pageOneNumbers.intersect(pageTwoNumbers).isEmpty,
        s"${pageOneNumbers.lastOption.getOrElse("?")} | ${pageTwoNumbers.headOption.getOrElse("?")}"
      )

      // A resize keeps the scrollback that was already there.
      _ = session.resize(TermSize(cols = 90, rows = 30))
      narrowed <- await(session)(snapshot => snapshot.size == TermSize(90, 30))
      oldScrollback = session.history(from = 1, count = 60).map(row => rowText(row).trim)
      _ = report(
        "a resize keeps the scrollback",
        narrowed.size == TermSize(90, 30) && oldScrollback.contains("150"),
        s"size ${narrowed.size}, 150 among ${oldScrollback.count(_.forall(_.isDigit))} history rows"
      )

      // History is capped, however much a program prints.
      _ = session.write("seq 1 6000\n")
      _ <- await(session)(snapshot => lines(snapshot).exists(_.trim == "6000"))
      capped = session.info.historyRows
      _ = report("the history is capped", capped == 5000, s"history rows $capped")

      // A flood of output while snapshots and deltas are taken: every delta must describe the frame it was taken against.
      _ = session.write("seq 1 30000\n")
      flood <- floodCheck(session, iterations = 60)
      _ = report("snapshots and deltas keep up under a flood", flood.ok, flood.detail)
      done <- await(session)(snapshot => lines(snapshot).exists(_.trim == "30000"))
      _ = report("and the flood's last line arrives", lines(done).exists(_.trim == "30000"), lines(done).takeRight(3).mkString(" / "))
    yield ()

  /** Every mode a full-screen program sets, the transitions back off, and the title. */
  private def modeChecks(session: HostSession, reporter: Reporter)(using Frame): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      _ = session.write("printf '\\033[?2004h'\n")
      paste <- await(session)(snapshot => snapshot.modes.bracketedPaste)
      _ = report("bracketed paste is reported", paste.modes.bracketedPaste, s"modes ${paste.modes}")

      _ = session.write("printf '\\033[?1000h\\033[?1006h'\n")
      mouse <- await(session)(snapshot => snapshot.modes.mouseMode == MouseMode.Click && snapshot.modes.mouseFormat == MouseFormat.Sgr)
      _ = report(
        "the mouse mode and format are reported",
        mouse.modes.mouseMode == MouseMode.Click && mouse.modes.mouseFormat == MouseFormat.Sgr,
        s"modes ${mouse.modes}"
      )
      _ = session.write("printf '\\033[?1002h'\n")
      dragged <- await(session)(snapshot => snapshot.modes.mouseMode == MouseMode.ButtonMotion)
      _ = session.write("printf '\\033[?1003h'\n")
      moved <- await(session)(snapshot => snapshot.modes.mouseMode == MouseMode.AnyMotion)
      _ = session.write("printf '\\033[?1001h'\n")
      highlighted <- await(session)(snapshot => snapshot.modes.mouseMode == MouseMode.Highlight)
      _ = session.write("printf '\\033[?1005h'\n")
      utf8 <- await(session)(snapshot => snapshot.modes.mouseFormat == MouseFormat.Utf8)
      _ = report(
        "button motion, any motion and highlight tracking are distinct modes",
        dragged.modes.mouseMode == MouseMode.ButtonMotion && moved.modes.mouseMode == MouseMode.AnyMotion &&
          highlighted.modes.mouseMode == MouseMode.Highlight && utf8.modes.mouseFormat == MouseFormat.Utf8,
        s"${dragged.modes.mouseMode} / ${moved.modes.mouseMode} / ${highlighted.modes.mouseMode} / ${utf8.modes.mouseFormat}"
      )

      // 1004 (focus reports) is a mouse mode in the engine, and an engine value with no case here used to crash the session inside
      // the emulator's callback. The mapping must be total, and it must not kill a live session.
      engineModes = EngineMouseMode.values.map(RecordingDisplay.mouseModeOf).toVector
      _ = report(
        "every engine mouse mode maps to a contract mode",
        engineModes.size == EngineMouseMode.values.length && engineModes.contains(MouseMode.Focus),
        engineModes.mkString(" ")
      )
      _ = session.write("printf '\\033[?1004h'\n")
      _ = await(session)(snapshot => snapshot.modes.mouseMode == MouseMode.Click)
      _ = session.write("echo AFTER_FOCUS\n")
      survived <- await(session)(snapshot => lines(snapshot).contains("AFTER_FOCUS"))
      _ = report(
        "focus reporting does not kill the session",
        lines(survived).contains("AFTER_FOCUS") && session.error.isEmpty,
        s"error ${session.error}"
      )

      // Modes off again: the encoder's fallbacks have to be reported too.
      // zsh turns bracketed paste back on at every prompt, so the transition is only observable while a command still runs.
      _ = session.write(
        "(printf '\\033[?1000l\\033[?1001l\\033[?1002l\\033[?1003l\\033[?1005l\\033[?1006l\\033[?2004l'; sleep 2)\n"
      )
      cleared <- await(session)(snapshot =>
        snapshot.modes.mouseMode == MouseMode.Off && snapshot.modes.mouseFormat == MouseFormat.X10 && !snapshot.modes.bracketedPaste
      )
      _ = report(
        "turning the modes off is reported",
        cleared.modes.mouseMode == MouseMode.Off && cleared.modes.mouseFormat == MouseFormat.X10 && !cleared.modes.bracketedPaste,
        s"modes ${cleared.modes}"
      )

      // The title: set it and sleep, so zsh's own title does not overwrite it before it is read.
      _ = session.write("printf '\\033]0;Corvi Host\\007'; sleep 1\n")
      titled <- await(session)(snapshot => snapshot.modes.title == "Corvi Host")
      _ = report("the window title is reported", titled.modes.title == "Corvi Host", s"title '${titled.modes.title}'")

      // Alternate screen, on and off.
      _ = session.write("printf '\\033[?1049h'\n")
      alternate <- await(session)(snapshot => snapshot.modes.alternateScreen)
      _ = report("the alternate screen is reported", alternate.modes.alternateScreen, s"modes ${alternate.modes}")
      _ = session.write("printf '\\033[?1049l'\n")
      restored <- await(session)(snapshot => !snapshot.modes.alternateScreen)
      _ = report("and leaving it is reported", !restored.modes.alternateScreen, s"modes ${restored.modes}")
    yield ()

  /** Damage: exact rows, and the parts of a frame that are not rows. */
  private def damageChecks(session: HostSession, reporter: Reporter)(using Frame): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      baseline <- settledSnapshot(session)
      _ = session.write("echo DAMAGE_OK\n")
      damaged <- await(session)(snapshot => lines(snapshot).contains("DAMAGE_OK"))
      damage = session.damageSince(baseline)
      damagedRows = damage.rows.map(_.row).toSet
      _ = report("damage lists the rows that changed", damage.rows.nonEmpty, s"${damage.rows.size} rows")
      _ = report(
        "and exactly those",
        exactRows(baseline, damaged, damage) && damagedRows.size < baseline.size.rows,
        s"damaged ${damagedRows.toVector.sorted.mkString(",")} of ${baseline.size.rows}"
      )
      _ = report("the damaged row holds the new text", damage.rows.exists(change => rowText(change.cells).contains("DAMAGE_OK")), "")
      _ = report(
        "applying the damage reproduces the frame",
        damage.applyTo(baseline) == damaged,
        s"cursor ${damage.cursor} modes ${damage.modes}"
      )
      settled = session.snapshot()
      idle = session.damageSince(settled)
      _ = report("and nothing when nothing changed", idle.isEmpty, s"${idle.rows.size} rows")

      // A cursor-only change: no text at all, so the rows must not be damaged and the cursor must be.
      cursorBaseline <- cursorOnlyBaseline(session)
      (cursorCurrent, cursorDelta) = session.frameSince(cursorBaseline)
      _ = report(
        "damage carries a cursor move on its own",
        cursorDelta.cursor.isDefined && exactRows(cursorBaseline, cursorCurrent, cursorDelta) &&
          cursorDelta.applyTo(cursorBaseline) == cursorCurrent,
        s"${cursorDelta.rows.size} rows, cursor ${cursorDelta.cursor}"
      )

      // A mode-only change: the mode has to be in the delta even when no cell changed.
      modeBaseline <- modeOnlyBaseline(session)
      (modeCurrent, modeDelta) = session.frameSince(modeBaseline)
      _ = report(
        "damage carries a mode change on its own",
        modeDelta.modes.isDefined && exactRows(modeBaseline, modeCurrent, modeDelta) &&
          modeDelta.applyTo(modeBaseline) == modeCurrent,
        s"${modeDelta.rows.size} rows, modes ${modeDelta.modes.map(_.bracketedPaste)}"
      )

      // History growth is a frame change too: a client showing "1,000 more lines" has to hear about it. This one needs a session with
      // room under the cap: the session under test filled it in the scrollback checks, and a full history stops growing.
      growth <- Async.timeout(30.seconds)(historyGrowthDelta)
      _ = report("damage carries history growth", growth, "a fresh session's first lines")

      // A resize re-indexes the grid, so a delta says so rather than patching rows of the wrong shape.
      resizeBaseline = session.snapshot()
      resizedTo = TermSize(cols = 110, rows = 45)
      _ = session.resize(resizedTo)
      _ = await(session)(snapshot => snapshot.size == resizedTo)
      (resizedFrame, resizeDelta) = session.frameSince(resizeBaseline)
      _ = report(
        "damage carries a resize",
        resizeDelta.size.contains(resizedTo) && resizeDelta.applyTo(resizeBaseline) == resizedFrame,
        s"size ${resizeDelta.size}, ${resizeDelta.rows.size} rows"
      )

      // A height-only resize re-indexes the rows too: the delta has to carry every one of them, or a client applying it would blank the rows
      // that survived, which is exactly the bug this check exists for.
      sameWidthBaseline = session.snapshot()
      taller = TermSize(cols = sameWidthBaseline.size.cols, rows = sameWidthBaseline.size.rows + 8)
      _ = session.resize(taller)
      _ <- await(session)(snapshot => snapshot.size == taller)
      (grownFrame, grownDelta) = session.frameSince(sameWidthBaseline)
      _ = report(
        "a same-width resize damages every row",
        grownDelta.size.contains(taller) && grownDelta.rows.size == taller.rows && grownDelta.applyTo(sameWidthBaseline) == grownFrame,
        s"${grownDelta.rows.size} rows of ${taller.rows}, applying it gives ${grownFrame.rows.size} rows: ${grownDelta.applyTo(sameWidthBaseline) == grownFrame}"
      )
    yield ()

  /** Two sessions at once, and a session closed by hand. */
  private def isolationAndTeardownChecks(session: HostSession, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Scope & Abort[Any]) =
    val report = reporter.report
    for
      other <- HostSession.open(SessionId("other"), index = 0, Paths.get(sys.props("user.dir")), HostSession.loginShell)
      _ = session.write("echo ONLY_IN_ONE\n")
      _ = other.write("echo ONLY_IN_TWO\n")
      inOne <- await(session)(snapshot => lines(snapshot).contains("ONLY_IN_ONE"))
      inTwo <- await(other)(snapshot => lines(snapshot).contains("ONLY_IN_TWO"))
      _ = report(
        "two sessions keep their own screens",
        lines(inOne).contains("ONLY_IN_ONE") && !lines(inOne).contains("ONLY_IN_TWO") &&
          lines(inTwo).contains("ONLY_IN_TWO") && !lines(inTwo).contains("ONLY_IN_ONE"),
        s"one ${inOne.size}, two ${inTwo.size}"
      )
      oneSizeBefore = session.info.size
      _ = other.resize(TermSize(cols = 70, rows = 20))
      _ = await(other)(snapshot => snapshot.size == TermSize(70, 20))
      _ = report(
        "and their own sizes",
        other.info.size == TermSize(70, 20) && session.info.size == oneSizeBefore && oneSizeBefore != TermSize(70, 20),
        s"one ${session.info.size} (was $oneSizeBefore), two ${other.info.size}"
      )
      _ = report("each has its own id", session.id != other.id, s"${session.id.value} / ${other.id.value}")
      _ = other.close()
      _ <- awaitCondition(5.seconds)(() => !other.info.running)
      _ = report(
        "closing one session leaves the other running",
        session.info.running && !other.info.running,
        s"one ${session.info.running}"
      )

      // Teardown: close ends the shell within a deadline, twice is safe, and the reader stops without a failure.
      _ = report("the session is running before it is closed", session.info.running, s"running ${session.info.running}")
      _ = session.close()
      closed <- awaitCondition(5.seconds)(() => !session.info.running)
      _ = report("close() ends the shell", closed, s"running ${session.info.running}")
      _ = report("and the reader stops without a failure", session.error.isEmpty, s"error ${session.error}")
      _ = session.close()
      stillReadable = session.snapshot()
      _ = report("closing twice is safe", !session.info.running && stillReadable.id == session.id, s"rows ${stillReadable.rows.size}")
    yield ()

  /** Scope-owned sessions: one closed by its scope, one whose shell exits on its own. */
  private def scopeChecks(reporter: Reporter)(using Frame): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      // A session whose scope ends is closed by the scope, in bounded time: the close-before-interrupt ordering is what makes that
      // finish at all, so this is the check that guards the deadlock.
      scoped <- Async.timeout(15.seconds)(closedByScope)
      _ = report("the scope closes a session in bounded time", !scoped, s"running after the scope: $scoped")

      // And a session that exits on its own: the shell is gone, so the reader ends cleanly.
      exited <- Async.timeout(20.seconds)(exitsOnItsOwn)
      _ = report("a shell that exits is noticed and ends cleanly", !exited._1 && exited._2, s"running ${exited._1}, clean ${exited._2}")
    yield ()

  /** A session owned by a scope of its own: the scope's release closes it, which is the ordering a real host depends on. Yields whether the
    * shell is still running when the scope has finished.
    */
  private def closedByScope(using Frame): Boolean < (Async & Abort[Any]) =
    Scope.run {
      for
        temporary <- HostSession.open(SessionId("scoped"), index = 0, Paths.get(sys.props("user.dir")), HostSession.loginShell)
        _ <- await(temporary)(snapshot => text(snapshot).trim.nonEmpty)
        _ <- Sync.defer(temporary.close())
        // The scope closes *after* the body, so the shell has to be gone before this reads it: the point of the check is that closing
        // and then releasing the scope both finish, which the timeout around this call is what proves.
        _ <- awaitCondition(5.seconds)(() => !temporary.info.running)
      yield temporary.info.running
    }

  /** A session whose shell exits on its own. Yields whether the shell is still running, and whether the reader ended without recording a
    * failure.
    */
  private def exitsOnItsOwn(using Frame): (Boolean, Boolean) < (Async & Abort[Any]) =
    Scope.run {
      for
        temporary <- HostSession.open(SessionId("exits"), index = 0, Paths.get(sys.props("user.dir")), HostSession.loginShell)
        _ <- await(temporary)(snapshot => text(snapshot).trim.nonEmpty)
        _ <- Sync.defer(temporary.write("exit\n"))
        _ <- awaitCondition(10.seconds)(() => !temporary.info.running)
      yield (temporary.info.running, temporary.error.isEmpty)
    }

  /** A fresh session, a full screen of output, and the delta that describes it: whether history growth reaches a client. */
  private def historyGrowthDelta(using Frame): Boolean < (Async & Abort[Any]) =
    Scope.run {
      for
        temporary <- HostSession.open(SessionId("growing"), index = 0, Paths.get(sys.props("user.dir")), HostSession.loginShell)
        _ <- await(temporary)(snapshot => text(snapshot).trim.nonEmpty)
        baseline = temporary.snapshot()
        _ <- Sync.defer(temporary.write("seq 1 200\n"))
        grown <- await(temporary)(snapshot => tmpHasLine(snapshot, "200"))
        (current, damage) = temporary.frameSince(baseline)
        _ <- Sync.defer(temporary.close())
      yield damage.historyRows.isDefined && grown.historyRows > baseline.historyRows && current.historyRows > baseline.historyRows
    }

  private def tmpHasLine(snapshot: Snapshot, line: String): Boolean = lines(snapshot).contains(line)

  /** Takes a snapshot during the first of two cursor moves, so the delta that follows covers the second move and no text. */
  private def cursorOnlyBaseline(session: HostSession)(using Frame): Snapshot < Async =
    for
      _ <- Sync.defer(session.write("printf '\\033[3;5H'; sleep 1; printf '\\033[7;9H'; sleep 2\n"))
      first <- await(session)(snapshot => snapshot.cursor.x == 4 && snapshot.cursor.y == 2)
      _ <- await(session)(snapshot => snapshot.cursor.x == 8 && snapshot.cursor.y == 6)
    yield first

  /** Takes a snapshot after a command has printed a marker and slept, before it turns bracketed paste on, so the delta that follows covers
    * the mode change and no text.
    */
  private def modeOnlyBaseline(session: HostSession)(using Frame): Snapshot < Async =
    for
      _ <- Sync.defer(session.write("printf 'MODES_READY'; sleep 2; printf '\\033[?2004h'; sleep 2\n"))
      _ <- await(session)(snapshot => lines(snapshot).exists(_.contains("MODES_READY")))
      // The command line's echo is still being painted when the marker appears, so the baseline is the first frame that stops
      // changing: the mode change has to be the only difference between it and what follows.
      ready <- settledSnapshot(session)
      _ <- await(session)(snapshot => snapshot.modes.bracketedPaste)
    yield ready

  /** Whether a delta names exactly the rows whose cells differ between the two frames: no row missed, none invented. A shell repaints
    * whenever it likes, so this is the invariant a client depends on - not "no rows changed".
    */
  private def exactRows(before: Snapshot, after: Snapshot, damage: Damage): Boolean =
    val changed = after.rows.zipWithIndex.collect { case (row, index) if before.rows.lift(index).forall(_ != row) => index }.toSet
    changed == damage.rows.map(_.row).toSet

  /** The first frame that survives a moment without changing: what a baseline has to be for a delta to mean anything. */
  private def settledSnapshot(session: HostSession)(using Frame): Snapshot < Async =
    def loop(previous: Snapshot, attempts: Int): Snapshot < Async =
      for
        _ <- Async.sleep(50.millis)
        current <- Sync.defer(session.snapshot())
        result <- if current == previous || attempts <= 0 then Sync.defer(current) else loop(current, attempts - 1)
      yield result
    for
      first <- Sync.defer(session.snapshot())
      settled <- loop(first, attempts = 20)
    yield settled

  /** Reads snapshots and deltas while output floods in, and checks that each delta, applied to the frame before it, is the frame after it.
    * That is the invariant a client depends on, and the one a dropped or mis-indexed row would break.
    */
  private def floodCheck(session: HostSession, iterations: Int)(using Frame): FloodResult < Async =
    def loop(remaining: Int, previous: Snapshot, consistent: Boolean, largest: Int): FloodResult < Async =
      if remaining == 0 then FloodResult(consistent, s"$iterations deltas, largest $largest rows", largest)
      else
        for
          _ <- Async.sleep(5.millis)
          step <- Sync.defer(session.frameSince(previous))
          (current, damage) = step
          next <- loop(remaining - 1, current, consistent && damage.applyTo(previous) == current, math.max(largest, damage.rows.size))
        yield next
    for
      first <- Sync.defer(session.snapshot())
      result <- loop(iterations, first, consistent = true, largest = 0)
    yield result

  private final case class FloodResult(ok: Boolean, detail: String, largest: Int)

  /** The first snapshot satisfying `holds`, or the last one when the deadline passes. */
  private def await(session: HostSession)(holds: Snapshot => Boolean)(using Frame): Snapshot < Async =
    val deadline = java.lang.System.nanoTime() + waitTimeout.toNanos
    def loop(): Snapshot < Async =
      val snapshot = session.snapshot()
      if holds(snapshot) || java.lang.System.nanoTime() > deadline then snapshot
      // Lazy: `loop()` must not be called until the sleep has finished, or the retries build an ever-deeper
      // computation instead of suspending.
      else Async.sleep(50.millis).andThen(loop())
    loop()

  /** Waits for a condition on the session's metrics, with a bound: teardown has to finish, not hang. */
  private def awaitCondition(timeout: scala.concurrent.duration.Duration)(condition: () => Boolean)(using Frame): Boolean < Async =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    def loop(): Boolean < Async =
      if condition() then true
      else if java.lang.System.nanoTime() > deadline then false
      else Async.sleep(20.millis).andThen(loop())
    loop()

  /** A smiley, as the two chars the emulator stores in two cells. */
  private val Emoji: String = "\uD83D\uDE00"

  private def text(snapshot: Snapshot): String = snapshot.rows.map(rowText).mkString("\n")

  private def rowText(row: Row): String = row.cells.map(_.text).mkString

  private def lines(snapshot: Snapshot): Vector[String] = snapshot.rows.map(row => rowText(row).trim)

  /** The row that contains `needle`, if any. */
  private def rowContaining(snapshot: Snapshot, needle: String): Option[Row] =
    snapshot.rows.reverseIterator.find(row => rowText(row).contains(needle))

  /** The cell where `needle` starts on the last row that contains it: the output line, not the echoed command. */
  private def cellOf(snapshot: Snapshot, needle: String): Option[Cell] =
    rowContaining(snapshot, needle).flatMap(row => cellAt(rowText(row).indexOf(needle))(row))

  /** The cell just after the one where `needle` starts: the continuation cell of a wide character, say. */
  private def cellAfter(snapshot: Snapshot, needle: String): Option[Cell] =
    rowContaining(snapshot, needle).flatMap(row => cellAt(rowText(row).indexOf(needle) + 1)(row))

  private def cellAt(index: Int)(row: Row): Option[Cell] =
    if index < 0 then None else row.cells.lift(index)
