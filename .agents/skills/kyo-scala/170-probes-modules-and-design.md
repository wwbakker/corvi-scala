# Probes, Modules and Design

## Dependencies

- Mill 1.3 (`build.mill`, `.mill-version`, `./mill`).
- The probes: `kyo-core`, `kyo-jsonrpc`, a real host, and (for the UI probes) a display *server* — a visible one is not
  required.

---

## The module map

```
contract    DTOs only: SessionId, SessionInfo, Snapshot/Damage, Frame (the pure consumer), TerminalView/Factory. No
            JavaFX, no Kyo, no terminal engine.
protocol    kyo-jsonrpc routes, schemas, typed errors, the client half (TerminalService) and the socket seam. Depends on
            contract; must never pull pty4j or jeditermfx in.
host        PTYs + the VT emulator (jeditermfx-core) + HostSession/HostSessions, the daemon's routes and its probes.
            Depends on contract and protocol. No JavaFX.
renderer    Frame -> pixels. JavaFX canvas, painter, layout, the per-session client bridge. Depends on contract and
            protocol, never on host.
ui          sidebar, tab strip, Overview, ShellState/Chords. Depends on contract only, over the TerminalView seam.
app         composition root: HostConnection, Client, the view factory, ShellApp/AppWindow. Depends on renderer, host and ui.
*.test      every probe, harness and test: JUnit 5 for the pure core (`ui.test`, `renderer.test`), TestFX for the window
            (`app.test`), and `runMain` probes where a real PTY/daemon/scene is the point (`host.test`, `app.test`). No
            production module contains test code, and none depends on JUnit or TestFX.
```

The dependency arrows are the architecture. Two are load-bearing:

- **`ui` does not depend on `renderer`.** The shell is written against `contract.TerminalView`/`TerminalViewFactory`, so it
  can be driven by a fake factory and knows nothing about Kyo, sockets or pixels.
- **`renderer` does not depend on `host`.** A renderer that linked the VT engine could parse bytes "just this once"; the
  module graph makes that impossible rather than discouraged. Anything that needs both lives in `app`.

Rules for `build.mill`:

- every module sets the compiler flags from [Scala 3 Style](100-scala3-style.md);
- Kyo modules add `-Xmax-inlines:128`;
- modules that run blocking fibers set `forkArgs` with the scheduler flags;
- dependency exclusions are one `;exclude=...` segment each (Mill silently reads a comma-separated list as a single
  bogus exclusion), and OpenJFX needs the per-platform classifier jar, not the empty stub.

## The probe-as-test convention

There is no JUnit. A probe is an `object` with a `main` that drives the real thing and prints one `PASS`/`FAIL` line per
check; it exits non-zero if any failed. That makes it runnable by a person (`./mill -i app.runMain corvi.app.UiProbe`)
and by CI, with no test framework in the dependency graph.

```scala
object UiProbe:
  def main(args: Array[String]): Unit =
    val finished = CountDownLatch(1)
    val failures = AtomicInteger(0)
    Platform.startup(() => ())
    val thread = Thread.ofPlatform().name("corvi-ui-probe").daemon(true)
    val _ = thread.start { () =>
      failures.set(run())
      finished.countDown()
    }
    finished.await()
    val failed = failures.get()
    println(if failed == 0 then "all ui checks passed" else s"$failed ui check(s) failed")
    Platform.exit()
    java.lang.System.exit(if failed == 0 then 0 else 1)
```

The reporter is deliberately tiny; one check, one line, with the observed value as detail:

```scala
private final class Reporter:
  private val failed = AtomicInteger(0)

  def report(name: String, ok: Boolean, detail: String): Unit =
    if !ok then
      val _ = failed.incrementAndGet()
    println(s"${if ok then "PASS" else "FAIL"}  $name")
    detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(2).foreach(line => println(s"      | $line"))

  def failures: Int = failed.get()
```

Two flavours, and a change usually needs both: a **pure probe** (`CoreProbe`) that checks values in and values out with
no toolkit, host or process — it runs in a second and pinpoints the state machine — and the **integration probes** below,
which drive the real thing:

- **They drive the real thing.** `HostProbe` starts a login shell on a real PTY. `ProtocolProbe` runs the daemon's routes
  against real shells. `UiProbe` builds the real window and fires real JavaFX events at the real nodes (clicks, chords, a
  right-click and its menu, a drag with pick results). `RendererProbe` renders real frames to pixels and checks the
  canvas and hit-testing.
- **They wait with a deadline, never with a sleep.** `poll(timeout)(condition)` re-evaluates with small sleeps until the
  deadline; a slow machine makes a check slower, not flakier.
- **They read the model, not the pixels, when possible** — and the pixels when that is the claim (a screenshot). Colours
  are read after `applyCss()`, because CSS lands on the next pulse.
- **A click is a press, a release and then the click.** A test that fires only `MOUSE_CLICKED` misses every state machine a press
  starts: the tab strip's drag state was left `Pressed` by a plain click, so the next render was deferred forever and the
  strip stopped updating — invisible to the probe until its clicks became real ones. Drive the sequence the platform sends.
- **They assert the absence of leaks**: descriptor counts before and after connections (`lsof`), no orphaned daemons,
  no view left in a sink after its tab closes, no `TerminalEmulator-*` thread after a session ends.
- **They clean up**: stop the harness host, close the stage, `System.exit`.

`./verify` runs the suites (and, without `--quick`, the probes) in one step; `./verify --quick` is the suite-only gate.

The full set, and the command for each:

| probe | what it covers |
| --- | --- |
| `ui.test`, `renderer.test` | the pure core as JUnit: `ShellState` transitions, the chord matrix, `ScreenModel` viewport/paging/copy, `Layout`/`Selection` |
| `corvi.host.HostProbe` | PTY, VT emulation, snapshot/damage, history, resize, session teardown |
| `corvi.host.ProtocolProbe` | the routes over the in-memory transport: typed errors, auth, pushes, reconnects |
| `corvi.host.HostDaemonProbe` | the socket daemon: single instance, token, stop, descriptor leaks, liveness |
| `corvi.app.RendererProbe` | the renderer: layout, selection, scrollback, copy, captures |
| `corvi.app.UiProbe` | the window: tabs, chords, bells, ended tabs, drag, menus, focus, the screenshot |

## Sink ownership

A view hears its session's damage only if it is plugged into the sink the client's push routes were wired with. Making
that a convention is not enough — choose the wrong sink and every tab silently parks on its seeded frame. So the pair is
a value:

```scala
/** A host connection and the sink its pushes land in, as one value. */
final case class Client(service: TerminalService, sink: ViewSink)
```

It is created only where the client is created (`HostConnection.ensure()` builds the sink and wires the routes to it;
`Harness.start` does the same for the in-memory host), and everything downstream takes the `Client` — the view factory,
the probes, the window. A view unplugs itself when it is disposed, so a closed tab stops being iterated.

The rule generalises: when two values must agree for a feature to work at all, make them one value at the place they are
created.

## Host-owned screen, thin renderer

The host interprets the terminal; the UI only paints.

- The host owns a PTY and a headless emulator (`jeditermfx-core`) per session, keeps 5,000 rows of history, and sends
  **damage**: the rows whose cells changed, plus the cursor, modes, history depth and grid size when those differ. Rows
  alone are not a delta.
- The UI caches one frame per session, applies damage for **all** sessions in the background (tab switching is instant),
  and touches no terminal bytes. Damage is absolute and idempotent, so applying it twice is the same as once, and a stale
  delta after a resize is dropped rather than thrown.
- A resize is decided by the side that owns the window (debounced there: 500 ms, 100 ms on the alternate screen) and
  applied by the host.
- Theme travels in `SessionInfo`; the title travels in `Modes`, because it changes with the terminal's state.

Why: the VT engine has no JavaFX dependency, so it runs in a daemon with no toolkit; the UI process is a renderer that
cannot corrupt a screen it does not understand; and a UI restart is a reattach, not a restart of the user's shells.

## The UI-thread callback contract

`contract.TerminalView` promises that `onActivity` and `onEnded` run on the UI thread, and `TerminalViewFactory.create`
delivers `onCreated` with `Platform.runLater`. Anything implementing the seam owes the shell that promise; anything
consuming it may keep unsynchronised state. Write the contract in the trait's Scaladoc, because the compiler cannot check
which thread a callback runs on.

## Limits this project accepts on purpose

Read the README before "fixing" one of these; each is a decision, not an oversight:

- no reconnect when the host dies (the shells die with it — a UI restart against a live host is the supported case);
- no idle exit and no `setsid` for the daemon;
- only the Unix socket transport (Windows is out of scope);
- the renderer is paste-and-edit: keys that need mode-dependent encoding (arrows, modified keys, mouse reports) are not
  forwarded yet;
- find and hyperlinks are not implemented;
- no host restart survival: the host's death is the shells' death.
