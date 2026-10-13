package corvi.terminal

import com.pty4j.{PtyProcess, PtyProcessBuilder}
import com.techsenger.jeditermfx.core.TtyConnector
import com.techsenger.jeditermfx.ui.{DefaultHyperlinkFilter, JediTermFxWidget}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.Channel
import scalafx.scene.layout.{Pane as SfxPane}

/** One terminal: a login shell on a real PTY, JediTermFX emulating the VT side of it, and one ox scope owning the lifecycle.
  *
  * Build it on the JavaFX thread — the widget is a JavaFX node. The session is what the shell (chunk 4) keeps per tab and closes when the
  * tab goes.
  */
private[corvi] final class TerminalSession(
    val id: Int,
    val label: String,
    private val widget: JediTermFxWidget,
    private val connector: PtyProcessTtyConnector,
    private val process: PtyProcess,
    private val closing: AtomicBoolean,
    private val signalClose: () => Unit
):
  private val disposed = new AtomicBoolean(false)

  /** The node the terminal paints into — the well's content.
    *
    * `scalafx.scene.layout.Pane` has only the delegate constructor, so this is a *wrap* of JediTermFX's own pane, not a pane inside a pane:
    * the well's `StackPane` sizes the widget's pane directly, and a window resize reaches the PTY through it — panel layout → `postResize`
    * → `connector.resize` → `PtyProcess.setWinSize`.
    */
  val pane: SfxPane = new SfxPane(widget.getPane)

  /** The shell's process id, for the checks and the log. */
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

  /** Ends the session: the emulator is stopped, the shell killed, and the ox scope signalled so its thread finishes. Idempotent, and safe
    * on the FX thread — which is where quitting calls it. Teardown has to be *done* before `Platform.exit()`, not raced against it, or a
    * killed toolkit leaves a live shell and a non-daemon emulator thread holding the JVM up.
    *
    * `closing` means "the user asked", so a shell that ends on its own is still reported through `onExited`; the scope's own teardown calls
    * `dispose` and leaves that flag alone.
    */
  def close(): Unit =
    closing.set(true)
    dispose()
    signalClose()

  /** Kills the shell and stops the emulator, once. The connector goes first: it is the only thing that actually destroys the shell, so
    * nothing after it may be able to skip it.
    */
  private def dispose(): Unit =
    if disposed.compareAndSet(false, true) then
      connector.close()
      if Platform.isFxApplicationThread then stopWidget()
      else
        try Platform.runLater(() => stopWidget())
        catch case _: Throwable => () // the toolkit is already gone; the JVM is on its way out

  private def stopWidget(): Unit =
    try widget.close()
    // A panel that fails to dispose must not leave the shell or the emulator's threads alive; the
    // connector above has already done the part that matters.
    catch case failure: Throwable => TerminalSession.logger.warn(s"session $id: closing the widget failed", failure)

private[corvi] object TerminalSession:
  private val logger = LoggerFactory.getLogger(getClass)

  /** The size a fresh PTY starts at; the widget resizes it from the panel's real size afterwards. */
  private val initialColumns: Int = 80
  private val initialRows: Int = 24

  def open(id: Int, label: String, cwd: Path, onOutput: Int => Unit, onExited: Int => Unit): TerminalSession =
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
          // A shell must not outlive a session that never got to exist.
          connector.close()
          throw failure

    val closing = new AtomicBoolean(false)
    val sentOnce = new AtomicBoolean(false)
    val closeSignal = Channel.buffered[Unit](1)

    // Exactly one token ever reaches the channel, so neither a shell that exits nor a `close()`
    // from the UI can block on a full buffer.
    def signalClose(): Unit =
      if sentOnce.compareAndSet(false, true) then closeSignal.send(())

    val session = new TerminalSession(id, label, widget, connector, process, closing, signalClose)

    // One raw virtual thread hosts the scope, and everything inside it is ox. It cannot be the FX
    // thread — `supervised` blocks — and JFXApp3 owns the process's main thread on macOS, so the
    // session gets its own. The scope lives as long as the tab does: that is deliberate, and longer
    // than the short scopes ox prefers.
    val _ = Thread
      .ofVirtual()
      .name(s"corvi-session-$id")
      .start(() =>
        try
          supervised {
            forkDiscard {
              try
                val _ = process.waitFor()
                // The check is inside the marshalled block: a `close()` that lands between the fork
                // and the FX thread must not still report an exit for a session the user just closed.
                if !closing.get() then Platform.runLater(() => if !closing.get() then onExited(id))
              catch case _: InterruptedException => ()
              finally signalClose()
            }
            closeSignal.receive()
          }
        catch case failure: Throwable => logger.warn(s"session $id ended with $failure")
          // The scope tearing down is not the user closing: `dispose` is the same once-only teardown
          // `close` runs, without setting `closing`, so an exit the fork has already queued still reports.
        finally session.dispose()
      )
    session

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
    val environment = new java.util.HashMap[String, String](System.getenv())
    val _ = environment.put("TERM", "xterm-256color")
    new PtyProcessBuilder()
      .setCommand(Array(shell, "-l"))
      .setEnvironment(environment)
      .setDirectory(cwd.toString)
      .setInitialColumns(initialColumns)
      .setInitialRows(initialRows)
      .start()
