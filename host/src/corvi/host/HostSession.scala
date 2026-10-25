package corvi.host

import com.pty4j.{PtyProcess, PtyProcessBuilder}
import com.techsenger.jeditermfx.core.emulator.JediEmulator
import com.techsenger.jeditermfx.core.model.{StyleState, TerminalTextBuffer}
import com.techsenger.jeditermfx.core.util.TermSize as CoreTermSize
import com.techsenger.jeditermfx.core.{RequestOrigin, TerminalColor, TerminalOutputStream, TextStyle, TtyBasedArrayDataStream}
import corvi.contract.*
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import kyo.*
// The contract has a `Frame` as well (a client's screen); this file means kyo's source position, so it says so.
import kyo.Frame
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*

/** One headless terminal: a login shell on a PTY, jeditermfx-core's emulator interpreting it, and the screen that comes out. There is no
  * JavaFX here — the core's VT engine is the same one the old UI drove, and it keeps the screen itself.
  *
  * A session is created inside the scope that owns it (`open`), read from any thread (`snapshot`, `damageSince`, `history`), and killed by
  * that scope: the PTY is a `Scope.acquireRelease`, the reader is a `Fiber`, and the process is destroyed before the fiber is interrupted
  * so a blocked PTY read cannot deadlock the scope.
  *
  * One piece of state crosses threads besides the display: the failure that ended the reader, a plain value behind an `AtomicReference`. It
  * is data, not a lifecycle flag — the session's lifetime is the scope's — and it is an atomic because the writer is a fiber parked in a
  * blocking read, which no effect system can serialise for us.
  */
private[corvi] final class HostSession private[host] (
    val id: SessionId,
    val index: Int,
    private val theme: TerminalTheme,
    private val process: PtyProcess,
    private val connector: PtyProcessTtyConnector,
    private val buffer: TerminalTextBuffer,
    private val terminal: KeySynchronizedTerminal,
    private val display: RecordingDisplay
):
  /** The terminal's answers to device queries go back to the shell, as they would on a real screen. */
  private[host] val output: TerminalOutputStream = new TerminalOutputStream:
    override def sendBytes(response: Array[Byte], userInput: Boolean): Unit = connector.write(response)
    override def sendString(string: String, userInput: Boolean): Unit = connector.write(string)

  /** The shell's exit code, once it has one: what `session.exited` reports. */
  def exitCode: Option[Int] = if process.isAlive then None else Some(process.exitValue())

  /** Why the reader stopped, when it was not a clean end: the protocol reports it as the session's exit, and the host's scope is unharmed.
    */
  private val failure: AtomicReference[Option[Throwable]] = new AtomicReference(None)

  private val logger = LoggerFactory.getLogger(classOf[HostSession])

  /** Advances whenever the emulator applies output or the grid is resized. The push loop samples a screen only when this changes, so an
    * idle session costs no snapshots at all instead of one every frame.
    */
  private val screenGeneration: AtomicLong = new AtomicLong(0)

  /** Wakes the push loop when the screen may have changed. Capacity one: every wake while one is pending is the same wake, and the push
    * loop samples the latest frame when it gets to it.
    */
  private val screenChanges: LinkedBlockingQueue[Unit] = new LinkedBlockingQueue[Unit](1)

  /** Blocks until the emulator applies output or the grid is resized. The push loop is the only reader, so a wake cannot be lost. Returns
    * false when the wait was interrupted, which is how a closing host stops its push loop without spinning on an interrupted thread.
    */
  private[host] def awaitScreenChange(): Boolean =
    try
      screenChanges.take()
      true
    catch case _: InterruptedException => false

  /** The shell's process id, which is what a reattaching client compares to be sure it found the same shell. */
  def shellPid: Long = process.pid()

  def info: SessionInfo =
    val running = process.isAlive
    val ended = if running then None else Some(Ended(code = exitCode, closed = false, reason = error.map(_.toString)))
    ScreenReader.info(id, index, buffer, running, shellPid = process.pid(), ended = ended)

  /** The visible grid, the cursor, the modes, the history's depth and the theme, as one value, read at one instant. */
  def snapshot(): Snapshot = ScreenReader.snapshot(id, buffer, display, theme, terminal.uriFor)

  /** Rows from the scrollback, newest first, `from` counting back from the most recent (1-based). */
  def history(from: Int, count: Int): Vector[Row] = ScreenReader.history(buffer, from, count, terminal.uriFor)

  /** The frame as it is now, and the delta from `before` to it. One read, so the two describe the same instant: taking a snapshot and
    * diffing against it separately would compare a frame with itself when output arrives in between.
    */
  def frameSince(before: Snapshot): (Snapshot, Damage) =
    val current = snapshot()
    (current, ScreenReader.damage(before, current))

  /** The rows that changed since `before`, plus whatever else in the frame did: what a frontend that drew that snapshot has to repaint. */
  def damageSince(before: Snapshot): Damage = frameSince(before)._2

  /** How many screen changes this session has applied; for the probes. */
  private[host] def generation: Long = screenGeneration.get

  /** Records a screen change and wakes the push loop. The token is what makes a change arriving between a sample and the next wait
    * impossible to miss; the generation is kept for the probes and as a cheap description of how much output was applied.
    */
  private def markScreenChanged(): Unit =
    val _ = screenGeneration.incrementAndGet()
    val _ = screenChanges.offer(())

  /** The failure that ended the reader, if it was not a clean end. */
  def error: Option[Throwable] = failure.get

  /** Ends the session's shell. Closing the connector first wakes the driver's blocked read, so the forked fiber can finish instead of
    * holding the scope open; closing the PTY then ends the shell. Idempotent, so a client may close and the scope still release.
    */
  def close(): Unit =
    connector.close()
    process.destroy()
    markScreenChanged()

  /** Asks for a new size: the grid first, then the PTY.
    *
    * `JediTerminal.resize` runs inside `TerminalTextBuffer.modify`, which is the same lock the emulator takes for each operation it parses,
    * so a resize cannot interleave with a half-interpreted escape sequence - it is serialised with the parse exactly as the parse's own
    * steps are. Handing it to the driver instead (a pending size applied before the next parse) was tried and has a hole: a session sitting
    * at its prompt produces no output, so the driver stays parked in its read and the grid keeps the old size until something happens to
    * print. The lock is the right place to serialise this, not the thread.
    */
  def resize(size: TermSize): Unit =
    val target = CoreTermSize(size.cols, size.rows)
    terminal.resize(target, RequestOrigin.User)
    connector.resize(target)
    markScreenChanged()

  /** Types at the shell, for the headless checks. */
  def write(text: String): Unit = connector.write(text)

  /** Sends a non-text key in the terminal's current key modes. `KeySynchronizedTerminal.codeForKey` holds the monitor the emulator's mode
    * setters use, so DECCKM cannot change the encoder map under this read; the write happens after the lock is released.
    */
  def key(input: KeyInput): Unit =
    KeyEncoder.encode(input, terminal.codeForKey).foreach(bytes => connector.write(bytes))

  /** Reports the window's focus only while the program asked for `1004`; every other mode ignores the transition. The host tracks 1004
    * separately from the mouse mode because a program may enable both. `MouseMode.Focus` here is only the encoder's name for the enabled
    * state; the display's mouse mode never carries it.
    */
  def focus(focused: Boolean): Unit =
    val mode = if display.state.focusReporting then MouseMode.Focus else MouseMode.Off
    FocusEncoder.encode(focused, mode).foreach(bytes => connector.write(bytes))

  /** Sends a pointer event in the session's current tracking mode. The mode is read from the display at the moment the event arrives, so a
    * program that enables SGR or switches from click to any-motion tracking gets the encoding it asked for.
    */
  def mouse(input: MouseInput): Unit =
    val state = display.state
    MouseEncoder.encode(input, state.mouseMode, state.mouseFormat).foreach(bytes => connector.write(bytes))

  /** jeditermfx-core's emulator loop, on the fiber that owns this session: the work `TerminalStarter.doStartEmulator` does, without the
    * thread it starts for it.
    *
    * The buffer's lock is taken by the engine, per operation: every path that writes cells (`writeCharacters`, `resize` through
    * `TerminalTextBuffer.modify`, the erase and insert sequences) locks the buffer itself, and the readers take the same lock, so a
    * snapshot cannot see half an escape sequence. That is also why this loop must *not* hold the lock around `emulator.next()`: `next()`
    * refills its character buffer from the PTY when it runs dry, so it can park in a blocking read, and a reader would then wait for output
    * that only it could have caused.
    *
    * An exception ends *this* session and nothing else: it is recorded and logged, never thrown at the scope, so one broken shell cannot
    * take the host's other sessions down with it.
    */
  private[host] def drive(): Unit =
    val stream = new FocusTrackingDataStream(
      new TtyBasedArrayDataStream(connector),
      enabled => display.setFocusReporting(enabled)
    )
    val emulator = new JediEmulator(stream, terminal)
    try
      while emulator.hasNext do
        emulator.next()
        markScreenChanged()
    catch
      // The read was interrupted: that is how a session ends, not a failure.
      case _: InterruptedIOException => ()
      case error: Throwable          =>
        // An exception after the connector went away is teardown noise; one while it is still connected is a real failure.
        if connector.isConnected then
          val _ = failure.set(Some(error))
          logger.error(s"session ${id.value} stopped reading", error)
    finally
      // A dead shell must not leave a cursor blinking on a screen nobody will update again. Wake the push loop so it can report the end
      // even when the last output was already sampled.
      markScreenChanged()
      terminal.disconnected()

private[corvi] object HostSession:
  /** The history Corvi caps at, the size a session starts at, and the colours a cell with none of its own is drawn in.
    */
  private val historyLines: Int = 5000
  private val initialColumns: Int = 80
  private val initialRows: Int = 24
  private val theme: TerminalTheme = TerminalTheme(foreground = Rgb(230, 237, 243), background = Rgb(13, 17, 23))

  /** Opens a login shell and starts its reader. The scope owns both, and the closer is registered *after* the fiber so that it runs
    * *before* the fiber when the scope closes: it closes the connector, which wakes the driver's blocked read, so the fiber can finish
    * instead of holding the scope open (the deadlock chapter 170 warns about).
    *
    * A shell that cannot start is an abort rather than a panic, so the protocol can report "could not start" the same way it reports an
    * exit.
    */
  def open(id: SessionId, index: Int, cwd: Path, command: Seq[String])(using
      Frame
  ): HostSession < (Async & Scope & Abort[Throwable]) =
    for
      process <- Scope.acquireRelease(Abort.catching(spawn(cwd, command)))(proc => Sync.defer(proc.destroy()))
      session <- Sync.defer(create(id, index, process))
      _ <- Fiber.init(Sync.defer(session.drive()))
      _ <- Scope.ensure(Sync.defer(session.close()))
    yield session

  private def create(id: SessionId, index: Int, process: PtyProcess): HostSession =
    val connector = new PtyProcessTtyConnector(process, StandardCharsets.UTF_8)
    val styleState = new StyleState()
    styleState.setDefaultStyle(new TextStyle(rgb(theme.foreground), rgb(theme.background)))
    val buffer = new TerminalTextBuffer(initialColumns, initialRows, styleState, historyLines, null)
    val display = new RecordingDisplay()
    val terminal = new KeySynchronizedTerminal(display, buffer, styleState)
    val session = new HostSession(id, index, theme, process, connector, buffer, terminal, display)
    // The shell can outlive the last output by a scheduling tick: wake the push loop when the process actually exits, so the end is
    // reported even when there is no PTY byte or resize to wake it.
    val _ = process.onExit().thenRun(() => session.markScreenChanged())
    terminal.setTerminalOutput(session.output)
    // KeyEncoder owns Alt/Meta: cursor and function keys need xterm's `CSI 1;N` suffix, while the engine's own Alt mode would send an
    // escape byte followed by the unmodified key. Turning the engine's prefix off keeps that rule in one place.
    terminal.setAltSendsEscape(false)
    session

  private def rgb(colour: Rgb): TerminalColor = TerminalColor.rgb(colour.red, colour.green, colour.blue)

  /** The login shell the environment names, which is what a normal session runs. */
  def loginShell: Seq[String] =
    Seq(Option(java.lang.System.getenv("SHELL")).filter(_.nonEmpty).getOrElse("/bin/sh"), "-l")

  /** A command in the session's directory, on a real PTY: the app's environment so it finds the PATH the user has, plus the one variable
    * that makes the pty a colour terminal. The command is a parameter so a caller can prove what a shell that cannot start does.
    */
  private def spawn(cwd: Path, command: Seq[String]): PtyProcess =
    val environment: java.util.Map[String, String] =
      (java.lang.System.getenv().asScala.toMap + ("TERM" -> "xterm-256color")).asJava
    new PtyProcessBuilder()
      .setCommand(command.toArray)
      .setEnvironment(environment)
      .setDirectory(cwd.toString)
      .setInitialColumns(initialColumns)
      .setInitialRows(initialRows)
      .start()
