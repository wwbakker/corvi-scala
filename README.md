# corvi-scalafx

A feasibility spike: Corvi's terminal screen — a tab strip of real PTY-backed terminals — rebuilt as a
Scala 3 desktop application, to answer whether the Electron app could be a JVM app instead.

Scala 3.10 · Mill · ScalaFX (JavaFX 26) · JediTermFX for VT emulation · pty4j for the PTY · ox for
session lifecycle · logback. macOS arm64 in practice (the build picks the right OpenJFX classifier for
macOS, Windows and Linux; only macOS has been run).

This is one screen, not the app: no changes, dashboards, subagents, settings, persistence or remote
workspaces. The point is the window tabs and the terminals behind them.

## What it does

- **One window** shaped like Corvi's: the sidebar (decoration only), the tab strip
  `Overview | <windows…> | new`, and the well the terminals live in.
- **Real terminals.** `new` opens a login shell (`$SHELL -l`, `TERM=xterm-256color`) on a PTY, rendered
  by JediTermFX on a canvas. Typing, colours and resize are the shell's own; mouse reporting and hyperlink
  handling come from JediTermFX and are inherited rather than verified here.
- **Tabs from state.** Opening selects the new window; clicking a tab shows it and takes the keyboard;
  right-click offers `Close tab`; every pane stays mounted so background shells keep running.
- **Overview** is the way back to the placeholder, and the tab strip's fixed left end.
- **A bell** (`--warn` dot) on a tab whose shell produced output while you were looking elsewhere;
  clicking the tab clears it.
- **Ended windows.** `exit` marks that tab (red icon, quiet label) and leaves the others alone.
- **Drag** a tab onto another to reorder the windows; the selection follows the tab that moved.

## Running it

```bash
./mill app.run                                        # the app (opens one terminal)
./mill app.compile                                     # build only

./mill app.runMain corvi.TabsProbeMain                 # 45 checks: tabs, bells, chords, drag
./mill app.runMain corvi.terminal.TerminalProbeMain    # 18 checks: the PTY itself (spawn, resize, exit, teardown)
./mill app.runMain corvi.SnapshotMain                  # renders the window to out/corvi-shell.png

./mill mill.scalalib.scalafmt/reformatAll && ./mill mill.scalalib.scalafmt/checkFormatAll
```

There is no unit-test suite: the two probes are the tests, and they are the evidence that the
behaviours work. `TabsProbeMain` drives the real window (synthetic mouse and key events at the real
nodes) and asserts both the state and what the strip renders; `TerminalProbeMain` drives a real PTY and
asserts what a shell does, including resizing the *window* and that closing a session kills the shell and
leaves no `TerminalEmulator-*` thread.

`SnapshotMain` exists because this machine has no Screen Recording permission — `screencapture` fails
and `osascript` times out — so the only way to look at the UI is to render the JavaFX scene to a PNG. It
waits for the shell's first output and then ten pulses, so the canvas has painted what the model already
holds (capped at 300 pulses, about five seconds); it refuses to write a blank image and it prints where
the keyboard is.

### Metals

`.pi/mcp.json` runs `metals-mcp 2.0.0-RC1` over **Mill's own BSP** (`./mill mill.bsp.BSP/install` writes
`.bsp/mill-bsp.json`), so `build.mill` stays the single source of the project model. The file also passes
the `--add-exports jdk.compiler/...` flags through `JAVA_OPTS`, without which symbol search over the
classpath dies with `IllegalAccessError`. Install the app once with
`cs install metals-mcp:2.0.0-RC1`.

## Chords

| chord | what it does |
| --- | --- |
| `⌘T` (macOS) / `ctrl-alt-t` | new terminal |
| `ctrl-b c` | new terminal (tmux-style) |
| `ctrl-b <n>` | switch to window `n` — **0-based**, as upstream's tabs and tmux count them |
| `⌘W` (macOS) | close the terminal on screen |
| right-click a tab → `Close tab` | close it, on any platform |

Not implemented, deliberately: the copy/paste, find, font-size and `⌘-click` chords of the real app
(`CheatSheet.tsx`), and any close chord off macOS — upstream binds none, and `ctrl-w` belongs to the
shell (delete-word), so elsewhere the tab menu is the way.

## Where it stands

### How the numbers were taken

- **ScalaFX:** a throwaway instrumented harness (a fifth entry point, deleted after measuring) that
  printed `MARK`s with `RuntimeMXBean.getUptime()` — window shown, first painted prompt, three terminals
  with painted prompts — launched **three times** through `./mill app.runMain` and **three times**
  directly as `java -cp <runClasspath>`, with RSS sampled from outside with `ps -o rss=` at each mark.
  All runs warm: caches hot, nothing to compile. The launch-command row below was measured separately,
  and the same workload both ways: `corvi.SnapshotMain`, which paints a prompt, writes a PNG and exits by
  itself, so the command can be timed end to end (three runs each, warm Mill daemon).
- **Electron:** the installed `/Users/wessel/Applications/Corvi.app`, which was **already running**
  (uptime 3h44m, the user's own state) — RSS summed over its processes with `ps`, sizes with `du`,
  sampled twice minutes apart (the process count moved between the samples). **Its startup time was not
  measured**: the app was running, restarting someone's live app was not mine to do, and no launch log
  gives a controlled number. No comparable startup figure is claimed below.

### Startup

| measure (JVM start → …) | six runs |
| --- | --- |
| window on screen | 790–836 ms |
| first prompt painted | 1179–1218 ms |
| three terminals painted | 2344–2414 ms |

| wall clock, launch command → prompt painted, the *same* workload both ways | three runs |
| --- | --- |
| `./mill app.runMain corvi.SnapshotMain` | 1.51–1.69 s |
| `java -cp <runClasspath> corvi.SnapshotMain` | 1.43–1.49 s |

So the app itself takes **~1.2 s from JVM start to a prompt in a working terminal**, with the window up at
~0.8 s, and Mill adds about **0.1 s** over a direct `java -cp` launch once its daemon is warm. An earlier
batch that showed a ~1 s gap was Mill warming up — starting its runner and evaluating the build for an
entry point it had not run before — not a cost of every run. The very first `./mill` invocation on a
machine also downloads Mill's own JVM, and a clean `./mill app.compile` takes about 8 s here.

### Memory

| | one terminal | three terminals |
| --- | --- | --- |
| ScalaFX spike (RSS, sampled seconds after startup) | 266–293 MB | 280–340 MB |

| Electron app (RSS summed over processes, hours of use, samples minutes apart) | |
| --- | --- |
| main process | 180 MB |
| GPU helper | 100 MB |
| network helper | 57 MB |
| **the Corvi server** (`apps/server/src/server.ts`) | 226–228 MB |
| renderer (the page) | 213–215 MB |
| **total** | **0.76–0.89 GB across five or six processes** |

The sum double-counts shared pages, so it overstates; the spike's number is one process, so it does not.
The process count itself moves with what the app is doing: two of my samples saw five, a reviewer's later
sample saw six. The two apps are not the same work either: Electron's total includes the Corvi server and
the whole JS UI, while the spike is one window and no server — and Electron's sample is hours old while
the spike's is seconds old. What they share is the terminal screen.

### Size on disk

| | |
| --- | --- |
| ScalaFX run classpath | 21 entries, **35.8 MB** |
| its largest jars | scala-library 9.84, javafx-graphics 5.48, scalafx 4.41, pty4j 2.82, javafx-controls 2.61, jna 1.95, kotlin-stdlib 1.85 MB |
| the spike's own code and resources | 0.61 MB |
| `Corvi.app` | **286 MB**, of which `Electron Framework.framework` 284 MB |
| the desktop host's own code (`app.asar`) | 16 KB |
| the web bundle (`apps/web/dist`) | 4.2 MB |
| the JS dev tree (`node_modules`) | 886 MB |

Not measured: a *shipped* JVM runtime. Mill's whole Zulu 25 JDK is 381 MB on disk, but a `jlink`ed
runtime for this app would be a fraction of that, and packaging (`jpackage`/`jlink`) is out of scope here.

### The verdict

**What the JVM buys.** The whole terminal screen — window, tab strip, PTY, VT emulation, login shells —
is one process in one language: 266–293 MB of RSS for one window and 280–340 MB for three, sampled seconds
after startup, against Electron's five or six processes and 0.76–0.89 GB sampled after hours of use. (The
samples are not the same age, Electron's total covers the server and the whole JS UI that the spike does
not have, and its sum double-counts shared pages.) There is no Chromium, no IPC boundary to the UI, no dev
server; the app is a 35.8 MB classpath (0.61 MB of it ours) on top of a JVM runtime we have not sized,
instead of a 284 MB browser framework — and the tabs, the shell and the UI are one language.

**What it costs.** A JVM has to boot before anything can be drawn: ~0.8 s to a window and ~1.2 s to a
prompt here, with ~0.1 s more per run under a warm Mill. Memory does not shrink to a thin client's
footprint — 266–293 MB for a single window in these samples. The library stack is a small ecosystem bet:
JediTermFX 1.1.0 is built against JavaFX 19 and ran unchanged on 26 through spawn, resize, render and
teardown in the probes, which is evidence rather than a cross-major guarantee. Under it sit two native
stacks: OpenJFX's own (thirteen `dylib`s across `javafx-graphics` and `javafx-media`) and pty4j's helper.
And packaging, signing, updates and the rest of the app are unbuilt — this spike proves one screen, not a
product.

**Net.** For *this* screen the JVM version is smaller, simpler and more direct at runtime than the
Electron host, and pays for it with ~1.2 s from launch to a prompt and ~280 MB of memory for a single
window. Whether that trade is right for Corvi depends on the rest of the app — which this spike does not
measure.

## Known limitations

- **No human has clicked or typed in this window.** This machine has no display available to an agent
  (and no Screen Recording permission), so the snapshot is a JavaFX *scene* render — no title bar, no OS
  chrome — and every input check is a synthetic JavaFX event fired at the real node. What that does not
  cover: real key repeat and IME, the platform's own `KEY_TYPED` quirks, focus behaviour of a window that
  a human is actually using, and mouse movement thresholds.
- The caret in the snapshot is drawn as the *unfocused* outline, because JediTermFX draws that when its
  window is not the frontmost one — which a harness window on this machine never is. The keyboard really
  is in the terminal: the harness prints `the keyboard is in: Canvas`.
- **macOS arm64 only.** The build selects the right OpenJFX classifier anywhere, but only macOS has been
  run; Windows (ConPTY) and Linux are untested.
- **No packaging.** No `jpackage`/`jlink`, no app bundle, no signing, no update path, no icon.
- **PTY teardown.** A session is a login shell; closing it destroys the process and typing `exit` ends it,
  and both are checked (`childShells` empty, no `TerminalEmulator-*` thread). A full-screen program that
  leaves the terminal in raw mode is not exercised — the shell dies with its session either way, and
  nothing here tries to restore a terminal's termios state.
- **The probes are the only tests**, and they need a display *server* (not a visible one) to run.
- **Not measured:** Electron's startup; CPU or energy use of either app; the spike's memory after hours of
  use (its samples are seconds after startup, while the Electron samples are after hours); a shipped JVM
  runtime's size; whether the two native stacks agree on what they load; the rest of the app's screens in
  either implementation.

## Layout

```
build.mill                     Scala 3.10, OpenJFX classifier, deps (see the comments for why javafx-media
                               cannot be compile-only, and why web/swing only are)
app/src/corvi/
  Main.scala                   JFXApp3: the stage, and quitting in the right order
  AppWindow.scala              the scene, the stylesheet, the scene-level chord filters
  CorviShell.scala             sessions, selection, rendering, the chords; the one holder of ShellState
  ShellState.scala             immutable state and its pure transitions; Selection
  TerminalWindow.scala         one window: label, Idle/Ended, Quiet/Unseen
  WindowTabs.scala             the strip: Overview | windows | new, clicks, the close menu, the drag
  Chords.scala                 the keyboard chords as a pure state machine
  Sidebar.scala, Icons.scala, Theme.scala
  SnapshotMain.scala           renders the window to out/corvi-shell.png
  FxProbe.scala, LiveProcesses.scala   the harness plumbing the probes share
  TabsProbeMain.scala          the tab/chord/drag checks
  terminal/
    TerminalSession.scala      PTY + widget + one ox scope per session; the session's lifetime
    PtyProcessTtyConnector.scala  vendored from JediTermFX's unpublished demo
    CorviSettingsProvider.scala   colours and font for the canvas
    TerminalProbeMain.scala    the PTY checks
app/resources/corvi.css        the palette and every visual rule
app/resources/logback.xml     the console logging backend (INFO)
```
