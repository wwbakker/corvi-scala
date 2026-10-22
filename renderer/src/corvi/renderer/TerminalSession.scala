package corvi.renderer

import corvi.contract.*
import corvi.protocol.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import kyo.*
import kyo.Frame

/** What the client's runtime tells a view. Every method runs on the **FX thread**: the runtime hands the call over with
  * `Platform.runLater`, so a view never has to guard its state.
  */
trait SessionEvents:
  def attached(snapshot: Snapshot): Unit
  def history(rows: Vector[Row]): Unit
  def sessionEnded(ended: Ended): Unit
  def failed(message: String): Unit

/** The FX/Kyo boundary for one session.
  *
  * The client's Kyo runtime gets a thread of its own; the FX thread never enters an effect, and the runtime never touches a node. FX offers
  * commands to a blocking queue that the runtime's fiber drains, and results come back through `Platform.runLater`. Those two hops are the
  * whole contract, and they are why `SessionEvents` can promise the FX thread.
  *
  * The fiber parks in `queue.take()`, a blocking call on purpose: the same blocking-FFI shape as the host's PTY reader, costing one parked
  * virtual thread rather than a spinning one. `Stop` ends it, which is a normal end and not a failure.
  */
final class TerminalSession(service: TerminalService, session: SessionId, events: SessionEvents):
  import TerminalSession.*

  private val commands = new LinkedBlockingQueue[Command]()
  private val running = new AtomicBoolean(false)
  private val stoppedCleanly = new AtomicBoolean(false)

  /** Starts the client runtime. Attaching, and every command after it, happens on that thread; the view hears about all of it through
    * `Platform.runLater`.
    */
  def start(): Unit =
    running.set(true)
    val thread = Thread.ofPlatform().name("corvi-renderer-client").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity)(body) match
        // A stop is how this is meant to end; only an end that nobody asked for is worth reporting.
        case Result.Success(_) =>
          if !stoppedCleanly.get() then onFx(() => events.failed("the client stopped without being asked"))
        case Result.Failure(error) => onFx(() => events.failed(s"the client stopped: $error"))
        case Result.Panic(error)   => onFx(() => events.failed(s"the client panicked: $error"))
    }

  def requestText(text: String): Unit = offer(Command.Text(text))

  def requestResize(size: TermSize): Unit = offer(Command.Resize(size))

  def requestHistory(from: Int, count: Int): Unit = offer(Command.History(from, count))

  /** Asks the host to close the session, which is what closing a tab does: the host then reports the end like any other. */
  def requestClose(): Unit = offer(Command.Close)

  /** Ends the runtime's loop. Nothing is accepted after this. */
  def close(): Unit =
    running.set(false)
    val _ = commands.offer(Command.Stop)

  private def offer(command: Command): Unit =
    if running.get() then
      val _ = commands.offer(command)

  private def body(using Frame): Unit < (Async & Abort[Any]) =
    for
      snapshot <- service.attach(session)
      _ <- Sync.defer(onFx(() => events.attached(snapshot)))
      _ <- pump
    yield ()

  /** One command at a time, in the order FX offered them. A command that fails is reported to the view and the loop carries on: a closed
    * session should not take the window's client down silently.
    */
  private def pump(using Frame): Unit < (Async & Abort[Any]) =
    def loop: Unit < (Async & Abort[Any]) =
      for
        command <- Sync.defer(commands.take())
        stop <- command match
          case Command.Stop =>
            stoppedCleanly.set(true)
            Sync.defer(true)
          case Command.Close                => perform("close")(service.close(session))(_ => ()).map(_ => false)
          case Command.Text(text)           => perform("text")(service.text(session, text))(_ => ()).map(_ => false)
          case Command.Resize(size)         => perform("resize")(service.resize(session, size))(_ => ()).map(_ => false)
          case Command.History(from, count) =>
            perform("history")(service.history(session, from, count))(rows => onFx(() => events.history(rows.toVector)))
              .map(_ => false)
        _ <- if stop then Sync.defer(()) else loop
      yield ()
    loop

  /** Runs one call and turns its typed failure into a report for the view; the runtime's loop never aborts on a session error. */
  private def perform[A](label: String)(operation: A < (Async & Abort[TerminalError | Closed]))(handle: A => Unit)(using
      Frame
  ): Unit < Async =
    for
      outcome <- Abort.run[TerminalError | Closed](operation)
      _ <- outcome match
        case Result.Success(value) => Sync.defer(handle(value))
        case Result.Failure(error) => Sync.defer(onFx(() => events.failed(s"$label failed: $error")))
        case Result.Panic(error)   => Sync.defer(onFx(() => events.failed(s"$label panicked: $error")))
    yield ()

  private def onFx(action: () => Unit): Unit =
    // The one FX hop: nothing else here knows that JavaFX exists.
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())

private[renderer] object TerminalSession:
  /** What FX can ask the runtime to do. `Stop` ends the loop and is a normal end; `Close` ends the session and lets the host report it. */
  enum Command:
    case Stop
    case Close
    case Text(text: String)
    case Resize(size: TermSize)
    case History(from: Int, count: Int)
