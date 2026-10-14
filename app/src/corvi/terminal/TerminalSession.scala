package corvi.terminal

import com.pty4j.{PtyProcess, PtyProcessBuilder}
import com.techsenger.jeditermfx.core.TtyConnector
import com.techsenger.jeditermfx.ui.{DefaultHyperlinkFilter, JediTermFxWidget}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*
import scalafx.scene.layout.{Pane as SfxPane}

/** One terminal: a login shell on a real PTY, and JediTermFX emulating the VT side of it.
  *
  * The session is *created* on the JavaFX thread — the widget is a JavaFX node — and *watched* by a fork inside the application's Ox scope
  * (see `SessionForker` for why the FX thread cannot start that fork itself). `watch` is the watcher's body; the fork's lifetime is the
  * session's lifetime, and closing the connector is what ends the shell the fork is waiting on.
  */
private[corvi] final class TerminalSession(
    val id: Int,
    val label: String,
    private val widget: JediTermFxWidget,
    private val connector: PtyProcessTtyConnector,
    private val process: PtyProcess,
    private val onExited: Int => Unit
):
  import TerminalSession.Lifecycle

  /** The one piece of state the session keeps: whether the shell is running, was closed by the user, or ended by itself. It decides two
    * things — whether `onExited` fires, and nothing else needs a flag.
    */
  private val lifecycle: AtomicReference[Lifecycle] = new AtomicReference(Lifecycle.Open)

  /** The node the terminal paints into — the well's content.
    *
    * `scalafx.scene.layout.Pane` has only the delegate constructor, so this is a *wrap* of JediTermFX's own pane, not a pane inside a pane:
    * the well's `StackPane` sizes the widget's pane directly, and a window resize reaches the PTY through it — panel layout → `postResize`
    * → `connector.resize` → `PtyProcess.setWinSize`.
    */
  val pane: SfxPane = new SfxPane(widget.getPane)

  /** The shell's process id, for the log and the checks. */
  def pid: Long = process.pid()

  /** The emulated screen as text, empty when the buffer has nothing to give — `getScreenLines` can answer `null` under load, and an absent
    * screen is not a failure. FX-thread-only: the buffer is the rendering model, written by the widget's own thread and read by the panel.
    */
  def screenText: String = Option(widget.getTerminalTextBuffer.getScreenLines).getOrElse("")

  /** The connection to the shell, for the checks that need to type at it. */
  def tty: TtyConnector = widget.getTtyConnector

  /** Puts the keyboard in the terminal — what selecting its window does. The canvas is the node the widget gives focus to when the user
    * clicks the terminal, so it is the node to focus here too. FX-thread-only.
    */
  def requestFocus(): Unit = widget.getTerminalPanel.getCanvas.requestFocus()

  def isAlive: Boolean = process.isAlive

  /** Ends the session because the user asked: the shell is killed here and now, and the watcher in the app scope wakes on the dead process
    * and tears the widget down. Idempotent, and safe on the FX thread, which is where quitting calls it — teardown has to be *done* before
    * `Platform.exit()`, not raced against it. Closing does not report an exit: `onExited` is for a shell that ended on its own.
    */
  def close(): Unit =
    if lifecycle.compareAndSet(Lifecycle.Open, Lifecycle.Closing) then dispose()

  /** The watcher's body, run as a fork in the application's scope: block until the shell is gone — because it exited, or because `close`
    * destroyed it — report a shell that ended by itself, and tear down.
    */
  def watch(): Unit =
    try
      val _ = process.waitFor()
      // The shell ended by itself only if nobody asked it to close. The CAS comes first, so a close that
      // lands while the exit is being reported still wins: `close` sees `Exited` and does not dispose twice,
      // and the report a moment later is a no-op if that window has already gone (`ShellState.ended` on an
      // absent id). The marshalling is guarded like the other two bridges — a toolkit on its way out must not
      // fail the app's scope.
      if lifecycle.compareAndSet(Lifecycle.Open, Lifecycle.Exited) then
        try Platform.runLater(() => onExited(id))
        catch case _: IllegalStateException => ()
    catch case _: InterruptedException => ()
    finally dispose()

  /** Kills the shell and stops the emulator. The connector goes first, because it is the only thing that actually destroys the shell, so
    * nothing after it may be able to skip it. Both closes are idempotent (`ProcessTtyConnector.close` destroys and closes streams that
    * tolerate being closed twice; the widget's `close` stops a timeline and shuts executors down once), which is why this needs no guard of
    * its own and can run from either thread.
    */
  private def dispose(): Unit =
    connector.close()
    if Platform.isFxApplicationThread then stopWidget()
    else
      try Platform.runLater(() => stopWidget())
      catch case _: Throwable => () // the toolkit is already gone; the JVM is on its way out

  private def stopWidget(): Unit =
    try widget.close()
    catch case failure: Throwable => TerminalSession.logger.warn(s"session $id: closing the widget failed", failure)

private[corvi] object TerminalSession:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Where a session is in its life. One value, so "the user closed it" and "it exited" cannot both be true or both be missing.
    */
  enum Lifecycle:
    case Open, Closing, Exited

  /** The size a fresh PTY starts at; the widget resizes it from the panel's real size afterwards. */
  private val initialColumns: Int = 80
  private val initialRows: Int = 24

  /** Starts a login shell on a real PTY and its emulator. Runs on the JavaFX thread, because the widget is a JavaFX node; the caller then
    * hands the session to `SessionForker`, which forks `watch` into the application's scope.
    *
    * A failure here leaves nothing behind: a shell must not outlive a session that never got to exist.
    */
  def create(id: Int, label: String, cwd: Path, onOutput: Int => Unit, onExited: Int => Unit): TerminalSession =
    val process = spawn(cwd)
    val connector =
      try new PtyProcessTtyConnector(process, StandardCharsets.UTF_8, () => onOutput(id))
      catch
        case failure: Throwable =>
          process.destroy()
          throw failure
    val widget =
      try startWidget(connector)
      catch
        case failure: Throwable =>
          connector.close()
          throw failure
    new TerminalSession(id, label, widget, connector, process, onExited)

  /** The widget is a JavaFX node, so this runs on the caller's (FX) thread; any failure is the caller's cue to destroy the process it was
    * handed.
    */
  private def startWidget(connector: PtyProcessTtyConnector): JediTermFxWidget =
    val widget = new JediTermFxWidget(initialColumns, initialRows, new CorviSettingsProvider())
    widget.setTtyConnector(connector)
    widget.addHyperlinkFilter(new DefaultHyperlinkFilter())
    widget.start()
    widget

  /** A login shell in the session's directory, on a real PTY. The environment is the app's own, so the shell finds the PATH the user has;
    * `TERM` is what makes it a colour terminal.
    */
  private def spawn(cwd: Path): PtyProcess =
    val shell = Option(System.getenv("SHELL")).filter(_.nonEmpty).getOrElse("/bin/sh")
    // The env is the app's own, plus the one variable that makes the pty a colour terminal; `asJava` of an
    // immutable map, so the bridge to pty4j's Java API stays a conversion rather than a mutable collection.
    val environment: java.util.Map[String, String] =
      (System.getenv().asScala.toMap + ("TERM" -> "xterm-256color")).asJava
    new PtyProcessBuilder()
      .setCommand(Array(shell, "-l"))
      .setEnvironment(environment)
      .setDirectory(cwd.toString)
      .setInitialColumns(initialColumns)
      .setInitialRows(initialRows)
      .start()
