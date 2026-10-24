# Effects and Resources

## Dependencies

- `io.getkyo::kyo-core` — `A < S`, `Sync`, `Async`, `Abort`, `Result`, `Scope`, `Frame`.

---

## `A < S`: a pending value with an effect set

A Kyo computation is a value of type `A < S`: when run, it produces an `A` and may use the effects in `S`. Nothing
happens until something runs it. Functions return these values directly; `<-` in a `for` comprehension sequences them.

```scala
def list(using Frame): Seq[SessionInfo] < Sync
def attach(client: ClientId, id: SessionId)(using Frame): Snapshot < (Async & Abort[UnknownSession])
def text(id: SessionId, text: String)(using Frame): Unit < (Async & Abort[UnknownSession | SessionEnded])
```

Reading the effect set is the point: `list` only needs synchronisation, `attach` can suspend and may fail with
`UnknownSession`, `text` adds another failure case. A caller sees what it must handle without reading the body.

- `Sync` — the computation runs synchronously from the caller's point of view; suspension is not allowed. Use it for
  pure work and for touching state that is already in hand.
- `Async` — may suspend (fibers, `Channel`, `sleep`, I/O implemented with fibers). Anything that can block conceptually
  is `Async`, even when the underlying call is a blocking JVM operation run on a parked fiber.
- `Abort[E]` — may fail with `E` (see below). `Scope` — owns finalisers. `Stream`, `Emit`, `Choice`, `Env`, `Var`
  exist upstream; this project does not use them.

`Frame` is the implicit source position Kyo attaches to suspensions. It is not an effect: it is a `using` parameter on
almost every Kyo API, and a method that returns `A < S` usually needs `(using Frame)` to pass along. Kyo derives it
implicitly at the call site, so write `(using Frame)` and let callers ignore it.

Also beware the name: this project has `corvi.contract.Frame` (a client's terminal screen). A file that means Kyo's
source position says so:

```scala
import kyo.Frame
```

or aliases it (`import kyo.Frame as KyoFrame`) where both are in play.

### `Sync.defer` and friends

`Sync.defer` marks a side effect as synchronous work. Values you compute are plain code inside it.

```scala
def damage(id: SessionId, damage: Damage): Unit < Sync =
  Sync.defer(targets.get.foreach(view => view.pushDamage(id, damage)))
```

`Sync.defer` takes a by-name block that itself may be `A < S`; it sequences it after the `Sync` effect. `Sync` also
offers `Sync.Unsafe.defer` (from `AllowUnsafe.embrace.danger` contexts, used inside the Kyo sources and in this project
only through library code) — do not reach for it in application code.

## `Abort` and `Result`

`Abort[E]` is a typed failure channel. Raise with `Abort.fail(e)`, handle with `Abort.run[E](v): Result[E, A]`, and
turn a `Result` back into the effect with `Abort.get(result)`.

```scala
sealed abstract class TerminalError(code: Int, message: String)(using Frame) extends JsonRpcApplicationError(code, message)

final case class UnknownSession(id: SessionId)(using Frame)
    extends TerminalError(TerminalError.UnknownSessionCode, s"no session '${id.value}'")
```

```scala
private def lookup(id: SessionId)(using Frame): Managed < (Sync & Abort[UnknownSession]) =
  for
    all <- sessions.get
    managed <- all.get(id) match
      case Some(found) => Sync.defer(found)
      case None        => Abort.fail(UnknownSession(id))
  yield managed
```

`Result` has three cases, and the third one matters: `Success(value)`, `Failure(error)` and `Panic(throwable)`.
`Abort.runWith`/`Abort.run` let a caller turn failures into values — which is exactly what a boundary does:

```scala
for
  outcome <- Abort.run[TerminalError | Closed](operation)
  _ <- outcome match
    case Result.Success(value) => Sync.defer(handle(value))
    case Result.Failure(error) => Sync.defer(report(s"$label failed: $error"))
    case Result.Panic(error)   => Sync.defer(report(s"$label panicked: $error"))
yield ()
```

Rules of thumb:

- Do **not** catch `Panic` as a normal failure in application logic. It is a defect; a probe or a top-level bridge may
  print it, nothing else.
- `Abort.catching[Throwable](Sync.defer(throwingCall))` is the sanctioned way to turn a foreign exception into an abort.
- Narrow the effect set at the API boundary: a method that cannot fail returns `A < Sync`, not `A < Sync & Abort[Nothing]`
  spelled out — leave `Abort` out entirely.

## Scopes: who releases what

A `Scope` collects finalisers and runs them in **reverse acquisition order** (LIFO) when it closes. `Scope.run(v)` turns
`A < (Scope & S)` into `A < (Async & S)`: the scope closes when `v` completes, successfully or not.

`Scope.ensure(finaliser)` registers cleanup that needs no handle:

```scala
_ <- Scope.ensure(Sync.defer {
  val _ = server.close()
  val _ = Files.deleteIfExists(paths.socket)
})
```

`Scope.acquireRelease(acquire)(release)` acquires a value and ties its release to the scope. Use it whenever the
resource has a handle that must be handed back:

```scala
process <- Scope.acquireRelease(Abort.catching(spawn(cwd, command)))(proc => Sync.defer(proc.destroy()))
```

`Sync.acquireReleaseWith(acquire)(release)(use)` is the bracket where the **`use` block is part of the call**, so the
resource cannot escape: prefer it for local, synchronous bracketing. There are two overloads — `release: A => ...` and
`release: (A, Result[Any, Any]) => ...` when cleanup wants to know whether the extent failed. Its release is synchronous
in the surrounding scope, so it is the right tool for a `try/finally` shape; a long-lived resource belongs to a `Scope`
instead.

### The ordering trap (why the closer goes last)

`HostSession.open` acquires the PTY, starts a fiber that parks in a blocking `read`, and only then registers the
session's closer:

```scala
def open(id: SessionId, index: Int, cwd: Path, command: Seq[String])(using
    Frame
): HostSession < (Async & Scope & Abort[Throwable]) =
  for
    process <- Scope.acquireRelease(Abort.catching(spawn(cwd, command)))(proc => Sync.defer(proc.destroy()))
    session <- Sync.defer(create(id, index, process))
    _ <- Fiber.init(Sync.defer(session.drive()))       // parks in a blocking PTY read
    _ <- Scope.ensure(Sync.defer(session.close()))     // runs first on close: closes the connector
  yield session
```

Because release is LIFO, the closer runs **before** the fiber is interrupted. Closing the connector is what wakes the
blocked read; interrupting a fiber parked in a classic `java.io` read does not, and the scope would wait forever for a
read nobody can satisfy.

The same shape appears in `TerminalSession`, on the JavaFX side, and it is the general rule:

> A finaliser that releases a resource a fiber is blocked on must be registered **after** that fiber, so it runs first.

The mirror-image mistake is waiting for the blocked work in the body before releasing: if the scope body joins the
reader before running the finaliser, the join never returns. `HostMain` therefore closes the listener *inside* the body
to end `accept`, and keeps the socket unlink as a finaliser.

### When there is no scope: `Fiber.initUnscoped` + a latch

A request handler has no `Scope` of its own, so it cannot open a session that wants one. The honest shape is a fiber of
its own plus a latch the session's scope waits on:

```scala
private def start(id, index, cwd, size, stop: Latch)(using Frame): Managed < (Async & Abort[UnknownSession | SessionFailed]) =
  for
    outcome <- AtomicRef.init[Maybe[Result[Throwable, HostSession]]](Maybe.empty)
    ready <- Latch.init(1)
    _ <- Fiber.initUnscoped {
      Scope.run {
        for
          result <- Abort.run[Throwable] {
            for
              session <- HostSession.open(id, index, Paths.get(cwd), command)
              _ <- Sync.defer(session.resize(size))
              _ <- outcome.set(Maybe(Result.Success(session)))
              _ <- ready.release
              _ <- stop.await            // the session lives until someone closes it
            yield ()
          }
          _ <- result match
            case Result.Success(_)     => Sync.defer(())
            case Result.Failure(error) =>
              for
                _ <- outcome.set(Maybe(Result.Failure(error)))
                _ <- ready.release
              yield ()
            case Result.Panic(error) =>
              for
                _ <- outcome.set(Maybe(Result.Failure(error)))
                _ <- ready.release
              yield ()
        yield ()
      }
    }
    _ <- ready.await
    published <- outcome.get
    session <- published match
      case Maybe.Present(Result.Success(found)) => Sync.defer(found)
      case Maybe.Present(Result.Failure(error)) => Abort.fail(SessionFailed(id, error.toString))
      case Maybe.Present(Result.Panic(error))   => Abort.fail(SessionFailed(id, error.toString))
      case Maybe.Absent                         => Abort.fail(SessionFailed(id, "the shell did not start"))
  yield Managed(session, stop, ...)
```

Why the `AtomicRef` + `Latch` instead of returning the fiber's value: a session outlives the request that created it, so
the caller must not wait for the fiber, and the fiber's result has to be observable *before* it parks. Publishing
success or failure through the latch is what turns "the shell could not start" into a typed `SessionFailed` instead of a
timeout. This is a deliberate, documented escape hatch: prefer a real scope whenever the lifetime allows it.

## Running a scope

```scala
Scope.run(daemon)                       // A < (Scope & Async & Abort[Any]) -> A < (Async & Abort[Any])
```

`Scope.run` needs `Async` and a `Frame`. A scope is closed by the surrounding `Scope.run` — including when the body
aborts or panics. Nested scopes close innermost-first, so a connection's scope releases its handler before the daemon's
own finalisers run.

Release failures are defects in Kyo's model: a finaliser that aborts does not stop the remaining ones, and the failure
surfaces as part of the scope's close. Keep finalisers total (`try`/`catch` inside the `Sync.defer` when the foreign
API throws).
