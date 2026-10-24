package corvi.host

import corvi.contract.*
import corvi.protocol.*
import corvi.protocol.Messages.*
import kyo.*
import kyo.Frame

/** The routes a host peer answers, over the live sessions of one client's binding.
  *
  * Every session route goes through `guarded`: a connection that has not said `host.hello` with the daemon's token cannot read, type into
  * or close a shell, and the refusal is the typed `Unauthorized` rather than a silent drop or a hang.
  */
private[corvi] object HostRoutes:
  import Schemas.given

  def routes(host: HostSessions, connection: Connection, startedAt: Long, requestStop: => Unit < Async)(using
      Frame
  ): Seq[JsonRpcRoute[?, ?, ?]] =
    routesWith(host, connection, startedAt, requestStop)

  def routesWith(host: HostSessions, connection: Connection, startedAt: Long, requestStop: => Unit < Async)(using
      Frame
  ): Seq[JsonRpcRoute[?, ?, ?]] =
    Seq(
      JsonRpcRoute.request[HelloParams, Unit](Methods.Hello)((params, _) =>
        if connection.authorize(params.token) then Sync.defer(()) else Abort.fail(Unauthorized("the token does not match"))
      ),
      JsonRpcRoute.request[Unit, HostStatus](Methods.Status)((_, _) => guarded(connection)(status(host, startedAt))),
      JsonRpcRoute.request[Unit, Unit](Methods.Stop)((_, _) => guarded(connection)(requestStop)),
      JsonRpcRoute.request[Unit, Seq[SessionInfo]](Methods.List)((_, _) => guarded(connection)(host.list)),
      JsonRpcRoute.request[CreateParams, SessionInfo](Methods.Create)((params, _) =>
        guarded(connection)(host.create(params.cwd, params.size))
      ),
      JsonRpcRoute.request[SessionParams, Snapshot](Methods.Attach)((params, _) =>
        guarded(connection)(host.attach(connection.id, params.id))
      ),
      JsonRpcRoute.request[HistoryParams, Seq[Row]](Methods.History)((params, _) =>
        guarded(connection)(host.history(params.id, params.from, params.count))
      ),
      JsonRpcRoute.request[SessionParams, Unit](Methods.Close)((params, _) => guarded(connection)(host.close(params.id))),
      JsonRpcRoute.request[TextParams, Unit](Methods.Text)((params, _) => guarded(connection)(host.text(params.id, params.text))),
      JsonRpcRoute.request[ResizeParams, Unit](Methods.Resize)((params, _) => guarded(connection)(host.resize(params.id, params.size)))
    ).map(route =>
      route
        .error[UnknownSession](TerminalError.UnknownSessionCode, "unknown session")
        .error[SessionFailed](TerminalError.SessionFailedCode, "the session could not be started")
        .error[SessionEnded](TerminalError.SessionEndedCode, "the session has ended")
        .error[Unauthorized](TerminalError.UnauthorizedCode, "not authorized")
    )

  private def status(host: HostSessions, startedAt: Long)(using Frame): HostStatus < Async =
    for sessions <- host.list
    yield HostStatus(
      pid = ProcessHandle.current().pid(),
      sessions = sessions.size,
      uptimeMillis = java.lang.System.currentTimeMillis() - startedAt
    )

  /** The connection has to have said hello; a session route's own typed errors pass through untouched. */
  private def guarded[A, E](connection: Connection)(body: => A < (Async & Abort[E]))(using
      Frame
  ): A < (Async & Abort[E | Unauthorized]) =
    if connection.isAuthorized then body else Abort.fail(Unauthorized("say host.hello with the daemon's token first"))
