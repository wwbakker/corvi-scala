package corvi.renderer

import corvi.contract.{SessionId, SessionInfo, TermSize}
import corvi.protocol.{TerminalError, TerminalService}
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import kyo.*
import kyo.Frame

/** A host connection and the sink its pushes land in, as one value.
  *
  * They belong together because a view plugged into any *other* sink never hears from its session: it holds the frame it seeded and nothing
  * ever updates it. Making the pair a `Client` means the sink cannot be chosen at the place a view is built — `Harness` and
  * `HostConnection.ensure` are the only places that make one, and everything downstream takes the pair.
  *
  * It also owns the runtime for the two host calls a tab makes outside a view: `create` and `close`. They are offered to a queue that one
  * fiber drains, so the FX thread never enters an effect and never blocks on a host that is slow to answer; the answer arrives through
  * `Platform.runLater`, the same two hops a `TerminalSession` makes for a live session's commands.
  */
final case class Client(service: TerminalService, sink: ViewSink):
  private val calls: Client.Calls = Client.Calls(service)

  /** A new session at this grid, delivered on the FX thread: `None` when the host refused, and nothing is shown until it arrives. */
  def create(cwd: String, size: TermSize, onCreated: Option[SessionInfo] => Unit): Unit =
    calls.create(cwd, size, onCreated)

  /** Ends the session behind a closed tab; the host then reports the end through the usual push. `onClosed` says the call has finished,
    * whether or not the host agreed — the tab is already gone, so it is the caller's cue to stop waiting, not a success signal.
    */
  def close(id: SessionId, onClosed: () => Unit): Unit = calls.close(id, onClosed)

  /** Ends the calls runtime. Nothing is accepted after this. */
  def stop(): Unit = calls.stop()

private[renderer] object Client:

  /** The factory's two host calls, on a runtime of their own.
    *
    * `create` and `close` arrive from FX gestures, so they wait on a queue that a fiber takes from, one at a time and in the order asked.
    * The fiber parks in `take()`, the same blocking-FFI shape as a session's command loop; `Stop` ends it and is a normal end.
    */
  private final class Calls(service: TerminalService):
    import Command.*

    private val commands = new LinkedBlockingQueue[Command]()
    private val running = new AtomicBoolean(false)

    def create(cwd: String, size: TermSize, onCreated: Option[SessionInfo] => Unit): Unit =
      offer(Create(cwd, size, onCreated))

    def close(id: SessionId, onClosed: () => Unit): Unit = offer(Command.Close(id, onClosed))

    def stop(): Unit =
      running.set(false)
      val _ = commands.offer(Stop)

    private def offer(command: Command): Unit =
      start()
      if running.get() then
        val _ = commands.offer(command)

    private def start(): Unit =
      if running.compareAndSet(false, true) then
        val thread = Thread.ofPlatform().name("corvi-ui-client").daemon(true)
        val _ = thread.start { () =>
          import AllowUnsafe.embrace.danger
          KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity)(body) match
            case Result.Success(_)     => ()
            case Result.Failure(error) => report(s"the client's tab calls stopped: $error")
            case Result.Panic(error)   => report(s"the client's tab calls panicked: $error")
        }

    private def body(using Frame): Unit < (Async & Abort[Any]) =
      def loop: Unit < (Async & Abort[Any]) =
        for
          command <- Sync.defer(commands.take())
          stop <- command match
            case Stop                    => Sync.defer(true)
            case Create(cwd, size, made) => created(service.create(cwd, size))(made).map(_ => false)
            case Command.Close(id, done) => closed(service.close(id))(done).map(_ => false)
          _ <- if stop then Sync.defer(()) else loop
        yield ()
      loop

    /** Creating is the one call whose result the shell needs, so a refusal is delivered as `None` rather than only logged. */
    private def created(operation: SessionInfo < (Async & Abort[TerminalError | Closed]))(
        onCreated: Option[SessionInfo] => Unit
    )(using Frame): Unit < Async =
      for
        outcome <- Abort.run[TerminalError | Closed](operation)
        _ <- outcome match
          case Result.Success(info)  => Sync.defer(onFx(() => onCreated(Some(info))))
          case Result.Failure(error) =>
            Sync.defer {
              report(s"create failed: $error")
              onFx(() => onCreated(None))
            }
          case Result.Panic(error) =>
            Sync.defer {
              report(s"create panicked: $error")
              onFx(() => onCreated(None))
            }
      yield ()

    private def closed(operation: Unit < (Async & Abort[TerminalError | Closed]))(onClosed: () => Unit)(using Frame): Unit < Async =
      for
        outcome <- Abort.run[TerminalError | Closed](operation)
        _ <- outcome match
          case Result.Success(_)     => Sync.defer(onFx(onClosed))
          case Result.Failure(error) =>
            Sync.defer {
              report(s"close failed: $error")
              onFx(onClosed)
            }
          case Result.Panic(error) =>
            Sync.defer {
              report(s"close panicked: $error")
              onFx(onClosed)
            }
      yield ()

  private def onFx(action: () => Unit): Unit =
    // The one FX hop: nothing else here knows that JavaFX exists.
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())

  private def report(message: String): Unit = java.lang.System.err.println(s"corvi renderer: $message")

  /** What FX can ask the client's runtime to do. `Stop` ends the loop and is a normal end. */
  private enum Command:
    case Stop
    case Create(cwd: String, size: TermSize, onCreated: Option[SessionInfo] => Unit)
    case Close(id: SessionId, onClosed: () => Unit)
