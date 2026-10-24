# KyoApp and Process Boundaries

## Dependencies

- `io.getkyo::kyo-core` — `KyoApp`, `KyoApp.Unsafe.runAndBlock`, `Scope`, `Result`, `AllowUnsafe`.

---

## `KyoApp`: the entry point of a process that is Kyo all the way down

```scala
object HostMain extends KyoApp:

  run {
    for
      // The daemon's scope is run here so the process can exit *after* it has closed: `KyoApp` treats a successful body
      // as "nothing to report" and would otherwise leave the runtime's threads holding the JVM open.
      _ <- Scope.run(daemon)
      _ <- Sync.defer(java.lang.System.exit(0))
    yield ()
  }
```

`run` takes an `A < (Async & Scope & Abort[Any])`. `KyoApp` forks it, reports a failure as a process failure, and — this
is the trap above — does **not** end the JVM for you when the body completes successfully. A long-lived daemon therefore
runs its own `Scope.run` and exits explicitly once the scope has closed, so the shells are destroyed before the process
goes.

The daemon's scope then registers everything else: the socket's unlink, the `HostSessions` service, the accept loop, and
the latch that keeps the process alive:

```scala
private def daemon(using Frame): Unit < (Async & Scope & Abort[Any]) =
  for
    paths <- Sync.defer(HostPaths.defaultPaths())
    _ <- Sync.defer(paths.prepare())
    token <- Sync.defer(paths.token())
    _ <- takeSocket(paths)
    server <- SocketWire.listen(paths.socket)
    _ <- Sync.defer(paths.claim(ProcessHandle.current().pid()))
    _ <- Scope.ensure(Sync.defer { /* close the socket, delete socket + owner marker */ })
    host <- Scope.acquireRelease(Sync.defer(HostSessions.init(pushInterval, HostSession.loginShell)))(_.shutdown)
    stop <- Latch.init(1)
    _ <- Fiber.init(acceptLoop(server, host, token, stop, startedAt))
    _ <- stop.await
    _ <- Sync.defer(server.close())      // ends the blocking accept
  yield ()
```

What a `KyoApp` is *not* for: a process whose main thread belongs to a foreign toolkit. JavaFX must own the main thread
(`JFXApp3`), so the UI is not a `KyoApp`; it starts Kyo runtimes on threads it owns. See
[The JavaFX/Kyo Boundary](160-javafx-boundary.md).

## `runAndBlock`: the bridge, and only at a bridge

`KyoApp.Unsafe.runAndBlock(timeout)(A < (Async & ...))` runs an effect on a fresh runtime and blocks the calling thread
until it settles. It needs `import AllowUnsafe.embrace.danger`.

Use it **only** where a non-Kyo world calls into Kyo once:

- a probe's helper that asks the host a question and prints the answer (`runKyo` in the probes);
- a renderer/protocol client's short-lived call from a plain method;
- never on the FX thread, never inside a fiber, never in a loop.

```scala
private def runKyo[A](call: A < (Async & Abort[TerminalError | Closed]))(using KyoFrame): Option[A] =
  import AllowUnsafe.embrace.danger
  KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(60.seconds))(call) match
    case Result.Success(value) => Some(value)
    case Result.Failure(error) =>
      java.lang.System.err.println(s"corvi ui probe: the host refused: $error")
      None
    case Result.Panic(error) =>
      java.lang.System.err.println(s"corvi ui probe: the host call panicked: $error")
      None
```

The timeout is a safety net, not a policy: `kyo.Duration.Infinity` is used by the long-lived client runtimes, because
the fiber's lifetime is the point of those threads.

## One runtime per thread you own

The project runs several Kyo runtimes on purpose, each tied to a thread whose lifetime it matches:

- the daemon: `HostMain`'s `KyoApp` runtime, with one fiber per session and one per session's push loop;
- each terminal view in the UI: `TerminalSession` starts a thread and `runAndBlock(Infinity)`s a command loop;
- the UI's tab operations: `Client.Calls`, one runtime for `create`/`close`;
- each probe phase, where a "UI restart" is deliberately a fresh runtime and a fresh connection.

Two rules follow. A runtime is not a service locator: do not share one `Fiber` across runtimes, and do not call into
another runtime's effects from a different thread — use a queue or a socket. And any resource handed to a runtime must be
thread-safe for the way it is used (this is why the in-memory protocol transport is used by the harness and the socket
transport by the daemon: the peers are wired once, in the runtime that owns them).

## Spawning a process that must outlive its parent

The UI starts the daemon with `ProcessBuilder`, **not** with `kyo-system`'s `Command`, and drops the handle:

```scala
val builder = java.lang.ProcessBuilder(
  javaBinary,
  "-Dkyo.scheduler.virtualizeWorkers=true",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "-cp",
  java.lang.System.getProperty("java.class.path"),
  "corvi.host.HostMain"
).redirectOutput(log.toFile)
 .redirectErrorStream(true)
 .redirectInput(java.lang.ProcessBuilder.Redirect.from(new File("/dev/null")))
val _ = builder.environment().put("CORVI_HOST_SOCKET", paths.socket.toString)
val process = builder.start()   // the handle is dropped on purpose
```

`Command(...).text`/`waitFor` bracket the process and wait for its output; even `spawn` hands back a process the enclosing
scope destroys. That is correct for "run this and read its output", wrong for a daemon whose whole point is to survive the
UI. The classpath is this JVM's own, so the daemon gets exactly the jars the
UI has; the environment carries the socket path; stdin is `/dev/null` so the daemon neither holds the terminal open nor
dies when the UI's stdin does.

The spawned process needs the scheduler flags too — pass them in the child's arguments, and set the same `forkArgs` on
every Mill module that runs Kyo with blocking fibers:

```scala
def forkArgs = Seq(
  "-Dkyo.scheduler.virtualizeWorkers=true",
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
)
```

`--add-opens=java.base/java.lang=ALL-UNNAMED` is required, not cosmetic: Kyo's blocking monitor inspects `Thread` state to
decide whether a fiber is parked or busy, and without the flag it cannot read it.

One more consequence of dropping the handle: process *cleanup* is the daemon's own job (`host.stop` or `kill`), and a
launcher that kills its whole process group takes the daemon with it. A packaged app needs its own launcher, and
`setsid`/`nohup` is not implemented here — see the README's limits.

## Reading the daemon's liveness

Before spawning, the UI asks whether something already listens. The check is a non-blocking connect to the Unix socket,
and it must open the channel with the **UNIX family**:

```scala
probe = SocketChannel.open(StandardProtocolFamily.UNIX)
probe.configureBlocking(false)
val _ = probe.connect(UnixDomainSocketAddress.of(socket))
while !probe.finishConnect() do ...
```

A default `SocketChannel.open()` is an INET channel, and connecting it to a `UnixDomainSocketAddress` throws
`UnsupportedAddressTypeException` — which a `catch`-to-false turns into "nobody is listening". The symptom is a UI that
spawns a second daemon on every start and then waits for a socket that is already there. `HostDaemonProbe` now checks
both the live and the dead answer for exactly this reason.
