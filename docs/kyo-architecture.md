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
- `session.exited(id, code)`, `sessions.list`, `session.create`, `session.close`.
- Base64 cell payloads first; a compact binary framing can replace them later without changing
  the routes.

## Modules (Mill)

```
contract   SessionId, SessionInfo, TermSize, TerminalTheme, Snapshot/Damage DTOs, TerminalService, TerminalView
protocol   kyo-jsonrpc routes and wire codecs; depends on contract
host       KyoApp: PTY + jeditermfx-core emulator + registry + protocol server; depends on protocol
renderer   thin JavaFX Canvas: Snapshot/Damage → cells, cursor, selection, scrollback; depends on contract
ui         shell/tabs/sidebar/state/chords; depends on contract (+ renderer's view factory)
app        composition root: ensure host, connect, build the UI, launch JavaFX
```

The UI is testable with a fake `TerminalService`; the host and renderer are testable headlessly.

## Kyo

- Host: `KyoApp`, `Scope.run`, `Scope.acquireRelease` for PTYs, one `Fiber.init` per session
  reader, `Channel` for output, `AtomicRef`/STM for the registry, `Signal` for session changes,
  `kyo-jsonrpc` for the server, `kyo-system` for process/path helpers.
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
