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

### Daemon limits

- The default paths are namespaced (`corvi-scalafx`), but they are still a shared directory: two users on one machine each get their own, two
  *builds* of this spike on one account share one. `CORVI_HOST_SOCKET` exists for that.
- The token file is created atomically at 0600 and the comparison is constant-time (`MessageDigest.isEqual`), but the token is a file: anything
  that can read it can talk to the daemon. There is no rotation and no per-connection expiry.
- `host.stop` ends the process rather than the connections one by one, so a client that is mid-request during a stop sees its socket close
  (`Closed`) rather than an error reply. There is no idle exit: the daemon lives until stopped or killed.
- A UI whose host dies is not reconnected automatically: `HostConnection.ensure` starts a new daemon when it runs, and `DaemonApp` does not
  re-run it on `Closed` (the probe covers recovery, the window does not yet).
- `host.log` is truncated on every spawn, so it holds one launch's output, not a history.
- Spawn detachment is `java -cp` with redirected stdio and no handle kept; it does **not** call `setsid`, so a launcher that kills its whole
  process group takes the daemon with it. A packaged app needs its own launcher.
- Windows (TCP or a named pipe) is out of scope; only the transport seam would change.

### Renderer limits

- Font metrics are measured once, from `Text("M")`/`Text("Mg")`: no re-measure on a DPI or font change, and a half-pixel cell width is
  rounded up, which makes the grid slightly wider than needed.
- A `Wide` cell is one glyph drawn at the cell's left edge with its `Continuation` skipped: right for a monospace font whose CJK glyphs are
  exactly two cells wide, wrong for one where they are not. No fallback font, per-script shaping or emoji presentation.
- History is refetched wholesale (`from = 1`, `count = offset + rows`) rather than paged incrementally, copied text loses wrapping and has
  no line join, and a resize while scrolled back does not reflow history.
- Input is paste-and-edit only (see above); the clipboard write is unchecked because the probe machine is headless, and the probe asserts the
  copied text rather than the system clipboard.
- Focus and blink are overridden for captures; neither is asserted against a real window manager, and the blink has no phase relationship
  with the host.

## The renderer

`renderer` turns a `Frame` into pixels and does nothing else: no terminal engine, no PTY, no host. Its module dependencies
(`contract`, `protocol`, ScalaFX) are the proof, and the window, the snapshot harness and the probe live in `rendererApp` precisely because
they start a real host.

- **Pure core.** `Layout` (cell width/height and the pixel/cell mapping), `Selection` (a viewport range, ordered, with `contains`),
  `ScreenModel` (the live grid or history above it, the scroll offset, the new-output mark, and the copy semantics: wide characters once,
  continuations skipped, trailing blanks trimmed). No JavaFX type appears in any of them, so hit-testing, scrolling and copying are checked
  without a toolkit.
- **Painter and canvas.** `TerminalPainter` draws background, per-cell background, selection, glyph (bold/italic via cached fonts, underline
  as a hairline), then the cursor and the new-output pill. `TerminalCanvas` holds the model and the selection and calls the painter; blinking
  is a timeline the view switches on when the frame's cursor blinks, and off for captures.
- **Focus.** A window without the keyboard draws the cursor hollow — stroke only, no fill and no glyph redraw, so the cell keeps its own
  colour: it says where the cursor would be, not that it has focus. Focus is a base flag the view sets from its own `focusedProperty`, with an
  override a harness can drive, because a canvas never takes the keyboard itself.
- **Deterministic captures.** Cursor blink and focus are overrides that frame state cannot undo: without that, a capture taken after any
  output is a coin flip, because every damage would restart the blink timeline. The snapshot harness writes both states
  (`out/corvi-renderer.png` and `out/corvi-renderer-unfocused.png`).
- **The FX/Kyo boundary.** The client's Kyo runtime gets a thread of its own; the FX thread never enters an effect. FX hands commands (text,
  resize, history) to a blocking queue that one fiber drains, and every result and every push comes back through `Platform.runLater`. That is
  two hops in the whole renderer, and it is why `SessionEvents` can promise the FX thread and `FrameSink` is the only place that hops.
- **Input.** Typed characters go out as `session.text`, and the editing keys — Backspace as `^?`, Enter as `\r`, Tab, Escape — are forwarded
  from `KEY_PRESSED` because `KEY_TYPED` reports Backspace as `\b`, which a macOS `stty erase` does not accept. **This build is
  paste-and-edit only**: arrows, modified keys and mouse report nothing, because they need the host's mode-dependent encoding and a
  `session.key`/`session.mouse` route; mouse drag selects locally and wheel scrolls.
- **Scrollback.** The model holds the host's paged history newest-first and draws the newest `offset` rows above the top of the grid. Scrolling
  past what it has asks the host for more, and the rows still in flight are blank rather than filled with grid rows, so the view keeps its
  shape instead of jumping. Reaching the bottom returns to the live grid. Output that arrives while scrolled back sets the new-output mark and
  grows the offset by the same amount the history grew, so the reader stays where they were. Scrolling clears the selection: it is in
  viewport coordinates, which stop meaning anything once the rows move (anchoring to absolute lines is what scrollback copying will need).
- **Resize.** Debounced in the view (500 ms, 100 ms on the alternate screen) because the side that owns the window is the only one that knows
  whether the user is still dragging its edge; the host applies what it is told.

## The shell

`ui` is the window: the sidebar, the tab strip, the Overview placeholder and the mount points for the terminals. It depends on
`contract` **only** — no Kyo, no protocol, no renderer, no VT engine — so `ShellView`, `ShellState`, `Chords` and the strip can be driven
through a fake factory and know nothing about where pixels or bytes come from.

- **The seam.** `contract.TerminalView` is one terminal on screen: a node, focus, a lifetime, and the two signals a tab needs (output
  arrived, the shell ended). `contract.TerminalViewFactory` is where views come from, plus what a tab needs beyond showing one: `create`,
  `close`, and the `gridFor` size query. **Every callback runs on the UI thread**: `RendererViewFactory` hops with `Platform.runLater`, and
  the views promise it on their side (`SessionEvents`). The shell therefore guards no state and never enters an effect.
- **Sink ownership.** A view hears its session's damage only if it is plugged into the sink the client's push routes were wired with. That is
  not a convention but a value: `rendererApp.Client(service, sink)` is made in the one place the client is made (`Harness.start`,
  `HostConnection.ensure`), `RendererViewFactory` takes the whole `Client`, and `dispose` unplugs the view so a closed tab stops being
  iterated. Choosing a second sink is exactly what once left every tab parked on its seeded frame.
- **Create and close are asynchronous.** They are offered to the client's own runtime (`Client.Calls`, one fiber draining a blocking queue,
  the same shape as a session's command loop) and report back through `Platform.runLater`. Nothing on the FX thread waits for the host, so a
  wedged daemon freezes neither the window nor the strip; a tab appears when the host has answered, and a refusal is `onCreated(None)`.
- **Render policy.** The state is one immutable `ShellState`; a transition that returns the same value does not repaint at all (output in the
  window you are looking at changes nothing). A render during a tab drag is deferred (`TabDragState`) and flushed when the gesture ends,
  because it would otherwise update the nodes the drag is running on. The strip keeps one tab node per window and **updates it in place** —
  label, bell, style classes, tooltip — touching the children list only when the set or order of windows changes. Rebuilding the strip
  wholesale would take the node a tab's `ContextMenu` hangs off out of the scene, so a bell on a neighbouring tab could close a menu you
  were using.
- **`gridFor`**: a new session is asked for at the grid the terminal area has now, so the host's first frame already fits the window. Before
  the first layout the area has no size and the factory's default (100×28) stands in.
- **Ending.** `Ended` is carried into the window rather than discarded: `Ending.Exited(code)` (a natural exit; the code is in the tab's label
  and tooltip), `Ending.Failed(reason)` (the reader died; the tooltip carries the reason) or `Ending.Closed` (a client close — you closed the
  tab — with no extra marker). `closed` wins over everything else: a close's code is the signal the host sent, not the shell's own status.
  Otherwise a reader failure is `Failed` and a code is `Exited`. An ended window stops asking for attention, so it never bells.
- **Not built: reconnect.** `ShellApp` ensures the host, lists once and attaches; if the daemon goes away the window keeps the last frames it
  received. The architecture already makes a host's death the shells' death, so this phase records the gap instead of hiding it: restarting
  the window reattaches to whatever host answers then. A UI restart against a *live* host works — that is the case that was in scope.
- The duplicate Ox sources in `app/` are still in the tree and are still what `./mill app.run` runs; removing them is the next chunk.

## The daemon

`corvi.host.HostMain` is the long-lived process: a `KyoApp` with no JavaFX that holds the shells and listens on a Unix domain socket. The UI
connects to it; it does not own it.

- **Transport.** `protocol.SocketWire` implements `kyo-jsonrpc`'s byte seam (`JsonRpcWireTransport`) over a blocking `SocketChannel`, and
  `JsonRpcTransport.fromWire` lifts it with `JsonRpcFramer.contentLength`. Both directions park in blocking reads and writes on fibers — the
  same blocking-FFI posture as the PTY reader, which is what `virtualizeWorkers` is for. The only thing the transport adds over the seam is a
  `Latch` released on EOF, because the handler's dispatch fiber would otherwise wait for a stream that has already ended.
- **Where it lives.** `$XDG_RUNTIME_DIR/corvi-scalafx/host.sock`, else `~/.local/state/corvi-scalafx/host.sock` — a name of its own, because
  the installed Corvi app keeps its single-instance socket in `.../corvi` and this spike must never bind, replace or delete that one. The socket
  and the token file next to it are both 0600 in a 0700 directory. `CORVI_HOST_SOCKET` overrides the socket (the token sits beside it), which is
  how a probe runs a daemon without touching the user's own. The token is 32 random bytes, hex, created atomically at 0600 (`CREATE_NEW`, the
  loser of a spawn race re-reads) and *reused*, so a UI that restarts reads the same file the daemon did.
- **One instance, within its own family.** Binding the socket *is* the lock, and a `host.sock.owner` marker records which pid owns it. Before
  binding, the daemon makes a non-blocking connect (500 ms deadline): a socket that answers means another host is running, and this one exits
  with one line. A socket file whose marker names a dead pid is this daemon's own dead predecessor: socket and marker are removed and the path
  is taken. A socket file with *no* marker is somebody else's — the installed app's — and is never unlinked.
- **Connection lifetime.** Each connection runs in a scope of its own, in an *unscoped* fiber: the handler, the wire, the socket and the
  client's registration all end with the connection, however it ends. The end is `SocketWire`'s latch, released from three sites — EOF from the
  read, a read failure, and a local close — and `Latch` release is once-only, so the first wakes the connection's `await` and the rest are
  no-ops. Reconnecting therefore costs the daemon nothing: the probe measures `lsof` before and after ten reconnects and allows a slack of two.
- **Authorisation.** Every connection starts unauthorised. `host.hello(token)` is the only thing it can call; the session routes go through
  `guarded`, which aborts with the typed `Unauthorized` until the token matched. This is per route rather than a `JsonRpcMessageGate` because a
  gate's rejection carries the handshake request's id, and the caller would wait for an answer to a request it did make. The token comparison
  is a plain `==`: this is a per-user door behind a 0600 socket, not a networked secret.
- **Stopping.** `host.stop` ends the accept loop and lets the scope close: the sessions are released (LIFO), the socket and its owner marker
  are unlinked, and the process exits. Connection handlers are **not** closed by the stop: a handler closing itself from inside its own request
  deadlocks, and the process exit closes their sockets anyway. Closing the listener is what ends the accept loop's blocking `accept` — it works
  because `ServerSocketChannel` is an `InterruptibleChannel`; a non-interruptible accept would leave the scope waiting for a connection that can
  never come.
- **Starting.** `HostConnection.ensure` (in `rendererApp`, not in `renderer`) connects, and if nothing answers spawns the daemon with this
  JVM's own `java`, classpath and Kyo flags, its output appended to `host.log` beside the socket, its stdin `/dev/null`, and drops the handle:
  nothing waits for it, nothing destroys it. Then it waits for the socket and says hello. The packaged app will need a launcher of its own;
  `java -cp` is what a Mill-run UI can do.
- **Reattaching.** A UI start is `sessions.list`, `attach` for each session, seed the frame, then live pushes. A second client can attach to the
  same host at the same time; damage is pushed per client from that client's own baseline, so neither disturbs the other.
- Windows (TCP or a named pipe) is out of scope; only the transport seam would change, not the routes or the lifecycle.

## Modules (Mill)

```
contract   SessionId, SessionInfo, TermSize, TerminalTheme, Snapshot/Damage DTOs, Frame (the pure consumer), TerminalView
protocol   kyo-jsonrpc route names, schemas, typed errors, ClientRoutes, TerminalService/FrameSink/TerminalClient; depends on contract
host       PTY + jeditermfx-core emulator, HostSession, HostSessions (registry + push loops), HostRoutes, in-memory wiring, probes;
           depends on contract and protocol
renderer   thin JavaFX canvas: Frame → cells, cursor, selection, scrollback; depends on contract, protocol, ScalaFX
rendererApp window, snapshot harness and probe: they start a real host, which the renderer must not
ui         shell/tabs/sidebar/state/chords; depends on contract only, over the TerminalView seam
app        the old Ox app, still built and still what `./mill app.run` starts; removed next phase.
           The Kyo window is `rendererApp`'s `ShellApp`
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
- UI: each view owns its Kyo runtime (`TerminalSession`: one fiber draining a command queue, around
  the attach), and the client owns one more for the tab operations that outlive a view
  (`Client.Calls`: create and close). The FX thread is a boundary: commands in, snapshots and damage
  out via `Platform.runLater`; nothing blocks on FX.
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
redraws through SIGWINCH). The UI does not reconnect to a host that dies (see "The shell").
