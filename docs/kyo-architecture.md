# Corvi terminal architecture: host-owned screen, thin renderer, Kyo host

Status: agreed design for the Kyo rewrite. Supersedes `docs/direct-style.md` where they disagree.

## Processes

- **`corvi-host`** — long-lived daemon, no JavaFX. Owns the PTYs and a headless terminal
  emulator per session. A `KyoApp` with structured scopes and fibers.
- **`corvi-ui`** — the ScalaFX application. Owns only the window, tabs, sidebar and a thin
  rendered view per session. Never parses terminal bytes.

The UI starts the host if it is not running (detached, so it survives the UI), then connects to
its Unix domain socket. A UI restart reattaches to the running host: the shells are untouched and
each screen is restored exactly from the host's screen state. If the *host* dies the shells are
gone; that is out of scope.

## Screen ownership

The **host interprets the terminal**; the frontend only renders it.

- Host, per session: pty4j PTY → `jeditermfx-core` (`JediTerminal` + `TerminalTextBuffer`)
  with a recording `TerminalDisplay`. No JavaFX: the core has no JavaFX/AWT dependency, so
  the host runs the same VT engine the old UI used.
- The reader is `JediEmulator` over `TtyBasedArrayDataStream`, driven by **one fiber per
  session** (`HostSession.drive`). `TerminalStarter` is not used: it starts its own thread,
  which is the thing this design replaces, and everything else it adds (type-ahead, an
  executor service) is optional.
- **Buffer ownership**: the engine takes `TerminalTextBuffer`'s lock itself, per operation —
  every path that writes cells (`writeCharacters`, `resize` through `TerminalTextBuffer.modify`,
  erase, insert, scroll) locks the buffer, and the readers take the same lock, so a snapshot
  cannot see half an escape sequence. The drive loop must **not** wrap `emulator.next()` in
  that lock: `next()` refills its character buffer from the PTY and can park in a blocking
  read, which would leave readers waiting for output that only they could have caused.
- History capped at **5,000 rows** (Corvi's `SCREEN_SCROLLBACK` default).
- Damage: after each coalesced batch of PTY output, **what changed** is sent down the socket:
  the rows whose cells differ, plus the cursor, the modes, the history depth and the grid size
  when those differ. Rows alone are not a delta — a cursor move, a mode toggle, a title change
  or history growth changes what a client draws or sends while leaving every cell alone.
- The frontend caches one frame per session and applies damage for **all** sessions in the
  background, so tab switching is instant and the FX thread only paints. No VT parsing and no
  `JediTerminal` in the UI.

## Protocol (JSON-RPC over a Unix domain socket, `kyo-jsonrpc`)

- `session.attach(id, cols, rows)` → `Snapshot`: styled cells for the visible grid, cursor (pos,
  shape, blink, visible), modes (alternate screen, mouse mode/format, bracketed paste, title),
  history row count. The session's theme (default foreground/background) travels in
  `SessionInfo`, not in every frame.
- `session.damage(id, damage)` — a frame delta: `rows` (index → cells), and `size`, `cursor`,
  `modes`, `historyRows` as `Option`s where `None` means unchanged. `Damage.applyTo(before)`
  is the host's own definition of what the delta means, so a client that applies it holds the
  host's screen. A `size` in the delta tells a client to take a fresh snapshot: after a resize
  the row indexes mean a different grid.
- `session.history(id, from, count)` — scrollback pages, newest first, `from` counting back
  from the most recent.
- `session.key(id, keyCode, modifiers, text)` / `session.mouse(...)` / `session.resize(id, cols, rows)`
  — the host owns key/mouse encoding (`TerminalKeyEncoder`) because it knows the terminal modes.
- `session.ended(id, ended)` — once per attached client: `closed` says who ended it, `code` is
  advisory, `reason` carries a reader failure. `sessions.list`, `session.create`, `session.close`.
- Base64 cell payloads first; a compact binary framing can replace them later without changing
  the routes.

### Protocol layer as built

`protocol` holds the wire and both sides of it, over `kyo-core` + `kyo-jsonrpc`:

- **Routes**: names in `Methods`, params in `Messages`, and the client's two notification routes in
  `ClientRoutes` — all in `protocol`. The host's answering routes are `HostRoutes` in `host`,
  because they need the live sessions. `session.text`/`session.resize` are requests, not
  notifications: a client wants to know when the host no longer has the session. `text` is
  paste-only for now; `session.key`/`session.mouse` arrive with the renderer, which owns the event
  model, and the host will encode them because it knows the modes.
- **`protocol` depends on `contract` and `kyo-jsonrpc` only**; `host` depends on `protocol`. A UI
  build therefore takes the client half without pty4j, jeditermfx or the host's JVM flags. For the
  same reason the protocol probe lives in `host` (`corvi.host.ProtocolProbe`): it needs real shells.
- DTOs stay in `contract`; their codecs are `Schema.derived` givens in `Schemas` (with hand-written
  transforms for the opaque ids) so no kyo type appears in `contract`.
- **Errors are typed**: `UnknownSession`, `SessionFailed`, `SessionEnded` and `ClientAlreadyBound`
  are `JsonRpcApplicationError`s with codes outside the implementation range (4001-4004), registered
  with `.error[E](code, message)`. The mapping encodes the value into the error's `data`, and the
  client decodes it back, so `client.attach(closedId)` aborts with `UnknownSession(id)` rather than
  a message.
- **Host service** (`HostSessions`, in `host`): the registry of live `HostSession`s. A request
  handler has no `Scope`, so each session is opened inside a scope of its own in an unscoped fiber
  and closed by completing a latch that scope waits on; a shell that cannot start publishes the
  failure, which the caller sees as `SessionFailed` instead of a timeout. (`TerminalStarter` was
  avoided for the same reason; this is the structured-concurrency shape it buys, and the version
  that moves it into a `KyoApp` scope belongs to the host phase.)
- **Registration is per client**: `ClientId` → peer handler, plus each client's own **baseline
  frame** per session. Damage is computed per client, `frameSince(thatClientBaseline)`, and the
  baseline advances only once the delta has actually been sent. A single session-wide baseline would
  let a delta computed over a window that straddled an attach be delivered after the client seeded
  its frame, and since damage is absolute, applying it would overwrite newer state with older.
  An attach is authoritative for a client with no frame yet; re-attaching a client that already has
  one leaves its baseline alone, because resetting it would take in-flight deltas out of its history
  and rewind an absolute frame for good. (A frame sequence number on `Damage` would make re-attach
  self-healing; it is not needed while attach only happens at start and on reconnect, which is a new
  client.) Client ids are unique per connection (`client-1`, `client-2`, …) and a duplicate `bind` is
  refused rather than replacing the other peer.
- **Pushes**: one loop per session samples every `pushInterval` (an `init` parameter; the probes run
  at 200 ms, the host at 16 ms) and sends a delta only when one is non-empty, and only to clients
  that attached — a session nobody is watching is not even sampled. Sampling *is* the coalescer:
  everything since the previous tick leaves as one delta, and an unchanged frame costs nothing on
  the wire. Waking the loop from the reader (a `Channel`/`Signal` per session, once the host is a
  `KyoApp`) is a later refinement; the current loops use `Fiber.initUnscoped` because a route handler
  has no scope.
- **Client** (`TerminalService`): UI-free, no `kyo-jsonrpc` in its signatures, with a `FrameSink` for
  the two pushes. `TerminalPeers.inMemory` wires a host peer and a client peer over
  `JsonRpcTransport.inMemory`; the Unix-socket phase replaces only the transport.
- **Frame consumer** (`contract.Frame`): `Frame.from(snapshot)` and `frame.apply(damage)`;
  `Damage.applyTo` is defined in terms of it, so host and renderer cannot disagree about a delta.
  `Snapshot` carries the theme, so a client that only attached can draw everything.

### Damage is absolute and idempotent

Every row a delta carries is a row's whole contents, and applying a delta twice gives the same frame
as applying it once. Two rules follow, and both are load-bearing:

- A delta that changes `size` carries **every** row of the new grid. The row indexes have moved, so
  a row with the same contents at the same index is still drawn in a place that now means something
  else; a same-width height change that sent only differing rows would blank the surviving ones.
- A row outside the frame's range is dropped, not thrown: a stale delta after a shrink is expected
  traffic, not a client bug.

### Ending a session

`session.ended(id, ended)` is sent once per attached client, with `closed` saying who ended it,
`code` (advisory — a host-initiated close reports whatever the PTY made of the signal) and `reason`
for a reader failure. The session then **stays listed with `running = false`**, so a tab can show
its final screen and the client can still read history; `session.close` is what removes it, and
`session.text`/`session.resize` on it fail with the typed `SessionEnded`. Reaping ended sessions on
a timer is a host-phase decision, not this one.

Still planned, not built: `Channel`/`Signal` between the reader and the push loops, `session.key`
and `session.mouse`, a supervisor fiber per host with its own scope, and the ended-session reap
policy.

## Modules (Mill)

```
contract   SessionId, SessionInfo, TermSize, TerminalTheme, Snapshot/Damage DTOs, Frame (the pure consumer), TerminalView
protocol   kyo-jsonrpc route names, schemas, typed errors, ClientRoutes, TerminalService/FrameSink/TerminalClient; depends on contract
host       PTY + jeditermfx-core emulator, HostSession, HostSessions (registry + push loops), HostRoutes, in-memory wiring, probes;
           depends on contract and protocol
renderer   thin JavaFX Canvas: Snapshot/Damage → cells, cursor, selection, scrollback; depends on contract and protocol
ui         shell/tabs/sidebar/state/chords; depends on contract (+ renderer's view factory)
app        composition root: ensure host, connect, build the UI, launch JavaFX
```

The UI is testable with a fake `TerminalService`; the host and renderer are testable headlessly.

## Kyo

- Host, as built: `Scope.run` for the host's lifetime, one `Fiber.initUnscoped` per session (the
  session's own scope inside it) and one more per session's push loop, `AtomicRef`/`AtomicInt` for
  the registry and the per-client baselines, `kyo-jsonrpc` for the peers. Planned for the host
  phase: a `KyoApp` supervisor whose scope owns the sessions, `Channel`/`Signal` to wake the push
  loops instead of sampling, and `kyo-system` for process/path helpers.
- **The reader fiber is deliberately a blocking-FFI fiber.** A pty has no non-blocking read
  without a selector pump, and a pump would put a second thread between the bytes and the
  parser for no gain; so the fiber parks in `read` for the session's life. It is started with
  `-Dkyo.scheduler.virtualizeWorkers=true` (a virtual thread per session, not a platform one)
  and `--add-opens=java.base/java.lang=ALL-UNNAMED` (Kyo's `BlockingMonitor` reads thread state
  to tell a parked fiber from a busy one, so a blocked reader does not count against the
  scheduler's parallelism). Do not "fix" this with a channel pump.
- A session's failure is data, not a scope failure: `drive` catches, records and logs it
  (`HostSession.error`), calls `terminal.disconnected()` so a dead shell stops advertising a
  cursor, and lets the reader end. One broken shell never takes the host down.
- `open` registers the closer **after** the fiber, so on scope close the connector closes first
  and wakes the parked read; that ordering is what makes teardown finish instead of waiting for
  a read that will never return.
- UI: one Kyo `Scope` on a dedicated runtime owns the client and reader fibers. The FX thread is
  a boundary: commands in, snapshots/damage out via `Platform.runLater`; nothing blocks on FX.
- `kyo.scheduler.virtualizeWorkers = true` so fibers run on Loom virtual threads.
- Explicit `A < S`; `kyo-direct` only if a suspension graph justifies it later.
- Required compiler flags: `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`, the `-Wconf`
  promotions (discarded values **and** non-exhaustive matches are errors), and
  `-language:strictEquality`.

## Phases

1. Host-owned screen spike: `jeditermfx-core` + pty4j headless, 5,000-row history, snapshot/delta
   extraction, headless probe (no UI, no socket). Done: `HostProbe` is the regression suite.
2. Protocol + renderer: in-process `kyo-jsonrpc`, JavaFX canvas applying snapshots/damage.
3. Process boundary: UDS transport, host spawn/reconnect, UI client.
4. Live-all + restart: all session frames cached, snapshot on attach, UI restart reattaches.
5. Harden, Kyo skill, docs, remove the old Ox code.

## Decisions recorded by the spike

- **`JediEmulator` + a drive fiber, not `TerminalStarter`**: the starter's constructor takes the
  emulator's collaborators and starts its own thread. The loop is two lines; the thread is the
  reason the rewrite exists.
- **Cell text is a `String` plus a width** (`Narrow`/`Wide`/`Continuation`), not a `Char`: the
  engine stores a character outside the BMP as its two surrogate halves in two cells and marks a
  double-width character with `CharUtils.DWC` in the cell after it. Both are the host's problem,
  not the renderer's.
- **Cursor**: column 0-based, row **1-based** in the engine (`JediTerminal.cursorPosition`
  subtracts one from the column and nothing from the row); the contract converts the row, so
  `Cursor` indexes rows the way `Row` does.
- **Title lives in `Modes`**, not `SessionInfo`: it changes with the terminal's state, so it is
  frame data and reaches a client through damage like the mouse mode does.
- **Theme lives in `SessionInfo`** (`TerminalTheme`): the renderer needs it to draw a cell that
  carries no colour, and it must not re-declare the host's default style.
- **`MouseMode.Focus` exists** because jeditermfx-core models DECSET 1004 in its `MouseMode` enum
  and calls `terminalMouseModeSet` with it; an unhandled case threw inside an emulator callback
  and killed the session. Mode 9 (X10) has no case: the engine does not implement it. `Highlight`
  is DECSET 1001, and `MouseFormat.Utf8` is 1005 — both were mislabelled at first.
- **Resize is serialised by the buffer's lock, not by the drive loop**: `JediTerminal.resize` runs
  inside `TerminalTextBuffer.modify`, the same lock each parse step takes, so it cannot interleave
  with a half-parsed sequence. Handing it to the loop instead (a pending size applied before the
  next parse) was tried and has a hole: an idle session produces no output, so the loop stays
  parked and the grid keeps the old size. No resize merge in the host either: the side that owns
  the window debounces (500 ms, 100 ms on the alternate screen) like upstream's `TerminalPanel`;
  the host applies what it is told.
- **Damage is a frame delta**, because a cursor move, a mode toggle, a title change or history
  growth leaves every cell unchanged and a rows-only delta would leave a client behind forever.

## Non-goals for now

Host restart survival, remote hosts, find/hyperlinks/IME (renderer follow-ups), Windows transport.

## Limits

5,000 rows of scrollback per session (UI cache and host history); base64 damage initially;
selection/copy local in the renderer; scrollback paged from the host; a resize is applied on the
caller's thread under the buffer lock (the PTY is resized at once, so a full-screen program
redraws through SIGWINCH).
