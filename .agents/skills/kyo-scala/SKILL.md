---
name: kyo-scala
description: Scala 3 style plus Kyo effect-system guidance — `A < S`, scopes and resource teardown, fibers and channels, kyo-jsonrpc, kyo-system, KyoApp daemons, and the JavaFX/Kyo boundary. Auto-load for any task touching this code base or any Kyo application.
---

You are an expert Scala engineer working on a Kyo 1.0 application.

This skill is first-party: it was written against **Kyo 1.0.0-RC7** and every worked example comes from this repository
(`corvi-scalafx`: a terminal host daemon in Kyo, a JavaFX UI that only paints). When in doubt, this repository's code is
the authority; the upstream Kyo docs (`https://github.com/getkyo/kyo`) explain the same APIs from the library's side.

# Tooling

- Build with Mill through the project's launcher (`./mill`), never a system-wide `mill`: `.mill-version` pins the version.
- Compile with `./mill -i <module>.compile`; run with `./mill -i <module>.runMain <mainClass>`.
- Format with `./mill -i mill.scalalib.scalafmt/reformatAll`, then `checkFormatAll`. `.scalafmt.conf` (3.11.5, Scala 3
  dialect, `maxColumn = 140`) is the only formatting authority.
- **The project compiles with zero warnings.** Warnings are configured as errors in Kyo modules (see
  [Scala 3 Style](100-scala3-style.md)). Fix warnings in code; `@nowarn` only for generated code, with a comment.
- To look up a dependency, read the canonical resolver's metadata
  (`https://repo1.maven.org/maven2/<group-with-slashes>/<artifact>/maven-metadata.xml`) rather than a search index; remember
  the `_3` suffix. Discovery by name: `https://index.scala-lang.org/api/autocomplete?q=<name>`.
- There is no JUnit suite: the tests are `runMain` probes with PASS/FAIL lines and exit codes. Before claiming a change
  works, run the probes (see [Probes, Modules and Design](170-probes-modules-and-design.md)) and the full probe set.
- After changing the UI, look at it: the probes write `out/corvi-ui.png` and `out/corvi-renderer*.png`.

# Core rules

- **Never run an effect on the JavaFX thread, and never touch a node off it.** One Kyo runtime on a dedicated thread, one
  command queue in, `Platform.runLater` out. Read [The JavaFX/Kyo Boundary](160-javafx-boundary.md) before writing a view.
- **Decide who owns a resource before writing the scope.** Registering a closer *after* the fiber that reads the resource is
  what makes teardown run in the right order. Read [Effects and Resources](110-effects-and-resources.md) before touching
  process/PTY/socket lifetimes.
- **A blocking read is a design decision, not an accident.** If a fiber parks in `read`/`take`, it needs the blocking-FFI
  flags (`-Dkyo.scheduler.virtualizeWorkers=true`, `--add-opens`) and a teardown path that closes the source rather than
  interrupting the read. See [KyoApp and Process Boundaries](130-kyoapp-and-process-boundaries.md).
- **Pair a client with its sink where the client is created.** A value that carries both makes the "two sinks" bug
  unrepresentable. See [Probes, Modules and Design](170-probes-modules-and-design.md#sink-ownership).
- **The host interprets the terminal; the UI paints.** No VT parsing, no PTY, no emulator in the UI. A change that moves
  terminal semantics into the UI is rejected even if it works.
- **Immutable state and pure transitions.** One case class per state, `copy`-returning methods, an enum for what varies.
  No class-level `var`; no `null`; no `Boolean` parameters where an enum says what it means.

# Use-case guide

Read the chapter for your task before writing code. Each one is short and contains the exact APIs this project uses.

## Foundations

- [Scala 3 Style](100-scala3-style.md) — braceless syntax, explicit visibility and types, immutable state with pure
  transitions, enums over booleans, opaque types for identifiers, `CanEqual` and `-language:strictEquality`, the compiler
  flags that make warnings errors, and the `val _ =` idiom `-Wvalue-discard` forces.

- [Effects and Resources](110-effects-and-resources.md) — `A < S` values, `Sync`/`Async`, `Frame`, `Abort`/`Result`;
  `Scope.run`, `Scope.ensure`, `Scope.acquireRelease` and `Sync.acquireReleaseWith`; LIFO release order and the ordering trap
  that makes a blocked reader finish; when an unscoped fiber plus a latch is the honest shape.

- [Fibers, Channels and Shared State](120-fibers-and-shared-state.md) — `Fiber.init`/`initUnscoped`, `Channel`, `Hub`,
  `Latch`, `Fiber.Promise`, `AtomicRef`/`AtomicInt`; when an atomic is right and when it is a smell; the blocking queue that
  sits at a foreign boundary.

## Application shape

- [KyoApp and Process Boundaries](130-kyoapp-and-process-boundaries.md) — `KyoApp`, `runAndBlock` as a bridge,
  `Scope.run` + `System.exit` in a daemon, why the UI cannot be a `KyoApp`, spawning a detached daemon, the scheduler
  flags for blocking-FFI fibers.

- [kyo-system](140-kyo-system.md) — `Command` (builder, `text`, `textWithExitCode`, `waitFor`, `waitForSuccess`, stdin,
  redirection, pipes), `CommandException`, `Path` and its read/write effects; when *not* to use it (a process that must
  outlive its parent).

- [kyo-jsonrpc](150-kyo-jsonrpc.md) — routes and handlers, typed errors and their round trip, calls and notifications,
  in-memory and custom byte transports, the EOF latch, and per-route authorisation instead of a message gate.

- [The JavaFX/Kyo Boundary](160-javafx-boundary.md) — JavaFX owns the main thread, the dedicated Kyo thread, the command
  queue and `Platform.runLater`, FX-confined view state, `JFXApp3`, and the probes' `onFx` bridge.

## Working in this repository

- [Probes, Modules and Design](170-probes-modules-and-design.md) — the Mill module map and its dependency rules, the
  probe-as-test convention (PASS/FAIL, exit codes, real processes), sink ownership, host-owned screens, and the limits
  this project accepts on purpose.
