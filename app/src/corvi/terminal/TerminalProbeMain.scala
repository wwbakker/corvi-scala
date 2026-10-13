package corvi.terminal

import java.io.IOException
import corvi.{AppWindow, CorviShell}
import java.nio.file.Paths
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.stage.WindowEvent
import scala.jdk.CollectionConverters.*
import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scalafx.scene.layout.StackPane
import scalafx.stage.Stage

/** Drives real PTYs through `TerminalSession` and reports what the shells do, because a screenshot cannot prove a pseudo-terminal:
  *
  * ./mill app.runMain corvi.terminal.TerminalProbeMain
  *
  * One session sits in a real window and is driven the way a keyboard would: `stty size`, a command whose output only the shell can
  * produce, the *window* resized both ways with the PTY checked after each, then `exit`. A second session is closed the way the app's quit
  * handler closes one, and the shell and the emulator's threads are checked to be gone. Any failure exits non-zero; on success the probe
  * leaves through `Platform.exit()` with no `System.exit` behind it, so a stray non-daemon thread would show up as a process that never
  * returns. A tool entry point, not the app.
  */
private[corvi] object TerminalProbeMain extends JFXApp3:
  private val timeoutSeconds: Long = 30

  /** The session's pane in a real window, so the checks resize the window rather than reaching past it to the connector.
    */
  private final case class ProbeWindow(stage: Stage, content: StackPane)

  override def start(): Unit =
    // A probe drives its own window; there is nothing for JFXApp3 to show. Implicit exit is off for
    // the same reason the app turns it off: the probe closes a window as part of its checks, and
    // that must not take the whole toolkit — and the rest of the checks — down with it.
    JFXApp3.AutoShow = false
    Platform.setImplicitExit(false)
    // A platform thread for the probe body, so the checks do not depend on virtual-thread scheduling
    // while they marshal onto the FX thread. It matters that this one is a *daemon* —
    // `Thread.ofPlatform()` inherits the creating thread's daemon status, and the FX thread is a
    // daemon — while JediTermFX's emulator thread is created with `daemon = false` on purpose. A
    // session left running is therefore what would hold the JVM open, which is what the leak checks
    // below look for rather than the process simply returning.
    val _ = Thread.ofPlatform().name("corvi-pty-probe").start(() => run())

  private def run(): Unit =
    var failures = 0
    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then failures += 1
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(6).foreach(line => println(s"      | $line"))

    val cwd = Paths.get(sys.props("user.dir"))
    val reads = new AtomicInteger(0)
    val exited = new CountDownLatch(1)
    val session = onFx(
      TerminalSession.open(
        id = 0,
        label = "probe",
        cwd = cwd,
        onOutput = _ => { val _ = reads.incrementAndGet() },
        onExited = _ => exited.countDown()
      )
    )
    println(s"session ${session.id} (${session.label}) in $cwd, shell pid ${session.pid}")

    val window = onFx(windowFor(session))
    try
      val mounted = awaitTrue(onFx(mountedInWindow(session, window)), 10)
      report("the session's pane is the widget's own pane, mounted in the window", mounted, paneDetail(session, window))

      val prompt = await(session, _.trim.nonEmpty, timeoutSeconds)
      report("the shell spawned and wrote a prompt", prompt.isDefined, prompt.getOrElse(""))

      // The builder starts the PTY at 80x24; the window's first layout replaces it, so waiting for a
      // size other than that default is already the layout reaching the PTY.
      val settled = ptySize(session, Some((24, 80)))
      report("the window's layout replaced the PTY's 80x24 default", settled.exists(_ != (24, 80)), sizeDetail("start", settled))

      typeOn(session, "echo CORVI_$((6*7))_OK")
      val ran = await(session, _.contains("CORVI_42_OK"), timeoutSeconds)
      report("a command ran and its output was read back", ran.isDefined, ran.getOrElse(""))

      // Window-driven resize, the plan's behaviour 8: the stage grows, layout hands the panel its new
      // size, the panel posts the resize and our connector ioctls the PTY — all through the delegate
      // wrap above. The widget merges resizes on a 500 ms delay, so the shell is polled.
      resizeWindow(window, 1150, 700)
      val grown = ptySize(session, settled)
      val grew = for startSize <- settled; grownSize <- grown yield grownSize._1 > startSize._1 && grownSize._2 > startSize._2
      report("growing the window grew the PTY", grew.getOrElse(false), sizeDetail("grown", grown))

      resizeWindow(window, 640, 360)
      val shrunk = ptySize(session, grown.orElse(settled))
      val shrank = for large <- grown; small <- shrunk yield small._1 < large._1 && small._2 < large._2
      report("shrinking the window shrank the PTY", shrank.getOrElse(false), sizeDetail("shrunk", shrunk))

      // Busy the FX thread first: the fork's `onExited` then has to sit queued while the session's
      // scope tears itself down, which is exactly the race that used to swallow a natural exit.
      occupyFxThread(2)
      typeOn(session, "exit")
      val ended = exited.await(timeoutSeconds, TimeUnit.SECONDS)
      report("exit ended the shell and fired onExited with the FX thread busy", ended && !session.isAlive, "")
      report(s"output arrived on the way (${reads.get} reads)", reads.get > 0, "")
    catch
      case failure: Throwable =>
        failures += 1
        println(s"FAIL  the probe threw: $failure")
    finally
      // Whatever happened above, nothing of ours outlives the probe.
      if session.isAlive then onFx(session.close())

    // Teardown, the way the app's quit handler does it: on the FX thread, and finished before the
    // toolkit goes. A shell that is still there, or an emulator thread still running, is a leak
    // rather than a timing detail.
    val secondExits = new CountDownLatch(1)
    val second = onFx(
      TerminalSession.open(
        id = 1,
        label = "probe-close",
        cwd = cwd,
        onOutput = _ => (),
        onExited = _ => secondExits.countDown()
      )
    )
    val secondPrompt = await(second, _.trim.nonEmpty, timeoutSeconds)
    report("a second session spawned for the teardown check", secondPrompt.isDefined, secondPrompt.getOrElse(""))

    onFx(second.close())
    val shellGone = awaitTrue(!processAlive(second.pid), 5)
    report("close() killed the shell", shellGone, s"shell pid ${second.pid} no longer alive: $shellGone")
    val noExit = !secondExits.await(2, TimeUnit.SECONDS)
    report("close() did not report an exit", noExit, "")
    val threadsGone = awaitTrue(sessionThreads.isEmpty, 10)
    report("no TerminalEmulator/JediTermFX thread left behind", threadsGone, if threadsGone then "none" else sessionThreads.mkString(", "))

    // Negative control for the leak check above: a session that is deliberately left running still
    // shows its own threads, so a "none left behind" result means something. It is closed straight
    // after, so the probe still leaves nothing.
    val control = onFx(
      TerminalSession.open(id = 2, label = "probe-control", cwd = cwd, onOutput = _ => (), onExited = _ => ())
    )
    val controlPrompt = await(control, _.trim.nonEmpty, timeoutSeconds)
    report("a third session spawned for the leak control", controlPrompt.isDefined, controlPrompt.getOrElse(""))
    val controlSeen = awaitTrue(emulatorThreads.nonEmpty, 10)
    report("the leak check sees a live session's emulator thread", controlSeen, emulatorThreads.mkString(", "))
    onFx(control.close())
    val controlGone = awaitTrue(sessionThreads.isEmpty, 10)
    report("and sees them go once the session is closed", controlGone, if controlGone then "none" else sessionThreads.mkString(", "))

    onFx(window.stage.close())

    // The app's quit, in full: a real close request on a window built and wired exactly as `Main`
    // builds it, so the handler under test is `AppWindow.quit` itself. `Platform.exit()` ends this
    // JVM from under the probe — ScalaFX's launcher exits the process, it does not merely stop the
    // toolkit — so the checks that must look *after* the quit are a shutdown hook, which still gets
    // to look and to say so before the process goes. Everything in there is plain Java: the toolkit
    // is gone by design, which is the point of the fix.
    val app = onFx(appWindow())
    val appShells = awaitTrue(childShells.nonEmpty, 15)
    report("the app's shell is running under this JVM", appShells, childShells.map(_.pid.toString).mkString(", "))
    val _ = Runtime.getRuntime.addShutdownHook(
      Thread
        .ofPlatform()
        .name("corvi-quit-check")
        .unstarted(() =>
          val shellsGone = awaitTrue(childShells.isEmpty, 5)
          val threadsGone = awaitTrue(sessionThreads.isEmpty, 5)
          report("the app's quit left no shell behind", shellsGone, childShells.map(_.pid.toString).mkString(", "))
          report(
            "the app's quit left no TerminalEmulator/JediTermFX thread behind",
            threadsGone,
            if threadsGone then "none" else sessionThreads.mkString(", ")
          )
          println(if failures == 0 then "all PTY checks passed" else s"$failures PTY check(s) failed")
          // System.exit is not available from a shutdown hook; halt is, and it is the only way these
          // last checks can fail the command rather than only printing FAIL.
          Runtime.getRuntime.halt(if failures == 0 then 0 else 1)
        )
    )
    println("firing the close request; the app's quit is expected to end this process on its own")
    onFx(fireCloseRequest(app))
    // If the quit does not end the JVM, say so rather than hanging the command.
    Thread.sleep(10_000)
    println("FAIL  the app's quit did not end this JVM")
    System.exit(1)

  /** A `CorviShell` in a window wired the way `Main` wires it, so its close request runs the app's own quit.
    */
  private def appWindow(): Stage =
    val shell = new CorviShell
    val stage = AppWindow.stage(AppWindow.scene(shell), () => AppWindow.quit(shell))
    stage.show()
    stage

  private def fireCloseRequest(stage: Stage): Unit =
    val _ = stage.delegate.fireEvent(new WindowEvent(stage.delegate, WindowEvent.WINDOW_CLOSE_REQUEST))

  /** The shells this JVM still has as children, as the OS sees them — the orphan check `pgrep -f` would do, scoped to us so the user's own
    * shells are not counted.
    */
  private def childShells: Seq[ProcessHandle] =
    val shell = Paths.get(Option(System.getenv("SHELL")).filter(_.nonEmpty).getOrElse("/bin/sh")).getFileName.toString
    ProcessHandle.current.descendants.iterator.asScala.toSeq.filter(_.info.commandLine.orElse("").contains(shell))

  /** A window around the session's pane. */
  private def windowFor(session: TerminalSession): ProbeWindow =
    val content = new StackPane:
      children = Seq(session.pane)
    val stage = new Stage:
      title = "corvi — pty probe"
      width = 900
      height = 520
      scene = new Scene(content)
    stage.show()
    ProbeWindow(stage, content)

  private def resizeWindow(window: ProbeWindow, width: Double, height: Double): Unit =
    onFx {
      window.stage.width = width
      window.stage.height = height
    }

  /** Whether the session's node really is the widget's own pane, as a direct child of the window's content: a delegate wrap shows up as the
    * parent *being* that content pane; a nesting would show up as the parent being some wrapper.
    */
  private def mountedInWindow(session: TerminalSession, window: ProbeWindow): Boolean =
    val widgetPane = session.pane.delegate
    widgetPane.getWidth > 0 && widgetPane.getHeight > 0 && (widgetPane.getParent eq window.content.delegate)

  private def paneDetail(session: TerminalSession, window: ProbeWindow): String =
    val widgetPane = session.pane.delegate
    s"widget pane ${widgetPane.getWidth.toInt}x${widgetPane.getHeight.toInt}, parent is the window content: " +
      s"${widgetPane.getParent eq window.content.delegate}"

  /** Types `stty size` until the shell reports a size other than the last one. One typing is not enough: the new size reaches the PTY only
    * after a layout pass and the widget's 500 ms resize merge, so the first answer can still be the old size.
    */
  private def ptySize(session: TerminalSession, previous: Option[(Int, Int)]): Option[(Int, Int)] =
    val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
    var size: Option[(Int, Int)] = None
    var waiting = true
    while waiting && System.nanoTime() < deadline do
      typeOn(session, "stty size")
      await(session, screen => sizeIn(screen).exists(changed(previous)), 3) match
        case Some(screen) =>
          size = sizeIn(screen)
          waiting = false
        case None => ()
    size

  private def changed(previous: Option[(Int, Int)])(size: (Int, Int)): Boolean = !previous.contains(size)

  /** The last `rows cols` line the shell printed — its own view of the PTY's size. */
  private def sizeIn(screen: String): Option[(Int, Int)] =
    screen.linesIterator.map(_.trim).filter(_.matches("\\d+ \\d+")).toSeq.lastOption.map { line =>
      val parts = line.split(" ")
      (parts(0).toInt, parts(1).toInt)
    }

  private def sizeDetail(label: String, size: Option[(Int, Int)]): String =
    size match
      case Some((rows, cols)) => s"$label: stty size reports $rows rows x $cols cols"
      case None               => s"$label: stty size said nothing"

  /** Types a command and presses return, the way a keyboard would. */
  private def typeOn(session: TerminalSession, command: String): Unit =
    try session.tty.write(command + "\n")
    catch case e: IOException => throw new IllegalStateException(s"writing [$command] to the shell failed", e)

  /** JediTermFX's own threads, by the names its code gives them: the emulator runs on `TerminalEmulator-<connector>` (ours is
    * `TerminalEmulator-Local`), and its executors are `JediTerm-*` and `JediTerm-fast-job-*`. The emulator thread is created with
    * `daemon = false`, so one left behind holds the JVM open as well as leaking a shell's worth of work.
    */
  private def sessionThreads: Seq[String] = (emulatorThreads ++ executorThreads).sorted

  /** The one that does the work: `TerminalEmulator-Local` for a session of ours. */
  private def emulatorThreads: Seq[String] = threadsNamed("TerminalEmulator")

  private def executorThreads: Seq[String] = threadsNamed("JediTerm")

  private def threadsNamed(containing: String): Seq[String] =
    Thread.getAllStackTraces.keySet.asScala.toSeq.map(_.getName).filter(_.contains(containing)).sorted

  /** Busies the FX thread, so anything marshalled while it runs stays queued. */
  private def occupyFxThread(seconds: Long): Unit =
    val busy = new CountDownLatch(1)
    val _ = Platform.runLater(() =>
      busy.countDown()
      Thread.sleep(seconds * 1000)
    )
    val _ = busy.await(timeoutSeconds, TimeUnit.SECONDS)

  /** Whether the OS still has that process — what `pgrep -f` would see. */
  private def processAlive(pid: Long): Boolean =
    val handle = ProcessHandle.of(pid)
    handle.isPresent && handle.get.isAlive

  /** Polls the emulated screen until it says what is expected, or the timeout runs out. */
  private def await(session: TerminalSession, expected: String => Boolean, seconds: Long): Option[String] =
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    var matched: Option[String] = None
    var waiting = true
    while waiting && System.nanoTime() < deadline do
      val text = onFx(session.screenText)
      if expected(text) then
        matched = Some(text)
        waiting = false
      else Thread.sleep(50)
    matched

  private def awaitTrue(what: => Boolean, seconds: Long): Boolean =
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    var ok = what
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(50)
      ok = what
    ok

  /** Reads or touches JavaFX state from the probe's own thread, on the FX thread. A stuck FX thread is a failure, not a null result.
    */
  private def onFx[A](body: => A): A =
    val result = new AtomicReference[A]()
    val failure = new AtomicReference[Throwable]()
    val done = new CountDownLatch(1)
    Platform.runLater(() =>
      try result.set(body)
      catch case throwable: Throwable => failure.set(throwable)
      finally done.countDown()
    )
    if !done.await(timeoutSeconds, TimeUnit.SECONDS) then
      throw new IllegalStateException("the JavaFX thread did not answer within the timeout")
    Option(failure.get).foreach(throwable => throw throwable)
    result.get
