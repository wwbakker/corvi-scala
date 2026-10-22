# Fibers, Channels and Shared State

## Dependencies

- `io.getkyo::kyo-core` — `Fiber` (with `Fiber.Promise`), `Channel`, `Hub`, `Latch`, `AtomicRef`, `AtomicInt`.

---

## Fibers

`Fiber.init(effect)` starts a fiber owned by the enclosing scope: closing the scope interrupts it. Its value is read with
`fiber.get`.

```scala
_ <- Fiber.init(acceptLoop(server, host, token, stop, startedAt))
```

`Fiber.initUnscoped(effect)` starts a fiber with **no owning scope**: it ends when its own effect ends, and nothing
cancels it. This is the documented escape hatch for a lifetime that is not the caller's:

- a route handler has no scope, so a session is opened in `Fiber.initUnscoped(Scope.run { ... })` and lives until its
  stop latch is released (`HostSessions.start`);
- a connection lives until its peer goes away, not until the daemon stops, so `HostMain` accepts with
  `Fiber.initUnscoped(contained(channel, ...))` — otherwise `host.stop` would wait for clients that are still connected.

Every `initUnscoped` needs an explicit answer to "what ends this fiber, and what cleans up after it?" — a latch, EOF on a
socket, a PTY destroyed by its scope. If the answer is "nothing", it is a leak.

A fiber parked in a blocking call is not woken by cancellation alone; see the teardown ordering in
[Effects and Resources](110-effects-and-resources.md) and the blocking-FFI flags in
[KyoApp and Process Boundaries](130-kyoapp-and-process-boundaries.md).

## Channels: hand-off and work queues

`Channel.init[A](capacity)` (inside a `Scope`) or `Channel.initUnscoped[A](capacity)` gives a buffered channel.
`offer`/`poll` are the non-blocking end, `put`/`take` the suspending one; all fail with `Closed` once the channel is
closed. Capacity 0 is a rendezvous.

```scala
// A worker mailbox: one fiber drains, producers put.
for
  mailbox <- Channel.init[Job](64)
  _ <- Fiber.init {
    def loop: Unit < (Async & Abort[Closed]) =
      for job <- mailbox.take
      yield ()
    loop
  }
yield mailbox
```

This project does not use Kyo `Channel` for the FX↔Kyo hand-off; it uses a `java.util.concurrent.LinkedBlockingQueue`
taken from a fiber (`TerminalSession`, `Client.Calls`). The reason is the boundary, not a preference: FX offers a command
with a plain blocking `offer`, and the fiber's `take()` is exactly the blocking-FFI posture the scheduler flags exist for.
Do that at a foreign boundary; use `Channel` between two Kyo fibers.

`Hub[A]` fans one stream out to many listeners: `hub.put(v)`, then each `hub.listen` (scope-bound) gets its own buffered
`Listener` with `take`/`poll`. Use it rather than one channel per consumer.

## One-shot synchronisation: `Latch` and `Fiber.Promise`

`Latch.init(count)`, `latch.await`, `latch.release`. Release is once-only: the first release wakes the waiters, the rest
are no-ops. That is why the host's socket end uses it — read EOF, read failure and local close all call `release`, and
the first one ends the connection's scope.

```scala
for
  closed <- Latch.init(1)
  wire <- SocketWire.connect(paths.socket, closed)
  ...
```

The key is `stop <- Latch.init(1)`: the session's scope awaits it, and `close` releases it, so "closed" is a single event
that ends exactly one scope.

`Fiber.Promise.init[E, A]` is a fibre-shaped, single-assignment cell: `complete(Result...)`, then `get`. Use it when one fiber
produces a value another waits for and a `Channel(1)` would be a lie about multiplicity. (`HostSessions` uses an
`AtomicRef[Maybe[Result[...]]]` plus a `Latch` instead, because it also needs to publish a *failure* before parking; a
`Fiber.Promise` is the cleaner choice for new code with the same shape.)

## Shared state: `AtomicRef` / `AtomicInt`

`AtomicRef.init(value)`, then `get`, `set`, `updateAndGet`, `use(f)`, with `AtomicInt` adding `getAndIncrement`.

```scala
private[corvi] final class HostSessions(
    sessions: AtomicRef[Map[SessionId, Managed]],
    clients: AtomicRef[Map[ClientId, JsonRpcHandler]],
    lastIndex: AtomicInt,
    lastClient: AtomicInt
):
  def bind(client: ClientId, handler: JsonRpcHandler)(using Frame): Boolean < Sync =
    clients.use { current =>
      if current.contains(client) then Sync.defer(false)
      else clients.set(current.updated(client, handler)).map(_ => true)
    }
```

Hold **immutable values** in the atomic and replace them whole; never store a mutable map and mutate it. `use` gives the
read-modify-write shape when the decision depends on the current value.

When an atomic is the right answer:

- state genuinely crosses threads that Kyo does not own on both sides (the registry is written by request fibers and read
  by push fibers; the per-client baselines are written by push fibers);
- a writer parks in a **blocking** foreign read (the PTY reader records its failure in a plain `AtomicReference`), where
  no effect system can serialise access;
- the value is small and the update is a pure function of it.

When it is a smell:

- the state is confined to one thread (JavaFX: use a property and let thread confinement be the proof);
- an atomic is being used as a lifecycle flag next to a scope (`closed`, `running` flags) — the scope or a `Latch` is the
  state;
- a chain of atomics is standing in for a supervisor.

`AtomicInt` for counters: `lastIndex.getAndIncrement` mints `session-1`, `session-2`, …

## Per-client baselines: an atomic as a correctness device

The host pushes deltas, and a delta is absolute. Each client's baseline is kept in
`AtomicRef[Map[ClientId, Snapshot]]` and advanced **only after** the delta has actually been sent:

```scala
for
  step <- Sync.defer(managed.session.frameSince(baseline))
  (frame, damage) = step
  _ <- if damage.isEmpty then Sync.defer(()) else push(client, Methods.Damage, DamageParams(frame.id, damage))
  _ <- managed.attached.updateAndGet(frames => if damage.isEmpty then frames else frames.updated(client, frame)).map(_ => ())
yield ()
```

A baseline advanced before the send would silently skip the delta a failed send dropped. When an atomic guards a
protocol invariant, comment the ordering (`// The baseline only moves once the delta has been sent …`).

## Blocking inside a fiber

Kyo runs fibers on Loom virtual threads. A fiber that calls `queue.take()`, `socket.read`, or `pty.read` parks a virtual
thread; the scheduler's `BlockingMonitor` notices the thread is blocked rather than spinning, so it does not count against
parallelism — provided the runtime was started with:

```
-Dkyo.scheduler.virtualizeWorkers=true
--add-opens=java.base/java.lang=ALL-UNNAMED
```

`--add-opens` is what lets Kyo read `Thread` state to tell a parked fiber from a busy one. Without the flags a blocking
fiber can starve the scheduler. Add them to the module's `forkArgs` (see [KyoApp and Process Boundaries](130-kyoapp-and-process-boundaries.md)).

Never add a second thread between the bytes and the parser to "fix" a blocking read: the PTY reader is deliberately a
blocking-FFI fiber, and a channel pump would only add a hop and a thread.
