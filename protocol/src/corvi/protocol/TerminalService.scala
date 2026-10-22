package corvi.protocol

import corvi.contract.*
import kyo.*
import kyo.Frame

/** What a client of the host does, without `kyo-jsonrpc` in the signature: the interface the renderer and the UI consume.
  *
  * Failures are values: the host's typed route errors come back as `TerminalError` (rebuilt from the code and the error's `data`), and
  * `Closed` says the transport went away.
  *
  * `text` is paste-only for now: it puts the bytes on the shell's input as they are, so Enter has to be sent as `\r` (which is what a
  * terminal sends) and nothing is translated. Key and mouse events belong here too, but they need the host's encoders and the terminal
  * modes, so they arrive with the renderer's key handling rather than as a rename later.
  */
private[corvi] trait TerminalService:

  /** Says who we are: the daemon's token, from the file next to its socket. Every other call needs it first. */
  def hello(token: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed])

  def status(using Frame): HostStatus < (Async & Abort[TerminalError | Closed])

  /** Asks the daemon to shut down, ending every shell it holds. */
  def stop(using Frame): Unit < (Async & Abort[TerminalError | Closed])

  def list(using Frame): Seq[SessionInfo] < (Async & Abort[TerminalError | Closed])
  def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[TerminalError | Closed])
  def attach(id: SessionId)(using Frame): Snapshot < (Async & Abort[TerminalError | Closed])
  def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[TerminalError | Closed])
  def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed])
  def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[TerminalError | Closed])
  def close(id: SessionId)(using Frame): Unit < (Async & Abort[TerminalError | Closed])

/** Where the host's pushes land on the client: damage as it is produced, and the session's end. A renderer applies the first to its frame
  * and marks the tab from the second.
  */
private[corvi] trait FrameSink:
  def damage(id: SessionId, damage: Damage): Unit < Sync
  def ended(id: SessionId, ended: Ended): Unit < Sync

/** `TerminalService` over a `kyo-jsonrpc` peer handler. */
private[corvi] final class TerminalClient(handler: JsonRpcHandler) extends TerminalService:
  import Messages.*
  import Schemas.given

  def hello(token: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[HelloParams, Unit](Methods.Hello, HelloParams(token)))

  def status(using Frame): HostStatus < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[Unit, HostStatus](Methods.Status, ()))

  def stop(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[Unit, Unit](Methods.Stop, ()))

  def list(using Frame): Seq[SessionInfo] < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[Unit, Seq[SessionInfo]](Methods.List, ()))

  def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[CreateParams, SessionInfo](Methods.Create, CreateParams(cwd, size)))

  def attach(id: SessionId)(using Frame): Snapshot < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[SessionParams, Snapshot](Methods.Attach, SessionParams(id)))

  def history(id: SessionId, from: Int, count: Int)(using Frame): Seq[Row] < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[HistoryParams, Seq[Row]](Methods.History, HistoryParams(id, from, count)))

  def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[TextParams, Unit](Methods.Text, TextParams(id, text)))

  def resize(id: SessionId, size: TermSize)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[ResizeParams, Unit](Methods.Resize, ResizeParams(id, size)))

  def close(id: SessionId)(using Frame): Unit < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[SessionParams, Unit](Methods.Close, SessionParams(id)))

  /** A wire failure becomes the typed error the host raised: the code says which one, and `data` carries its fields. Anything else is kept
    * as the peer described it, rather than being flattened into a message string.
    */
  private def run[A](call: A < (Async & Abort[JsonRpcError | Closed]))(using
      Frame
  ): A < (Async & Abort[TerminalError | Closed]) =
    for
      outcome <- Abort.run[JsonRpcError](call)
      value <- outcome match
        case Result.Success(result) => Sync.defer(result)
        case Result.Failure(error)  => Abort.fail(TerminalClient.translate(error))
        case Result.Panic(panic)    => Abort.fail(RemoteError(TerminalError.SessionFailedCode, panic.toString))
    yield value

private[corvi] object TerminalClient:
  import Schemas.given

  def translate(error: JsonRpcError)(using Frame): TerminalError =
    val decoded = error.data match
      case Maybe.Present(data) =>
        error.code match
          case TerminalError.UnknownSessionCode => Structure.decode[UnknownSession](data).toMaybe
          case TerminalError.SessionFailedCode  => Structure.decode[SessionFailed](data).toMaybe
          case TerminalError.SessionEndedCode   => Structure.decode[SessionEnded](data).toMaybe
          case TerminalError.UnauthorizedCode   => Structure.decode[Unauthorized](data).toMaybe
          case _                                => Maybe.Absent
      case Maybe.Absent => Maybe.Absent
    decoded.getOrElse(RemoteError(error.code, error.message))
