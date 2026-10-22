package corvi.app

import corvi.contract.*
import corvi.protocol.{TerminalError, TerminalService}
import corvi.renderer.{SessionCalls, SessionEvents}
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import kyo.*
import kyo.Frame

/** A host connection and the sink its pushes land in, as one value.
  *
  * They belong together because a view plugged into any *other* sink never hears from its session: it holds the frame it seeded and nothing
  * ever updates it. Making the pair a `Client` means the sink cannot be chosen at the place a view is built — `HostConnection.ensure` is
  * the only place the window makes one, everything downstream takes the pair, and the in-memory harness constructs one over the same sink.
  *
  * It is also the client's **only runtime's** work queue: creating, attaching, typing, resizing, history, closing. Every caller — a tab, a
  * view, a probe — offers a command and returns; one fiber, on the runtime that owns the connection and its reader, drains them and hands
  * results back through `Platform.runLater`. Offers and answers are the same two hops every time, so the FX thread never enters an effect,
  * and no effect ever touches a node.
  */
final class Client(val service: TerminalService, val sink: ViewSink) extends SessionCalls:
  import Client.Command

  /** Commands are queued from the moment the client exists: starting the serving fiber only *schedules* it, so a caller — an FX callback, a
    * view attaching to the session the window just opened — can offer a command before the fiber has run a single line. Dropping those
    * offers is a terminal that never receives its snapshot, i.e. a black window.
    */
  private val commands: Client.Queue = new Client.Queue

  /** A new session at this grid, delivered on the FX thread: `None` when the host refused, and nothing is shown until it arrives. */
  def create(cwd: String, size: TermSize, onCreated: Option[SessionInfo] => Unit): Unit =
    offer(Command.Create(cwd, size, onCreated))

  /** Ends the session behind a closed tab; the host then reports the end through the usual push. `onClosed` says the call has finished,
    * whether or not the host agreed — the tab is already gone, so it is the caller's cue to stop waiting, not a success signal.
    */
  def close(id: SessionId, onClosed: () => Unit): Unit = offer(Command.Close(id, Some(onClosed)))

  def attach(session: SessionId, events: SessionEvents): Unit = offer(Command.Attach(session, events))

  def text(session: SessionId, text: String): Unit = offer(Command.Text(session, text))

  def resize(session: SessionId, size: TermSize): Unit = offer(Command.Resize(session, size))

  def history(session: SessionId, from: Int, count: Int): Unit = offer(Command.History(session, from, count))

  def close(session: SessionId): Unit = offer(Command.Close(session, None))

  /** Ends the client's runtime. Commands queued before this are served; the close marker ends the loop, and anything offered afterwards is
    * inert. Closing is one atomic step on the queue itself, so no command can slip past a check and be served after the close.
    */
  def stop(): Unit =
    val _ = commands.close()

  private def onFx(action: () => Unit): Unit =
    // The one FX hop: nothing else here knows that JavaFX exists.
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())

  private def report(message: String): Unit = java.lang.System.err.println(s"corvi ui: $message")

  /** Drains the queue until `stop`: the one fiber that serves every view. Start it once, on the runtime that created the client, and let
    * the enclosing scope end it.
    */
  def serve(using Frame): Unit < (Async & Abort[Any]) =
    def loop(events: Map[SessionId, SessionEvents]): Unit < (Async & Abort[Any]) =
      for
        command <- Sync.defer(commands.take())
        step <- command match
          case None                                  => Sync.defer((events, true))
          case Some(Command.Create(cwd, size, made)) =>
            created(service.create(cwd, size))(made).map(_ => (events, false))
          case Some(Command.Close(id, done)) =>
            closed(service.close(id))(done).map(_ => (events - id, false))
          case Some(Command.Attach(id, target)) =>
            attached(service.attach(id))(target).map(_ => (events.updated(id, target), false))
          case Some(Command.Text(id, text)) =>
            perform(events.get(id), "text")(service.text(id, text)).map(_ => (events, false))
          case Some(Command.Resize(id, size)) =>
            perform(events.get(id), "resize")(service.resize(id, size)).map(_ => (events, false))
          case Some(Command.History(id, from, count)) =>
            performed(events.get(id), "history")(service.history(id, from, count))(rows =>
              events.get(id).foreach(target => onFx(() => target.history(rows.toVector)))
            ).map(_ => (events, false))
        (next, ended) = step
        _ <- if ended then Sync.defer(()) else loop(next)
      yield ()

    loop(Map.empty)

  private def offer(command: Command): Unit = commands.offer(command)

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

  /** Closing is one command for both callers — the tab strip, which passes a callback, and a view's own `SessionCalls` — so the view's
    * event registration is dropped in exactly one place.
    */
  private def closed(operation: Unit < (Async & Abort[TerminalError | Closed]))(onClosed: Option[() => Unit])(using
      Frame
  ): Unit < Async =
    for
      outcome <- Abort.run[TerminalError | Closed](operation)
      _ <- outcome match
        case Result.Success(_)     => Sync.defer(onClosed.foreach(callback => onFx(callback)))
        case Result.Failure(error) =>
          Sync.defer {
            report(s"close failed: $error")
            onClosed.foreach(callback => onFx(callback))
          }
        case Result.Panic(error) =>
          Sync.defer {
            report(s"close panicked: $error")
            onClosed.foreach(callback => onFx(callback))
          }
    yield ()

  private def attached(operation: Snapshot < (Async & Abort[TerminalError | Closed]))(target: SessionEvents)(using
      Frame
  ): Unit < Async =
    for
      outcome <- Abort.run[TerminalError | Closed](operation)
      _ <- outcome match
        case Result.Success(snapshot) => Sync.defer(onFx(() => target.attached(snapshot)))
        case Result.Failure(error)    => Sync.defer(onFx(() => target.failed(s"attach failed: $error")))
        case Result.Panic(error)      => Sync.defer(onFx(() => target.failed(s"attach panicked: $error")))
    yield ()

  /** Runs one call and reports a typed failure to the view it belongs to; the loop never aborts on a session error. */
  private def perform[A](events: Option[SessionEvents], label: String)(operation: A < (Async & Abort[TerminalError | Closed]))(using
      Frame
  ): Unit < Async =
    performed(events, label)(operation)(_ => ())

  private def performed[A](events: Option[SessionEvents], label: String)(
      operation: A < (Async & Abort[TerminalError | Closed])
  )(handle: A => Unit)(using Frame): Unit < Async =
    for
      outcome <- Abort.run[TerminalError | Closed](operation)
      _ <- outcome match
        case Result.Success(value) => Sync.defer(handle(value))
        case Result.Failure(error) =>
          Sync.defer {
            report(s"$label failed: $error")
            events.foreach(target => onFx(() => target.failed(s"$label failed: $error")))
          }
        case Result.Panic(error) =>
          Sync.defer {
            report(s"$label panicked: $error")
            events.foreach(target => onFx(() => target.failed(s"$label panicked: $error")))
          }
    yield ()

private[app] object Client:

  /** What a caller can ask the client's runtime to do. Closing the queue ends the loop and is a normal end; there is no `Stop` command,
    * because a command that raced the close would then have to be dropped by the loop instead of by the queue.
    */
  private[app] enum Command:
    case Create(cwd: String, size: TermSize, onCreated: Option[SessionInfo] => Unit)
    case Close(session: SessionId, onClosed: Option[() => Unit])
    case Attach(session: SessionId, events: SessionEvents)
    case Text(session: SessionId, text: String)
    case Resize(session: SessionId, size: TermSize)
    case History(session: SessionId, from: Int, count: Int)

  /** The commands FX offers the client's runtime, and the close that ends it.
    *
    * A Kyo `Channel` would be the idiomatic queue, but its producer here is the JavaFX thread, which cannot run an effect: `Channel.offer`
    * is effectful and bounded, and an unbounded FX-facing offer is exactly what is missing. A plain `ConcurrentLinkedQueue` has the offer
    * but no close, so a command could be accepted after the consumer had ended. This queue closes once — the marker is appended atomically,
    * the consumer ends when it reaches it, and an offer that races the close can only land behind the marker, where nothing will read it.
    */
  private[app] final class Queue:
    private val commands: LinkedBlockingQueue[Maybe[Command]] = new LinkedBlockingQueue[Maybe[Command]]()
    private val closed: AtomicBoolean = new AtomicBoolean(false)

    def offer(command: Command): Unit =
      if !closed.get() then
        val _ = commands.offer(Maybe.Present(command))

    /** Ends the queue once; later calls are no-ops. */
    def close(): Boolean =
      val alreadyClosed = closed.getAndSet(true)
      if !alreadyClosed then
        val _ = commands.offer(Maybe.Absent)
      !alreadyClosed

    /** Blocks until the next command, or `None` once the queue has been closed. */
    def take(): Option[Command] =
      commands.take() match
        case Maybe.Present(command) => Some(command)
        case Maybe.Absent           => None
