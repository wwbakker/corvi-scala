package corvi.app

import corvi.contract.*
import corvi.renderer.*
import corvi.renderer.TerminalView as TerminalPane
import corvi.host.HostSession
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.stage.Stage
import javax.imageio.ImageIO
import kyo.Duration
import scala.concurrent.duration.*

/** The renderer's regression suite: a real host session painted by the real canvas, checked through the frame it was given and the pixels
  * it produced.
  *
  * ./mill -i app.test.runMain corvi.app.RendererProbe
  *
  * The checks run on a thread of their own, never on the FX thread: the pushes the checks wait for arrive *through* the FX thread, so a
  * check that blocked it would wait for itself. `onFx` is the only way in.
  */
object RendererProbe:
  private val cols = 100
  private val rows = 28
  private val fontSize = 13.0
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(16.millis)
  private val waitTimeout: scala.concurrent.duration.Duration = 20.seconds

  def main(args: Array[String]): Unit =
    val finished = CountDownLatch(1)
    val failures = AtomicInteger(0)
    Platform.startup(() => ())
    val thread = Thread.ofPlatform().name("corvi-renderer-probe").daemon(true)
    val _ = thread.start { () =>
      failures.set(run())
      finished.countDown()
    }
    finished.await()
    val failed = failures.get()
    println(if failed == 0 then "all renderer checks passed" else s"$failed renderer check(s) failed")
    Platform.exit()
    java.lang.System.exit(if failed == 0 then 0 else 1)

  private def run(): Int =
    val reporter = Reporter()
    val running = Harness.start(cols, rows, pushInterval, HostSession.loginShell)
    var stage: Option[Stage] = None
    try
      val metrics = onFx(TerminalMetrics.monospaced(fontSize))
      val view = onFx(TerminalPane(running.client, running.session, metrics))
      running.client.sink.plug(view)
      view.attach()
      val scene = onFx {
        view.setPrefSize(metrics.layout.width(cols), metrics.layout.height(rows))
        val created = Scene(view, metrics.layout.width(cols), metrics.layout.height(rows))
        created
      }
      stage = Some(onFx {
        val window = Stage()
        window.setScene(scene)
        window.show()
        view.setCursorBlinking(false)
        // A harness without a window manager says which state it is in: the checks below assume the keyboard is the terminal's, and
        // `focusChecks` switches it off explicitly to look at the hollow cursor.
        view.showFocus(true)
        view.requestFocus()
        window
      })
      val layout = metrics.layout
      // The second view lives in a scene of its own, so it lays out and asks for a grid like a real one would.
      val second: Option[TerminalPane] = running.extras.headOption.map { id =>
        val created = onFx(TerminalPane(running.client, id, metrics))
        running.client.sink.plug(created)
        created.attach()
        val _ = onFx(new Scene(new javafx.scene.layout.StackPane(created), metrics.layout.width(60), metrics.layout.height(16)))
        created
      }
      paintChecks(view, scene, layout, reporter)
      damageChecks(view, scene, layout, reporter)
      selectionChecks(view, reporter)
      wideChecks(view, scene, layout, reporter)
      resizeChecks(view, stage.get, layout, reporter)
      scrollbackChecks(view, reporter, layout)(using stage)
      focusChecks(view, scene, layout, reporter)
      captureChecks(view, scene, reporter)
      twoSessionsCheck(view, running.session, second, running.extras.headOption, reporter)
      closeSessionChecks(view, reporter)
      cleanupChecks(running, reporter)
    finally
      stage.foreach(window => onFx(window.close()))
      running.stop()
    reporter.failures

  /** PASS/FAIL lines and the count the probe exits with. */
  private final class Reporter:
    private val failed = AtomicInteger(0)

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then
        val _ = failed.incrementAndGet()
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(4).foreach(line => println(s"      | $line"))

    def failures: Int = failed.get()

  /** The prompt, its colours, and the pixels that back them. */
  private def paintChecks(view: TerminalPane, scene: Scene, layout: Layout, reporter: Reporter): Unit =
    val report = reporter.report
    val prompted = poll(waitTimeout)(onFx(view.screen).exists(model => rowsOf(model).exists(_.contains("»"))))
    report("the host's prompt is painted", prompted, onFx(view.screen).map(model => rowsOf(model).mkString(" / ")).getOrElse("no model"))

    still(view)
    val (frame, image) = shoot(view, scene)
    report(
      "the frame carries text cells and the cursor",
      frame.exists(f => f.rows.exists(_.cells.exists(_.text == "»")) && f.cursor.visible),
      frame.map(f => s"${f.size}, cursor ${f.cursor}").getOrElse("no frame")
    )
    val background = backgroundPixelAt(image, layout, cols - 1, rows - 1)
    report(
      "the theme's background is the canvas background",
      closeTo(background, Rgb(13, 17, 23), tolerance = 6),
      s"pixel ${show(background)}"
    )
    val promptCell = frame.flatMap(f => findCell(f, "»"))
    report(
      "a painted glyph uses the terminal's foreground",
      promptCell.exists { case (_, _, cell) => cell.foreground.forall(colour => colour.red > 100) } &&
        promptCell.exists { case (col, row, _) => containsColour(image, layout, col, row, Rgb(122, 162, 247), tolerance = 90) },
      promptCell.map { case (col, row, cell) => s"cell ($col,$row) ${cell.foreground}" }.getOrElse("no » cell")
    )

    // A command with colours, so the checks see a foreground, a background and an inverse cell. The checks look for the *output* cells by their
    // attributes, not by the words: the echoed command line contains the same words, uncoloured.
    onFx(view.typeText("printf '\\033[31mR%s\\033[0m \\033[44mB%s\\033[0m \\033[7mI%s\\033[0m\\n' ED G NV\r"))
    val coloured = poll(waitTimeout)(
      onFx(view.frame).exists(frame => attribute((cell: Cell) => cell.text == "R" && cell.foreground.isDefined)(frame).isDefined)
    )
    report(
      "the damage pushed for a command reaches the canvas",
      coloured,
      onFx(view.frame).map(f => lines(f).filter(_.nonEmpty).mkString("\n")).getOrElse("no frame")
    )

    still(view)
    val (updated, colouredImage) = shoot(view, scene)
    val redCell = updated.flatMap(frame =>
      attribute((cell: Cell) => cell.text == "R" && cell.foreground.exists(c => c.red > 150 && c.green < 100))(frame)
    )
    val bgCell = updated.flatMap(frame => attribute((cell: Cell) => cell.text == "B" && cell.background.exists(_.blue > 100))(frame))
    val inverseCell = updated.flatMap(frame => attribute((cell: Cell) => cell.text == "I" && cell.inverse)(frame))
    report(
      "a cell carries its SGR colour",
      redCell.isDefined,
      redCell.map { case (col, row, cell) => s"cell ($col,$row) ${cell.text} ${cell.foreground}" }.getOrElse("no red R cell")
    )
    report(
      "and its background is painted",
      bgCell.exists { case (col, row, cell) =>
        closeTo(pixelAt(colouredImage, layout, col, row), cell.background.getOrElse(Rgb(0, 0, 0)), 30)
      },
      bgCell
        .map { case (col, row, cell) => s"cell ($col,$row) ${cell.background} -> ${show(pixelAt(colouredImage, layout, col, row))}" }
        .getOrElse("no blue B cell")
    )
    report(
      "an inverse cell swaps the two",
      inverseCell.exists { case (col, row, _) =>
        closeTo(backgroundPixelAt(colouredImage, layout, col, row), Rgb(230, 237, 243), tolerance = 40)
      },
      inverseCell
        .map { case (col, row, cell) =>
          s"cell ($col,$row) inverse=${cell.inverse} bg=${show(backgroundPixelAt(colouredImage, layout, col, row))}"
        }
        .getOrElse("no inverse I cell")
    )

  /** The canvas changes when the frame does, and the change is visible, not just in the model. */
  private def damageChecks(view: TerminalPane, scene: Scene, layout: Layout, reporter: Reporter): Unit =
    val report = reporter.report
    val before = capture(scene)
    // The marker is assembled by the shell, so the echoed command line cannot contain it: only the output can.
    onFx(view.typeText("printf 'CORVI_%s\\n' RENDER_OK\n"))
    val arrived = poll(waitTimeout)(onFx(view.frame).exists(f => lines(frame = f).contains("CORVI_RENDER_OK")))
    still(view)
    val (afterFrame, after) = shoot(view, scene)
    val differing = differingPixels(before, after)
    report(
      "a command changes the painted canvas",
      arrived && differing > 100,
      s"$differing pixels differ; screen: ${onFx(view.frame).map(f => lines(f).filter(_.nonEmpty).takeRight(3).mkString(" / ")).getOrElse("")}"
    )

    // The cursor is painted where the frame says it is: most of that cell is the cursor's colour.
    val cursor = afterFrame.map(_.cursor)
    val painted = cursor.map(c => cursorPixels(after, layout, c)).getOrElse(0)
    report(
      "the cursor is painted at the frame's position",
      painted * 2 > cursorCells(layout),
      s"cursor ${cursor}, $painted of ${cursorCells(layout)} pixels in the cell"
    )

  /** Selection over the visible cells, and the text a copy would put on the clipboard. */
  private def selectionChecks(view: TerminalPane, reporter: Reporter): Unit =
    val report = reporter.report
    // The output row: the one whose cells carry the attributes the command asked for, so the echoed command line cannot be picked instead.
    val rowOfRed =
      onFx(view.frame).flatMap(frame => attribute((cell: Cell) => cell.text == "R" && cell.foreground.isDefined)(frame)).map(_._2)
    val row = rowOfRed.getOrElse(0)
    onFx(view.select(CellPosition(row, 0), CellPosition(row, 8)))
    val copied = onFx(view.copySelection())
    report(
      "a drag selection copies the cells it covers",
      copied.startsWith("RED") && copied == copied.stripTrailing() && copied.nonEmpty,
      s"'$copied'"
    )
    val selectedCells = onFx(view.screen).map(model => model.text(Selection(CellPosition(row, 0), CellPosition(row, 8)))).getOrElse("")
    report("and the model and the view agree about it", copied == selectedCells, s"'$selectedCells'")
    onFx(view.clearSelection())
    report("clearing the selection empties the copy", onFx(view.selectedText).isEmpty, "")

  /** History above the grid, and the way back to it. */
  private def scrollbackChecks(view: TerminalPane, reporter: Reporter, layout: Layout)(using staging: Option[Stage]): Unit =
    val report = reporter.report
    onFx(view.typeText("seq 1 200\n"))
    val flooded = poll(waitTimeout)(onFx(view.frame).exists(f => lines(frame = f).exists(_.trim == "200")))
    report("the screen has more lines than fit", flooded, "")

    val live = onFx(view.screen).map(model => (0 until model.rows).map(model.rowText).toVector).getOrElse(Vector.empty)
    onFx(view.scrollBy(6))
    val scrolled = poll(waitTimeout)(onFx(view.screen).exists(model => model.scrollOffset >= 6 && model.missingHistory == 0))
    val shown = onFx(view.screen).map(model => (0 until model.rows).map(model.rowText).toVector).getOrElse(Vector.empty)
    report(
      "scrolling back shows history above the grid",
      scrolled && shown.exists(row => row.trim.nonEmpty && !live.contains(row)),
      s"${shown.count(_.trim.nonEmpty)} non-empty rows, ${shown.count(row => !live.contains(row))} of them from history"
    )

    onFx(view.scrollTo(0))
    onFx(view.select(CellPosition(0, 0), CellPosition(0, 4)))
    val beforeScroll = onFx(view.selectedText)
    onFx(view.scrollBy(1))
    val afterScroll = onFx(view.selectedText)
    report(
      "scrolling clears a selection made over the grid",
      beforeScroll.nonEmpty && afterScroll.isEmpty,
      s"'${beforeScroll.getOrElse("")}' -> '${afterScroll.getOrElse("")}'"
    )
    onFx(view.scrollTo(0))

    // Repeated paging: the offset clamps at both ends and nothing falls over.
    (1 to 5).foreach { _ =>
      onFx(view.scrollBy(-3))
      onFx(view.scrollBy(2))
    }
    val clamped = onFx(view.screen).exists(model => model.scrollOffset >= 0 && model.scrollOffset <= model.maxScroll)
    report("repeated paging clamps the viewport", clamped, onFx(view.screen).map(model => s"offset ${model.scrollOffset}").getOrElse(""))

    // A resize while scrolled back keeps the reader where they were.
    onFx(view.scrollBy(10))
    val beforeResize = onFx(view.screen).map(_.scrollOffset).getOrElse(0)
    onFx {
      staging.foreach(window => window.setHeight(window.getHeight + layout.cellHeight * 2))
    }
    val anchored = poll(waitTimeout)(onFx(view.screen).exists(model => model.scrollOffset > 0))
    report(
      "resizing while scrolled back keeps the reader's place",
      beforeResize > 0 && anchored,
      s"offset $beforeResize -> ${onFx(view.screen).map(_.scrollOffset).getOrElse(0)}"
    )
    // The history cap: the frame's historyRows stops at the host's cap however much a program prints.
    onFx(view.typeText("seq 1 5200\r"))
    val capped = poll(waitTimeout)(onFx(view.frame).exists(f => lines(f).exists(_.trim == "5200")))
    report(
      "history stops at the host's cap",
      capped && onFx(view.frame).exists(_.historyRows == 5000),
      onFx(view.frame).map(f => s"history ${f.historyRows}").getOrElse("no frame")
    )

    onFx(view.scrollTo(0))
    report(
      "scrolling to the bottom returns to the live grid",
      onFx(view.screen).exists(model => model.isLive && !model.newOutput),
      onFx(view.screen).map(model => s"offset ${model.scrollOffset}, new output ${model.newOutput}").getOrElse("no model")
    )

  /** The window's size reaches the host, and the grid comes back the right shape. */
  private def resizeChecks(view: TerminalPane, stage: Stage, layout: Layout, reporter: Reporter): Unit =
    val report = reporter.report
    val before = onFx(view.screen).map(_.cols).getOrElse(cols)
    onFx {
      stage.setWidth(stage.getWidth + layout.cellWidth * 4)
      stage.setHeight(stage.getHeight + layout.cellHeight * 2)
    }
    val narrowed = poll(waitTimeout)(onFx(view.frame).exists(f => f.size.cols > before))
    report(
      "resizing the window asks the host for a bigger grid",
      narrowed,
      s"$before -> ${onFx(view.frame).map(_.size).getOrElse(TermSize(0, 0))}"
    )
    // A resize makes the shell repaint, and a command typed into a repaint can be lost: wait for the prompt, and say it twice.
    val _ = waitForPrompt(view)
    onFx(view.typeText("stty size\r"))
    if !poll(scala.concurrent.duration.Duration(2, "s"))(
        onFx(view.frame).exists(f => f.rows.exists(row => parseSize(row.cells.map(_.text).mkString.trim).isDefined))
      )
    then onFx(view.typeText("stty size\r"))
    def printedSize: Option[(Int, Int)] =
      onFx(view.frame).flatMap(_.rows.iterator.map(_.cells.map(_.text).mkString.trim).flatMap(parseSize).nextOption())
    val stty = poll(waitTimeout)(printedSize.isDefined)
    val size = onFx(view.frame).map(_.size)
    report(
      "and the PTY has it too",
      stty && printedSize.zip(size.map(expected => (expected.rows, expected.cols))).exists { case (printed, expected) =>
        printed == expected
      },
      s"stty ${printedSize}, grid ${size.map(expected => (expected.rows, expected.cols))}"
    )

  /** Wide and astral characters: one glyph over two cell widths, and the continuation cell left alone. */
  private def wideChecks(view: TerminalPane, scene: Scene, layout: Layout, reporter: Reporter): Unit =
    val report = reporter.report
    onFx(view.typeText("printf '世界 😀\\r'"))
    val arrived = poll(waitTimeout)(onFx(view.frame).exists(_.rows.exists(_.cells.exists(_.width == CellWidth.Wide))))
    val frame = onFx(view.frame)
    val wide = frame.flatMap(f =>
      f.rows.zipWithIndex.reverseIterator
        .flatMap { case (row, index) =>
          row.cells.zipWithIndex.find(_._1.width == CellWidth.Wide).map { case (cell, col) => (cell, col, index) }
        }
        .nextOption()
    )
    report(
      "a wide character is one painted glyph over two cells",
      arrived && wide.isDefined,
      wide.map { case (cell, col, row) => s"'${cell.text}' at column $col of row $row (${cell.width})" }.getOrElse("no wide cell")
    )
    still(view)
    val (_, image) = shoot(view, scene)
    val painted = wide.map { case (_, col, row) => nonBackgroundPixels(image, layout, col, row) }.getOrElse(0)
    report(
      "and it is drawn, not skipped",
      painted > 0,
      wide.map { case (cell, _, _) => s"'${cell.text}' painted $painted pixels" }.getOrElse("no wide cell")
    )

  /** The cursor: filled with the keyboard, hollow without it, and steady when blinking is switched off. */
  private def focusChecks(view: TerminalPane, scene: Scene, layout: Layout, reporter: Reporter): Unit =
    val report = reporter.report
    onFx(view.scrollTo(0))
    onFx(view.setCursorBlinking(false))

    // The canvas is checked on its own, where focus is the only thing that differs between the two captures.
    val model = onFx(view.screen).getOrElse(ScreenModel.from(onFx(view.frame).map(_.snapshot).get))
    val (hollow, hollowOutline) = onFx {
      val canvas = TerminalCanvas(TerminalMetrics.monospaced(fontSize), TerminalStyle.default)
      canvas.show(model, None)
      canvas.setBlinking(false)
      canvas.showFocus(false)
      val image = imageOf(canvas)
      (cursorPixels(image, layout, model.frame.cursor), outlinePainted(image, layout, model.frame.cursor))
    }
    val solid = onFx {
      val canvas = TerminalCanvas(TerminalMetrics.monospaced(fontSize), TerminalStyle.default)
      canvas.show(model, None)
      canvas.setBlinking(false)
      canvas.showFocus(true)
      cursorPixels(imageOf(canvas), layout, model.frame.cursor)
    }
    report(
      "an unfocused cursor is an outline",
      hollowOutline && hollow * 2 < cursorCells(layout),
      s"$hollow of ${cursorCells(layout)} pixels are the cursor, outline $hollowOutline"
    )
    report(
      "a focused cursor is filled",
      solid * 2 > cursorCells(layout) && solid > hollow * 2,
      s"hollow $hollow of ${cursorCells(layout)}, filled $solid"
    )

    // Blinking on, then pinned off: the frame keeps asking for blinking on every damage, and the override has to win over it, or a capture
    // after any output is a coin flip.
    onFx(view.setCursorBlinking(true))
    onFx(view.setCursorBlinking(false))
    onFx(view.typeText("printf 'BLINK_%s\\r' STILL_OFF"))
    val _ = poll(waitTimeout)(onFx(view.frame).exists(f => lines(f).contains("BLINK_STILL_OFF")))
    onFx(view.showFocus(true))
    still(view)
    val (blinkFrame, blinkImage) = shoot(view, scene)
    val blinkPainted = blinkFrame.map(f => cursorPixels(blinkImage, layout, f.cursor)).getOrElse(0)
    report(
      "a pinned cursor stays painted after new output",
      blinkPainted * 2 > cursorCells(layout),
      blinkFrame.map(f => s"cursor ${f.cursor}, $blinkPainted pixels painted after the output").getOrElse("no frame")
    )

  /** Two sessions on one host: each view holds its own session's frame, at its own size, and neither shows the other's screen. */
  private def twoSessionsCheck(
      first: TerminalPane,
      firstSession: SessionId,
      maybeSecond: Option[TerminalPane],
      secondSession: Option[SessionId],
      reporter: Reporter
  ): Unit =
    val report = reporter.report
    maybeSecond.zip(secondSession) match
      case Some((second, secondId)) =>
        val _ = poll(waitTimeout)(onFx(second.frame).exists(f => f.id == secondId))
        val firstFrame = onFx(first.frame)
        val secondFrame = onFx(second.frame)
        val ownFirst = firstFrame.exists(_.id == firstSession)
        val ownSecond = secondFrame.exists(_.id == secondId)
        val distinctSizes = firstFrame.map(_.size) != secondFrame.map(_.size)
        val crossed = onFx(first.frame).exists(f => f.id == secondId) || onFx(second.frame).exists(f => f.id == firstSession)
        report(
          "two views on one host hold their own sessions",
          ownFirst && ownSecond && distinctSizes && !crossed,
          s"first ${firstFrame.map(f => (f.id.value, f.size))}, second ${secondFrame.map(f => (f.id.value, f.size))}, crossed $crossed"
        )
      case None => report("two views on one host hold their own sessions", false, "no second session")

  /** Closing the session from the view is the tab-close path, and the host reports it as a closed session. */
  private def closeSessionChecks(view: TerminalPane, reporter: Reporter): Unit =
    val report = reporter.report
    onFx(view.closeSession())
    val ended = poll(waitTimeout)(onFx(view.ended).exists(_.closed))
    report(
      "closing the session from the view is reported as closed",
      ended && onFx(view.ended).exists(_.closed),
      onFx(view.ended).map(described).getOrElse("no end")
    )
    onFx(view.dispose())

  /** The PNG a human can look at, and the guarantee that it is not blank. */
  private def captureChecks(view: TerminalPane, scene: Scene, reporter: Reporter): Unit =
    val report = reporter.report
    onFx(view.scrollTo(0))
    val image = capture(scene)
    val target = Paths.get("out", "corvi-renderer-probe.png")
    val _ = Files.createDirectories(target.getParent)
    ImageIO.write(image, "png", target.toFile)
    report(
      "the capture is a real image",
      image.getWidth > 100 && image.getHeight > 100 && differingPixels(image, blankLike(image)) > 100,
      s"${image.getWidth}×${image.getHeight} to ${target.toAbsolutePath}"
    )

  /** Stopping the harness takes its shell with it: no login shell that was not there before is left behind. */
  private def cleanupChecks(running: Harness.Running, reporter: Reporter): Unit =
    val report = reporter.report
    val before = loginShells()
    running.stop()
    val stopped = poll(waitTimeout)(loginShells().forall(before.contains))
    report(
      "stopping the harness leaves no shell behind",
      stopped,
      s"${loginShells().size} login shells, ${before.size} before"
    )

  // --- helpers -----------------------------------------------------------------------------------------------------------------------

  private def lines(frame: corvi.contract.Frame): Vector[String] = frame.rows.map(row => row.cells.map(_.text).mkString.trim)

  private def rowsOf(model: ScreenModel): Vector[String] = (0 until model.rows).map(model.rowText).toVector

  /** The login shells the machine is running, as `pgrep` sees them. The pattern comes from `$SHELL`, so on a machine whose shell is not zsh
    * this check looks for that shell rather than passing without looking at anything.
    */
  private def loginShells(): Vector[String] =
    val name = Option(java.lang.System.getenv("SHELL")).filter(_.nonEmpty).map(path => path.split('/').last).getOrElse("sh")
    val process = ProcessBuilder("pgrep", "-fl", s"$name -l").redirectErrorStream(true).start()
    val text = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val _ = process.waitFor()
    text.linesIterator.map(_.trim).filter(_.nonEmpty).toVector

  /** The first cell (column, row) whose attributes satisfy the predicate, scanning the grid from the bottom up: output is at the bottom. */
  private def attribute(predicate: Cell => Boolean)(frame: corvi.contract.Frame): Option[(Int, Int, Cell)] =
    frame.rows.zipWithIndex.reverseIterator
      .flatMap { case (row, index) =>
        row.cells.zipWithIndex.collectFirst { case (cell, col) if predicate(cell) => (col, index, cell) }
      }
      .nextOption()

  private def findCell(frame: corvi.contract.Frame, needle: String): Option[(Int, Int, Cell)] =
    frame.rows.zipWithIndex.reverseIterator
      .flatMap { case (row, index) => Option.when(row.cells.map(_.text).mkString.contains(needle))(row -> index) }
      .nextOption()
      .flatMap { case (row, index) =>
        val text = row.cells.map(_.text).mkString
        val start = text.indexOf(needle)
        row.cells.lift(start).map(cell => (start, index, cell))
      }

  private def onFx[A](action: => A): A =
    if Platform.isFxApplicationThread then action
    else
      val result = AtomicReference[Either[Throwable, A]]()
      val latch = CountDownLatch(1)
      Platform.runLater { () =>
        try result.set(Right(action))
        catch case error: Throwable => result.set(Left(error))
        latch.countDown()
      }
      if !latch.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("the FX thread did not answer")
      result.get() match
        case Right(value) => value
        case Left(error)  => throw error

  private def poll(timeout: scala.concurrent.duration.Duration)(condition: => Boolean): Boolean =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && java.lang.System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition
    satisfied

  /** The frame and the image it produced, taken in one go: reading the model and the pixels separately lets the shell paint between them,
    * and then a check compares a cursor position with a picture from a different moment.
    */
  private def shoot(view: TerminalPane, scene: Scene): (Option[corvi.contract.Frame], BufferedImage) =
    onFx {
      val frame = view.frame
      val image: WritableImage = scene.snapshot(null)
      val width = image.getWidth.toInt
      val height = image.getHeight.toInt
      val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val pixels = image.getPixelReader
      for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
      (frame, buffered)
    }

  /** Waits until the screen stops changing, so a shot is not of a half-painted frame. */
  private def still(view: TerminalPane): Unit =
    val _ = poll(waitTimeout) {
      val one = onFx(view.frame)
      Thread.sleep(30)
      one == onFx(view.frame)
    }

  private def capture(scene: Scene): BufferedImage =
    val image: WritableImage = onFx(scene.snapshot(null))
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = image.getPixelReader
    for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
    buffered

  /** A cell's background: the colour most of its pixels are. A glyph, or the antialiasing around one, cannot outvote the background. */
  private def backgroundPixelAt(image: BufferedImage, layout: Layout, col: Int, row: Int): Rgb =
    val x0 = math.max(0, layout.cellX(col).toInt)
    val y0 = math.max(0, layout.cellY(row).toInt)
    val x1 = math.min(image.getWidth - 1, x0 + layout.cellWidth.toInt - 1)
    val y1 = math.min(image.getHeight - 1, y0 + layout.cellHeight.toInt - 1)
    val pixels = (x0 to x1).iterator.flatMap(x => (y0 to y1).iterator.map(y => fromArgb(image.getRGB(x, y)))).toVector
    pixels
      .groupBy(identity)
      .view
      .map { case (colour, same) => colour -> same.size }
      .maxByOption(_._2)
      .map(_._1)
      .getOrElse(fromArgb(image.getRGB(x0, y0)))

  /** The middle of a cell: its corner is where a hollow cursor's outline is drawn, and a check of a cell's colour wants the inside. */
  private def pixelAt(image: BufferedImage, layout: Layout, col: Int, row: Int): Rgb =
    val x = math.min(image.getWidth - 1, (layout.cellX(col) + layout.cellWidth / 2).toInt)
    val y = math.min(image.getHeight - 1, (layout.cellY(row) + layout.cellHeight / 2).toInt)
    fromArgb(image.getRGB(x, y))

  private def containsColour(image: BufferedImage, layout: Layout, col: Int, row: Int, target: Rgb, tolerance: Int): Boolean =
    val x0 = layout.cellX(col).toInt
    val y0 = layout.cellY(row).toInt
    val x1 = math.min(image.getWidth - 1, x0 + layout.cellWidth.toInt)
    val y1 = math.min(image.getHeight - 1, y0 + layout.cellHeight.toInt)
    (x0 to x1).exists(x => (y0 to y1).exists(y => closeTo(fromArgb(image.getRGB(x, y)), target, tolerance)))

  /** Whether a pixel on the cursor cell's edge is not the background: a hollow cursor is its outline. */
  private def outlinePainted(image: BufferedImage, layout: Layout, cursor: Cursor): Boolean =
    val x = layout.cellX(cursor.x).toInt
    val y = layout.cellY(cursor.y).toInt
    val background = TerminalStyle.toColor(backgroundPixelAt(image, layout, cols - 1, rows - 1)).hashCode
    Vector(
      image.getRGB(math.min(image.getWidth - 1, x), math.min(image.getHeight - 1, y)),
      image.getRGB(math.min(image.getWidth - 1, x + layout.cellWidth.toInt - 1), math.min(image.getHeight - 1, y)),
      image.getRGB(math.min(image.getWidth - 1, x), math.min(image.getHeight - 1, y + layout.cellHeight.toInt - 1))
    ).exists(_ != background)

  /** A canvas on its own, rendered to an image: a scene is what makes `snapshot` lay the node out. */
  private def imageOf(canvas: TerminalCanvas): BufferedImage =
    val scene = new Scene(new javafx.scene.layout.StackPane(canvas))
    val image: WritableImage = scene.snapshot(null)
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = image.getPixelReader
    for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
    buffered

  /** Waits until the last non-empty row is the prompt, which is the only state in which a typed command is a command. */
  private def waitForPrompt(view: TerminalPane): Boolean =
    poll(waitTimeout) {
      onFx(view.frame).exists(f => lines(f).filter(_.nonEmpty).lastOption.exists(_.contains("»")))
    }

  /** How many pixels of the cursor's cell are the cursor's colour, which tells a filled block from an outline. */
  private def cursorPixels(image: BufferedImage, layout: Layout, cursor: Cursor): Int =
    val x0 = math.max(0, layout.cellX(cursor.x).toInt)
    val y0 = math.max(0, layout.cellY(cursor.y).toInt)
    val x1 = math.min(image.getWidth - 1, x0 + layout.cellWidth.toInt)
    val y1 = math.min(image.getHeight - 1, y0 + layout.cellHeight.toInt)
    val background = backgroundPixelAt(image, layout, cols - 1, rows - 1)
    (x0 to x1).iterator
      .flatMap(x => (y0 to y1).iterator.map(y => fromArgb(image.getRGB(x, y))))
      .count(pixel => !closeTo(pixel, background, 6))

  private def cursorCells(layout: Layout): Int = math.max(1, (layout.cellWidth * layout.cellHeight).toInt)

  /** How many pixels of a cell are not the theme's background, which is how a check tells a painted glyph from an empty cell. */
  private def nonBackgroundPixels(image: BufferedImage, layout: Layout, col: Int, row: Int): Int =
    val x0 = math.max(0, layout.cellX(col).toInt)
    val y0 = math.max(0, layout.cellY(row).toInt)
    val x1 = math.min(image.getWidth - 1, x0 + layout.cellWidth.toInt)
    val y1 = math.min(image.getHeight - 1, y0 + layout.cellHeight.toInt)
    val background = backgroundPixelAt(image, layout, cols - 1, rows - 1)
    (x0 to x1).iterator
      .flatMap(x => (y0 to y1).iterator.map(y => fromArgb(image.getRGB(x, y))))
      .count(pixel => !closeTo(pixel, background, 6))

  private def differingPixels(left: BufferedImage, right: BufferedImage): Int =
    val width = math.min(left.getWidth, right.getWidth)
    val height = math.min(left.getHeight, right.getHeight)
    (0 until width).iterator.flatMap(x => (0 until height).iterator.map(y => x -> y)).count { case (x, y) =>
      left.getRGB(x, y) != right.getRGB(x, y)
    }

  /** An image of one flat colour, so the "is this blank?" check reuses the pixel diff. */
  private def blankLike(image: BufferedImage): BufferedImage =
    val blank = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_ARGB)
    val first = image.getRGB(0, 0)
    for x <- 0 until image.getWidth; y <- 0 until image.getHeight do blank.setRGB(x, y, first)
    blank

  private def closeTo(left: Rgb, right: Rgb, tolerance: Int): Boolean =
    math.abs(left.red - right.red) <= tolerance && math.abs(left.green - right.green) <= tolerance &&
      math.abs(left.blue - right.blue) <= tolerance

  private def fromArgb(argb: Int): Rgb =
    Rgb(red = (argb >> 16) & 0xff, green = (argb >> 8) & 0xff, blue = argb & 0xff)

  private def parseSize(row: String): Option[(Int, Int)] =
    row.split(' ').toList match
      case rows :: cols :: Nil => rows.toIntOption.zip(cols.toIntOption).map { case (r, c) => (r, c) }
      case _                   => None

  private def show(rgb: Rgb): String = s"(${rgb.red},${rgb.green},${rgb.blue})"

  private def described(ended: Ended): String = s"code=${ended.code}, closed=${ended.closed}, reason=${ended.reason}"
