package corvi.app

import corvi.protocol.HostPaths
import corvi.renderer.TerminalView as TerminalPane
import corvi.ui.ShellView
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.Parent
import javafx.stage.Stage
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Reproduces the Pi paging report against a copy of a real session.
  *
  * The runner script supplies a private socket and a `$SHELL` that starts Pi on the copied session. This probe opens the real window, waits
  * for Pi to settle, drives 20 PageUp and 20 PageDown steps plus rapid bursts through JavaFX key dispatch, and records frame counts,
  * latency, CPU, context switches and JFR recordings for the app and host. It never touches the user's host.
  *
  * ./scripts/pi-paging-profile.sh [session.jsonl]
  */
object PiPagingProbe:
  private val outDir: Path =
    sys.env.get("PI_PAGING_OUT").filter(_.nonEmpty).map(Paths.get(_)).getOrElse(Paths.get("/tmp", "corvi-pi-paging"))
  private val jcmd: Path = Paths.get(System.getProperty("java.home"), "bin", "jcmd")
  private val keysPerPhase: Int = 20
  private val targetCols: Int = 185
  private val targetRows: Int = 83
  private val quietMillis: Long = 120
  private val firstFrameTimeout: scala.concurrent.duration.Duration = 2.seconds

  def main(args: Array[String]): Unit =
    Files.createDirectories(outDir)
    val finished = CountDownLatch(1)
    val result = AtomicReference[Either[String, String]](Left("the probe did not run"))
    Platform.startup(() => ())
    val thread = Thread.ofPlatform().name("corvi-pi-paging-probe").daemon(true)
    val _ = thread.start { () =>
      result.set(run())
      finished.countDown()
    }
    finished.await()
    result.get() match
      case Right(report) =>
        println(report)
        Platform.exit()
        System.exit(0)
      case Left(error) =>
        System.err.println(s"pi paging probe: $error")
        Platform.exit()
        System.exit(1)

  private def run(): Either[String, String] =
    val startedAt = System.nanoTime()
    var stage: Option[Stage] = None
    var app: Option[AppWindow] = None
    var hostPid: Option[Long] = None
    try
      val window = onFx {
        val created = Stage()
        created.setTitle("Corvi — Pi paging profile")
        created
      }
      stage = Some(window)
      val shellWindow = onFx(AppWindow(window, System.getProperty("user.dir")))
      app = Some(shellWindow)
      onFx(shellWindow.connect())
      if !await(90.seconds)(onFx(shellWindow.shell.isDefined)) then return Left("the window never mounted a shell")
      val shell = onFx(shellWindow.shell).getOrElse(return Left("the window never mounted a shell"))
      if !await(30.seconds)(onFx(terminalViews(shell)).nonEmpty) then return Left("the shell never mounted a terminal view")
      val views = onFx(terminalViews(shell))
      val view = views.find(_.isVisible).getOrElse(views.head)

      // Pi sets the title once its transcript has loaded. Before that a resize only makes startup more expensive.
      val titled = await(90.seconds)(onFx(view.frame.exists(_.modes.title.nonEmpty)))
      if !titled then return Left(s"Pi never set a title; last frame: ${describe(view)}")

      resizeToGrid(view, window)
      val sized = await(20.seconds)(onFx(view.frame.exists(size => nearTarget(size.size))))
      if !sized then return Left(s"the terminal did not reach ${targetCols}x${targetRows}; frame: ${describe(view)}")
      val quiet = await(5.seconds)(quietFor(view, 500))
      if !quiet then return Left(s"Pi did not settle after the resize; frame: ${describe(view)}")
      val readyAt = System.nanoTime()
      val host = HostPaths.defaultPaths().owner()
      hostPid = host
      val hostProcess = host.flatMap(pid => ProcessHandle.of(pid).toScala)
      val appPid = ProcessHandle.current().pid()
      val frames = AtomicInteger(0)
      onFx(view.onOutput(() => { val _ = frames.incrementAndGet() }))

      // A small warm-up: the first page after a cold Pi start is not what we are measuring.
      warmUp(view, frames)

      val appCpuBefore = cpuNanos(appPid)
      val hostCpuBefore = host.map(cpuNanos).getOrElse(0L)
      val appCtxBefore = contextSwitches(appPid)
      val hostCtxBefore = host.flatMap(contextSwitches)
      val jfrApp = outDir.resolve("app.jfr")
      val jfrHost = outDir.resolve("host.jfr")
      delete(jfrApp)
      delete(jfrHost)
      startJfr(appPid, "corvi-paging-app", jfrApp)
      host.foreach(pid => startJfr(pid, "corvi-paging-host", jfrHost))

      val paint = paintPhase(window)
      val typing = typingPhase(view, frames)
      val upStep = stepPhase(view, frames, "pageup-step", KeyCode.PAGE_UP)
      val downStep = stepPhase(view, frames, "pagedown-step", KeyCode.PAGE_DOWN)
      val upBurst = burstPhase(view, frames, "pageup-burst", KeyCode.PAGE_UP)
      val downBurst = burstPhase(view, frames, "pagedown-burst", KeyCode.PAGE_DOWN)

      stopJfr(appPid, "corvi-paging-app")
      host.foreach(pid => stopJfr(pid, "corvi-paging-host"))

      val appCpuAfter = cpuNanos(appPid)
      val hostCpuAfter = host.map(cpuNanos).getOrElse(0L)
      val appCtxAfter = contextSwitches(appPid)
      val hostCtxAfter = host.flatMap(contextSwitches)
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
      val result = report(
        view,
        startedAt,
        readyAt,
        host,
        hostProcess,
        appPid,
        frames.get,
        paint,
        typing,
        upStep,
        downStep,
        upBurst,
        downBurst,
        appCpuAfter - appCpuBefore,
        hostCpuAfter - hostCpuBefore,
        appCtxBefore,
        appCtxAfter,
        hostCtxBefore,
        hostCtxAfter,
        heap,
        jfrApp,
        jfrHost
      )
      val _ = Files.writeString(outDir.resolve("result.json"), result, StandardCharsets.UTF_8)
      Right(result)
    catch
      case error: Throwable =>
        error.printStackTrace()
        Left(error.toString)
    finally
      app.foreach(window => onFx(window.close()))
      stage.foreach(window => onFx(window.close()))
      hostPid.foreach(pid => ProcessHandle.of(pid).ifPresent(handle => { handle.destroy(); () }))

  private final case class Phase(
      name: String,
      frames: Int,
      firstFrameMillis: Vector[Double],
      settleMillis: Vector[Double],
      totalMillis: Double,
      timeouts: Int
  )

  /** Sets the stage from the full-screen grid. Each step waits for the host's resize round trip, so a stale frame cannot compound the
    * adjustment; the probe records the final grid, which exposes a window-manager or scale difference.
    */
  private def resizeToGrid(view: TerminalPane, stage: Stage): Unit =
    val bounds = onFx(javafx.stage.Screen.getPrimary.getVisualBounds)
    onFx {
      stage.setWidth(bounds.getWidth)
      stage.setHeight(bounds.getHeight)
    }
    val _ = await(10.seconds)(onFx(view.frame.exists(_.size.cols > targetCols)))
    for _ <- 0 until 4 do
      onFx(view.frame).foreach { current =>
        if !nearTarget(current.size) then
          val width = onFx(stage.getWidth)
          val height = onFx(stage.getHeight)
          val wantWidth = width * targetCols.toDouble / current.size.cols
          val wantHeight = height * targetRows.toDouble / current.size.rows
          onFx {
            if current.size.cols != targetCols then stage.setWidth(wantWidth)
            if current.size.rows != targetRows then stage.setHeight(wantHeight)
          }
          val before = current.size
          val _ = await(5.seconds)(onFx(view.frame.exists(_.size != before)))
          Thread.sleep(250)
      }

  private def nearTarget(size: corvi.contract.TermSize): Boolean =
    math.abs(size.cols - targetCols) <= 3 && size.rows == targetRows

  private def warmUp(view: TerminalPane, frames: AtomicInteger): Unit =
    for code <- Vector(KeyCode.PAGE_UP, KeyCode.PAGE_DOWN) do
      val before = frames.get()
      fire(view, code)
      val _ = awaitFrames(frames, before, 2.seconds, 100, 3.seconds)
      val _ = await(2.seconds)(quietFor(view, 200))

  /** Types a few printable characters through the same KEY_TYPED path a keyboard uses and waits for each redraw. Pi's prompt is left
    * unchanged by backing the characters out again.
    */
  private final case class PaintPhase(p50: Double, p95: Double, max: Double, count: Int)

  private def paintPhase(stage: Stage): PaintPhase =
    val scene = onFx(stage.getScene)
    val times = Vector.newBuilder[Double]
    for _ <- 0 until 12 do
      val began = System.nanoTime()
      val _ = onFx(scene.snapshot(null))
      times += (System.nanoTime() - began) / 1e6
    val values = times.result()
    def percentile(fraction: Double): Double =
      values.sorted.apply(math.min(values.size - 1, math.max(0, ((values.size - 1) * fraction).round.toInt)))
    PaintPhase(percentile(0.5), percentile(0.95), values.max, values.size)

  private def paintLine(paint: PaintPhase): String =
    f"  paint: samples=${paint.count} p50_ms=${paint.p50}%.1f p95_ms=${paint.p95}%.1f max_ms=${paint.max}%.1f"

  private def typingPhase(view: TerminalPane, frames: AtomicInteger): Phase =
    val first = Vector.newBuilder[Double]
    val settle = Vector.newBuilder[Double]
    var timeouts = 0
    val initial = frames.get()
    val start = System.nanoTime()
    for _ <- 0 until 5 do
      val before = frames.get()
      val began = System.nanoTime()
      fireTyped(view, 'x')
      val (seen, firstAt, settledAt) = awaitFrames(frames, before, firstFrameTimeout, quietMillis, 8.seconds)
      if seen then
        first += (firstAt - began) / 1e6
        settle += (settledAt - began) / 1e6
      else timeouts += 1
    for _ <- 0 until 5 do
      fire(view, KeyCode.BACK_SPACE)
      Thread.sleep(20)
    val _ = await(3.seconds)(quietFor(view, 100))
    Phase("typing", frames.get() - initial, first.result(), settle.result(), (System.nanoTime() - start) / 1e6, timeouts)

  private def fireTyped(view: TerminalPane, character: Char): Unit =
    onFx(
      Event.fireEvent(
        view,
        new KeyEvent(KeyEvent.KEY_TYPED, character.toString, "", KeyCode.UNDEFINED, false, false, false, false)
      )
    )

  private def stepPhase(view: TerminalPane, frames: AtomicInteger, name: String, code: KeyCode): Phase =
    val first = Vector.newBuilder[Double]
    val settle = Vector.newBuilder[Double]
    var timeouts = 0
    val initial = frames.get()
    val start = System.nanoTime()
    for _ <- 0 until keysPerPhase do
      val before = frames.get()
      val began = System.nanoTime()
      fire(view, code)
      val (seen, firstAt, settledAt) = awaitFrames(frames, before, firstFrameTimeout, quietMillis, 8.seconds)
      if seen then
        first += (firstAt - began) / 1e6
        settle += (settledAt - began) / 1e6
      else timeouts += 1
    val total = (System.nanoTime() - start) / 1e6
    Phase(name, frames.get() - initial, first.result(), settle.result(), total, timeouts)

  private def burstPhase(view: TerminalPane, frames: AtomicInteger, name: String, code: KeyCode): Phase =
    val before = frames.get()
    val began = System.nanoTime()
    for _ <- 0 until keysPerPhase do
      fire(view, code)
      Thread.sleep(15)
    val (seen, firstAt, settledAt) = awaitFrames(frames, before, 5.seconds, 200, 15.seconds)
    val total = (System.nanoTime() - began) / 1e6
    val first = if seen then Vector((firstAt - began) / 1e6) else Vector.empty
    val settle = if seen then Vector((settledAt - began) / 1e6) else Vector.empty
    Phase(name, frames.get() - before, first, settle, total, if seen then 0 else 1)

  /** Waits for the first frame after `before`, then for a short quiet period after the last frame. */
  private def awaitFrames(
      frames: AtomicInteger,
      before: Int,
      firstTimeout: scala.concurrent.duration.Duration,
      quiet: Long,
      totalTimeout: scala.concurrent.duration.Duration
  ): (Boolean, Long, Long) =
    val began = System.nanoTime()
    val firstDeadline = began + firstTimeout.toNanos
    val totalDeadline = began + totalTimeout.toNanos
    var firstAt = -1L
    var last = before
    var lastChange = began
    var done = false
    while !done && System.nanoTime() < totalDeadline do
      val current = frames.get()
      val now = System.nanoTime()
      if current != last then
        last = current
        lastChange = now
        if firstAt < 0 then firstAt = now
      if firstAt >= 0 && now - lastChange >= quiet * 1000000L then done = true
      else if firstAt < 0 && now > firstDeadline then done = true
      else Thread.sleep(5)
    (firstAt >= 0, if firstAt >= 0 then firstAt else lastChange, lastChange)

  private def quietFor(view: TerminalPane, millis: Long): Boolean =
    val deadline = System.nanoTime() + millis * 1000000L
    var last = frameText(view)
    var lastChange = System.nanoTime()
    while System.nanoTime() < deadline do
      Thread.sleep(10)
      val current = frameText(view)
      if current != last then
        last = current
        lastChange = System.nanoTime()
    System.nanoTime() - lastChange >= millis * 1000000L

  private def frameText(view: TerminalPane): Option[String] =
    onFx(view.frame).map(frame => frame.rows.map(row => row.cells.map(_.text).mkString).mkString("\n"))

  private def describe(view: TerminalPane): String =
    val frame = onFx(view.frame)
    frame.map(f => s"size=${f.size} title=${f.modes.title} rows=${f.rows.size}").getOrElse("no frame")

  private def terminalViews(shell: ShellView): Vector[TerminalPane] =
    shell.delegate.lookup(".terminal-area") match
      case area: Parent =>
        area.getChildrenUnmodifiable.asScala.collect { case view: TerminalPane => view }.toVector
      case _ => Vector.empty

  private def fire(view: TerminalPane, code: KeyCode): Unit =
    onFx(Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)))

  private def startJfr(pid: Long, name: String, target: Path): Unit =
    runJcmd(pid, "JFR.start", s"name=$name", "settings=profile", s"filename=$target")
    Thread.sleep(100)

  private def stopJfr(pid: Long, name: String): Unit =
    runJcmd(pid, "JFR.stop", s"name=$name")

  private def runJcmd(pid: Long, args: String*): Unit =
    if Files.isExecutable(jcmd) then
      try
        val command = (Seq(jcmd.toString, pid.toString) ++ args).asJava
        val process = new ProcessBuilder(command).redirectErrorStream(true).start()
        val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        val code = process.waitFor()
        if code != 0 then System.err.println(s"jcmd ${args.mkString(" ")} (pid $pid) failed: $output")
      catch case error: Throwable => System.err.println(s"jcmd (pid $pid) failed: $error")
    else System.err.println(s"jcmd not found at $jcmd")

  private def cpuNanos(pid: Long): Long =
    ProcessHandle.of(pid).toScala.flatMap(handle => Option(handle.info.totalCpuDuration.orElse(null))).map(_.toNanos).getOrElse(0L)

  private def contextSwitches(pid: Long): Option[(Long, Long)] =
    try
      val lines = Files.readAllLines(Paths.get(s"/proc/$pid/status")).asScala
      def value(label: String): Option[Long] =
        lines.find(_.startsWith(label)).flatMap(_.split(":\\s+").lift(1)).flatMap(_.trim.toLongOption)
      for voluntary <- value("voluntary_ctxt_switches"); involuntary <- value("nonvoluntary_ctxt_switches")
      yield (voluntary, involuntary)
    catch case _: Throwable => None

  private def report(
      view: TerminalPane,
      startedAt: Long,
      readyAt: Long,
      host: Option[Long],
      hostProcess: Option[ProcessHandle],
      appPid: Long,
      frames: Int,
      paint: PaintPhase,
      typing: Phase,
      upStep: Phase,
      downStep: Phase,
      upBurst: Phase,
      downBurst: Phase,
      appCpuNanos: Long,
      hostCpuNanos: Long,
      appCtxBefore: Option[(Long, Long)],
      appCtxAfter: Option[(Long, Long)],
      hostCtxBefore: Option[(Long, Long)],
      hostCtxAfter: Option[(Long, Long)],
      heap: Long,
      appJfr: Path,
      hostJfr: Path
  ): String =
    val frame = onFx(view.frame)
    val size = frame.map(_.size.toString).getOrElse("none")
    val title = frame.map(_.modes.title).getOrElse("")
    val lines = Vector(
      s"PI_PAGING_RESULT",
      s"  ready_ms=${(readyAt - startedAt) / 1e6}",
      s"  frame=${size} target=${targetCols}x${targetRows} title=${title}",
      s"  app_pid=$appPid host_pid=${host.getOrElse(-1)} host_alive=${hostProcess.exists(_.isAlive)}",
      s"  frames=$frames app_cpu_ms=${appCpuNanos / 1e6} host_cpu_ms=${hostCpuNanos / 1e6} app_heap_mb=${heap / 1024 / 1024}",
      s"  app_context=${describeContext(appCtxBefore, appCtxAfter)} host_context=${describeContext(hostCtxBefore, hostCtxAfter)}",
      paintLine(paint),
      s"  app_jfr=$appJfr",
      s"  host_jfr=$hostJfr",
      phaseLine(typing),
      phaseLine(upStep),
      phaseLine(downStep),
      phaseLine(upBurst),
      phaseLine(downBurst),
      "  result_json=" + outDir.resolve("result.json")
    )
    lines.mkString("\n")

  private def phaseLine(phase: Phase): String =
    def percentile(values: Vector[Double], fraction: Double): String =
      if values.isEmpty then "n/a"
      else f"${values.sorted.apply(math.min(values.size - 1, math.max(0, ((values.size - 1) * fraction).round.toInt)))}%.1f"
    def number(value: Double): String = f"$value%.1f"
    s"  ${phase.name}: frames=${phase.frames} total_ms=${number(phase.totalMillis)} first_p50_ms=${percentile(phase.firstFrameMillis, 0.5)} first_p95_ms=${percentile(phase.firstFrameMillis, 0.95)} settle_p50_ms=${percentile(phase.settleMillis, 0.5)} settle_p95_ms=${percentile(phase.settleMillis, 0.95)} timeouts=${phase.timeouts}"

  private def describeContext(before: Option[(Long, Long)], after: Option[(Long, Long)]): String =
    (before, after) match
      case (Some((v0, i0)), Some((v1, i1))) => s"voluntary=${v1 - v0} involuntary=${i1 - i0}"
      case _                                => "n/a"

  private def delete(path: Path): Unit =
    val _ = Files.deleteIfExists(path)

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
      if !latch.await(120, TimeUnit.SECONDS) then throw new IllegalStateException("the FX thread did not answer")
      result.get() match
        case Right(value) => value
        case Left(error)  => throw error

  private def await(timeout: scala.concurrent.duration.Duration)(condition: => Boolean): Boolean =
    val deadline = System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition
    satisfied

  extension [A](optional: java.util.Optional[A]) private def toScala: Option[A] = if optional.isPresent then Some(optional.get) else None
