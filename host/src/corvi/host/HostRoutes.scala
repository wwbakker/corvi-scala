package corvi.host

import corvi.contract.*
import corvi.protocol.*
import corvi.protocol.Messages.*
import kyo.*
import kyo.Frame

/** The routes a host peer answers, over the live sessions of one client's binding. */
private[corvi] object HostRoutes:
  import Schemas.given

  def routes(host: HostSessions, client: ClientId)(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
    Seq(
      JsonRpcRoute.request[Unit, Seq[SessionInfo]](Methods.List)((_, _) => host.list),
      JsonRpcRoute.request[CreateParams, SessionInfo](Methods.Create)((params, _) => host.create(params.cwd, params.size)),
      JsonRpcRoute.request[SessionParams, Snapshot](Methods.Attach)((params, _) => host.attach(client, params.id)),
      JsonRpcRoute.request[HistoryParams, Seq[Row]](Methods.History)((params, _) => host.history(params.id, params.from, params.count)),
      JsonRpcRoute.request[SessionParams, Unit](Methods.Close)((params, _) => host.close(params.id)),
      JsonRpcRoute.request[TextParams, Unit](Methods.Text)((params, _) => host.text(params.id, params.text)),
      JsonRpcRoute.request[ResizeParams, Unit](Methods.Resize)((params, _) => host.resize(params.id, params.size))
    ).map(route =>
      route
        .error[UnknownSession](TerminalError.UnknownSessionCode, "unknown session")
        .error[SessionFailed](TerminalError.SessionFailedCode, "the session could not be started")
        .error[SessionEnded](TerminalError.SessionEndedCode, "the session has ended")
    )
