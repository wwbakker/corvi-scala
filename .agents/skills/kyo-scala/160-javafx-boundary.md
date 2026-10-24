# The JavaFX/Kyo Boundary

## Dependencies

- `org.scalafx::scalafx` (JavaFX 26 underneath)
- `io.getkyo::kyo-core`

---

## The two rules

1. **JavaFX owns the main thread.** On macOS the toolkit starts on the process's first thread; it will not start anywhere
   else. So the UI is a `JFXApp3` (ScalaFX), never a `KyoApp`, and the Kyo runtime gets a thread of its own.
2. **No node is touched off the FX thread, and no effect is run on it.** The FX thread never enters `A < S`; the Kyo
   runtime never touches a `Node`. Everything crosses at two hops: a blocking queue in, `Platform.runLater` out.

```scala
object ShellApp extends scalafx.application.JFXApp3:

  locally:
    val thread = Thread.ofPlatform().name("corvi-shell-client").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity) {
        Scope.run {
          for
            client <- HostConnection.ensure()
            sessions <- client.service.list
            _ <- Sync.defer { started.set(Some(Started(client, sessions.toVector))); ready.countDown() }
            _ <- Sync.defer(closing.await())
          yield ()
        }
      } match
        case Result.Success(_)     => ()
        case Result.Failure(error) => java.lang.System.err.println(s"corvi shell: the client stopped: $error")
        case Result.Panic(error)   => java.lang.System.err.println(s"corvi shell: the client panicked: $error")
    }

  override def start(): Unit = /* wait for `ready`, then build the scene and show the stage */
```

`ready`/`closing` are `CountDownLatch`es: the FX side waits for the first connection before showing a window, and the
window close releases the second so the runtime can shut its scope. `runAndBlock(Infinity)` is right here because the
thread *is* the runtime's lifetime.

The runtime's daemon flag matters: an abandoned UI must not keep the process (and therefore the shells it does not own)
alive.

## The command queue and the return hop

`TerminalSession` is the worked example of a per-session bridge:

```scala
final class TerminalSession(service: TerminalService, session: SessionId, events: SessionEvents):
  private val commands = new LinkedBlockingQueue[Command]()

  def requestText(text: String): Unit = offer(Command.Text(text))     // called on FX
  def requestClose(): Unit = offer(Command.Close)

  private def body(using Frame): Unit < (Async & Abort[Any]) =
    for
      snapshot <- service.attach(session)                  // the Kyo side
      _ <- Sync.defer(onFx(() => events.attached(snapshot)))
      _ <- pump
    yield ()

  private def pump(using Frame): Unit < (Async & Abort[Any]) =
    def loop: Unit < (Async & Abort[Any]) =
      for
        command <- Sync.defer(commands.take())            // parks the fiber: blocking-FFI by design
        stop <- command match
          case Command.Stop         => Sync.defer(true)
          case Command.Close        => perform("close")(service.close(session))(_ => ()).map(_ => false)
          case Command.Text(text)   => perform("text")(service.text(session, text))(_ => ()).map(_ => false)
          case Command.Resize(size) => perform("resize")(service.resize(session, size))(_ => ()).map(_ => false)
        _ <- if stop then Sync.defer(()) else loop
      yield ()
    loop

  private def onFx(action: () => Unit): Unit =
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())
```

Why a `LinkedBlockingQueue` and not a Kyo `Channel`: the producer is JavaFX, which cannot enter an effect. `offer` is a
plain method call, and the fiber's `take()` is exactly the blocking-FFI posture the scheduler handles. Use Kyo `Channel`
between two Kyo fibers; use this shape at a foreign boundary.

`SessionEvents` is the promise that closes the loop: **every method on it runs on the FX thread**, because the only
caller hops first. That is what lets the view keep all of its state as plain FX-confined fields.

The same shape is used for the two host calls a tab makes outside any view (`Client.Calls` in `app`): a queue, one fiber,
`Platform.runLater` for the answer. The consequence for the UI is the important part: **nothing on the FX thread waits
for the host**, so a slow or wedged daemon cannot freeze the window. A new tab appears only when the callback fires.

## Blocking-FFI fibers and the scheduler flags

A fiber parked in `take()`, `socket.read` or a PTY `read` is running a blocking JVM call. Kyo runs fibers on Loom virtual
threads, and its blocking monitor checks the parked thread's state to avoid counting it as busy. That check needs:

```
-Dkyo.scheduler.virtualizeWorkers=true
--add-opens=java.base/java.lang=ALL-UNNAMED
```

Put both in every module that runs such a fiber (`forkArgs` in `build.mill`) and in the arguments of any JVM this
project spawns (the daemon). Without them a blocking fiber can starve the scheduler's parallelism.

## FX-confined state

A view's fields are plain `var`s/collections — safe because only the FX thread touches them, and the boundary above is
what proves it. Do not "fix" this with synchronisation; the thread confinement is the design, and adding atomics would
hide a boundary violation instead of preventing it.

The shell's *model* is different: `ShellState` is immutable and its transitions are pure, so the state can be reasoned
about (and asserted by probes) without a toolkit. The single holder is a property, not a `var`:

```scala
private val state: ObjectProperty[ShellState] = ObjectProperty(ShellState.initial)

private def applied(next: ShellState): Unit =
  if next != current then
    state.value = next
    paint()
```

## The probes' bridge

A probe thread (not FX) needs to call into the FX world and wait for the result. That is the one place a blocking hop is
sanctioned, and it is written once:

```scala
private def onFx[A](action: => A): A =
  if Platform.isFxApplicationThread then action
  else
    val result = AtomicReference[Either[Throwable, A]]()
    val latch = CountDownLatch(1)
    Platform.runLater { () =>
      try result.set(Right(action))
      catch case error: Throwable => result.set(Left(error))
      latch.countDown()
    }
    if !latch.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("the FX thread did not answer")
    result.get() match
      case Right(value) => value
      case Left(error)  => throw error
```

Never call it from the FX thread's own path (it would deadlock); the `isFxApplicationThread` branch makes nesting safe,
which matters because helpers call each other. Production code must not block FX on Kyo at all — that is the queue, not
this helper.

## `Platform.startup` and `JFXApp3`

- A real window: `JFXApp3`, `start()` builds the scene, `Platform.exit()` on close.
- A probe: `Platform.startup(() => ())`, then a daemon thread does the work and `Platform.runLater` performs the UI
  calls; `System.exit(0/1)` at the end so a stray non-daemon thread cannot hang the probe.
- A snapshot harness: `JFXApp3` with an `AnimationTimer` that waits for the screen to settle before `scene.snapshot`.

`scene.snapshot` renders the scene graph without a visible display, which is how this project looks at its own UI on a
machine with no screen-recording permission. It does not include the OS title bar.

## Styling

The stylesheet is a resource of the `ui` module (`ui/resources/corvi.css`), attached by `corvi.ui.Stylesheet`, which
throws when the resource is missing rather than silently rendering an unthemed window. Colours live in the sheet as
looked-up colours (`-fx-corvi-*` on `.root`); code does not duplicate them. CSS lands on the next pulse, so a probe that
asserts a computed colour calls `applyCss()` first.
