# kyo-jsonrpc

## Dependencies

- `io.getkyo::kyo-jsonrpc` — `JsonRpcHandler`, `JsonRpcRoute`, `JsonRpcTransport`, `JsonRpcWireTransport`,
  `JsonRpcFramer`, `JsonRpcError`, `JsonRpcApplicationError`.
- `io.getkyo::kyo-schema` — `Schema`, `Structure` (arrives transitively).

---

## Routes

A route is a method name, a request type, a response type, and a handler. `JsonRpcRoute.request[P, R](method)(handler)`
takes `(params, context) => R < …`; `JsonRpcRoute.notification[P](method)` is the fire-and-forget form.

```scala
def routes(host: HostSessions, connection: Connection, startedAt: Long)(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
  Seq(
    JsonRpcRoute.request[HelloParams, Unit](Methods.Hello)((params, _) =>
      if connection.authorize(params.token) then Sync.defer(()) else Abort.fail(Unauthorized("the token does not match"))
    ),
    JsonRpcRoute.request[Unit, Seq[SessionInfo]](Methods.List)((_, _) => guarded(connection)(host.list)),
    JsonRpcRoute.request[CreateParams, SessionInfo](Methods.Create)((params, _) =>
      guarded(connection)(host.create(params.cwd, params.size))
    )
  )
```

```scala
/** The client peer answers the host's two pushes. */
object ClientRoutes:
  def routes(sink: FrameSink)(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
    Seq(
      JsonRpcRoute.notification[DamageParams](Methods.Damage)((params, _) => sink.damage(params.id, params.damage)),
      JsonRpcRoute.notification[EndedParams](Methods.Ended)((params, _) => sink.ended(params.id, params.ended))
    )
```

Method names are plain `String` constants in one object (`Methods`), because both peers need them and the host's routes
live in another module (they need the live sessions). The context argument is passed through by the handler; this project
uses it rarely, preferring the closure.

**Schemas**: request and response types need a `Schema`. This project keeps the DTOs in `contract` (no Kyo type in them)
and puts all derived instances in one `Schemas` object, imported where the routes are declared:

```scala
object Schemas:
  given Schema[SessionInfo] = Schema.derived
  // hand-written transforms for the opaque ids:
  given Schema[SessionId] = Schema[String].transform(SessionId.apply, _.value)
```

`Schema.derived` needs the givens in scope at the derivation site; a `Schema` is not derived implicitly per call site in
this code base, so a new DTO needs one line in `Schemas` (and the compile fails loudly if you forget).

## Typed errors

A handler fails with an `Abort[E]`. For `E` to survive the wire as a *value*, the type must be a `JsonRpcApplicationError`
and the route must declare it:

```scala
sealed abstract class TerminalError(code: Int, message: String)(using Frame) extends JsonRpcApplicationError(code, message)

object TerminalError:
  val UnknownSessionCode: Int = 4001
  ...
```

```scala
route
  .error[UnknownSession](TerminalError.UnknownSessionCode, "unknown session")
  .error[SessionFailed](TerminalError.SessionFailedCode, "the session could not be started")
  .error[SessionEnded](TerminalError.SessionEndedCode, "the session has ended")
  .error[Unauthorized](TerminalError.UnauthorizedCode, "not authorized")
```

`.error[E](code, message)` registers the code and **encodes the value into the error's `data`**. On the client, the same
`E` is rebuilt from `data` rather than from the words, so `client.attach(closedId)` aborts with `UnknownSession(id)`:

```scala
def translate(error: JsonRpcError)(using Frame): TerminalError =
  val decoded = error.data match
    case Maybe.Present(data) =>
      error.code match
        case TerminalError.UnknownSessionCode => Structure.decode[UnknownSession](data).toMaybe
        case TerminalError.SessionFailedCode  => Structure.decode[SessionFailed](data).toMaybe
        case _                                => Maybe.Absent
    case Maybe.Absent => Maybe.Absent
  decoded.getOrElse(RemoteError(error.code, error.message))
```

Codes: pick values outside the implementation-reserved range (this project uses 4001+) so a peer decodes them as
application errors. Register every case a route can raise; an unregistered `E` arrives as a generic remote error.

**Authorisation is a route concern, not a gate.** A `JsonRpcMessageGate` rejection carries the *handshake* request's id,
so a client waiting on its own call never sees it. `HostRoutes` wraps each session route in `guarded`, which aborts with
the typed `Unauthorized`, and the client gets the error for the call it actually made.

## Client side

A `JsonRpcHandler` is both server and client: `handler.call[P, R](method, params): R < (Async & Abort[JsonRpcError | Closed])`
and `handler.notify[P](method, params): Unit < …`. `Closed` means the transport ended. This project wraps the handler in
`TerminalService` so callers never see `kyo-jsonrpc` in their signatures, and translates `JsonRpcError` to its own
`TerminalError`:

```scala
private[corvi] final class TerminalClient(handler: JsonRpcHandler) extends TerminalService:
  def list(using Frame): Seq[SessionInfo] < (Async & Abort[TerminalError | Closed]) =
    run(handler.call[Unit, Seq[SessionInfo]](Methods.List, ()))
```

Note the shape: the thin client has no retry, no cache and no mutable state. Effects in, values out.

## Transports

In memory, for a host and client in one process (the harness):

```scala
for
  transports <- JsonRpcTransport.inMemory
  (clientSide, hostSide) = transports
  hostHandler <- JsonRpcHandler.init(hostSide, HostRoutes.routes(sessions, Connection.trusted(id), startedAt, Sync.defer(())) *)
  clientHandler <- JsonRpcHandler.init(clientSide, ClientRoutes.routes(sink) *)
yield Wired(TerminalClient(clientHandler), id)
```

Over a socket, two pieces: implement the byte seam and lift it with the framing the protocol uses. `JsonRpcWireTransport`
is three members — `send(bytes: Chunk[Byte]): Unit < (Async & Abort[Closed])`, `incoming: Stream[Chunk[Byte], Async & Abort[Closed]]`
(the bytes as they arrive), and `close: Unit < Async`:

```scala
transport <- JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength)
handler <- JsonRpcHandler.init(transport, ClientRoutes.routes(sink) *)
```

`SocketWire` is that seam over a blocking `SocketChannel`: both directions block (a read with no message, a write for one
keystroke), and they run in fibers, so a blocked read parks a virtual thread rather than spinning. The seam also owns
the **EOF latch**: when the peer goes away, release a `Latch` the connection's scope awaits. Without it the handler's
dispatch fiber waits for a stream that has already finished, and the connection's scope never closes.

Server side, `SocketWire.listen(path)` and one `Fiber.initUnscoped(contained(channel, ...))` per accepted connection, so
a client's connection outlives neither its peer nor the daemon's stop.

## Pushing to a client

The host needs the client's handler to push notifications:

```scala
def send[A: Schema](handler: JsonRpcHandler, method: String, params: A)(using Frame): Unit < Async =
  Abort.run[Closed](handler.notify[A](method, params)).map(_ => ())
```

A push to a client that has gone away is not the host's problem: the send fails with `Closed`, the run discards it, and
the loop carries on. Note the deliberate `Abort.run` at the send site rather than at the loop: one dead client must not
take the session's push loop down.

## Rules of thumb

- One `Methods` object, one `Schemas` object, DTOs in a Kyo-free module.
- Register the typed errors on the route; never encode an error message and hope the client parses it.
- Authorise per route; do not use a message gate for a handshake.
- A notification is fire-and-forget, so the peer must not assume it was delivered.
- The transport owns framing; the handler owns dispatch; the application owns meaning.
