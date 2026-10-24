# kyo-scala

A first-party skill for Scala 3 + [Kyo](https://github.com/getkyo/kyo) work, written for this repository and drawn from
its code.

## What is in it

- [SKILL.md](SKILL.md) — tooling, core rules, and the index. Read this first.
- [Scala 3 Style](100-scala3-style.md) — braceless syntax, visibility, immutable state, enums/opaque types,
  `CanEqual`, and the warning-as-error flags.
- [Effects and Resources](110-effects-and-resources.md) — `A < S`, `Sync`/`Abort`, scopes and release ordering.
- [Fibers, Channels and Shared State](120-fibers-and-shared-state.md) — fibers, `Channel`/`Hub`/`Latch`/`Promise`,
  `AtomicRef`, blocking fibers.
- [KyoApp and Process Boundaries](130-kyoapp-and-process-boundaries.md) — daemons, `runAndBlock` bridges, spawn
  detachment, scheduler flags.
- [kyo-system](140-kyo-system.md) — `Command` and `Path`.
- [kyo-jsonrpc](150-kyo-jsonrpc.md) — routes, typed errors, transports.
- [The JavaFX/Kyo Boundary](160-javafx-boundary.md) — the one runtime, the queue, `Platform.runLater`.
- [Probes, Modules and Design](170-probes-modules-and-design.md) — the module map, probe-as-test, sink ownership,
  host-owned screens, limits.

## Provenance

Written for this repository against **Kyo 1.0.0-RC7**, replacing the vendored Ox/Tapir skill (from
[VirtusLab/scala-skill](https://github.com/VirtusLab/scala-skill)) once the Ox implementation was removed. The
worked examples are the modules in this checkout; the upstream Kyo documentation explains the same APIs from the
library's side. It assumes Mill and the Metals MCP server this repository wires up in `.pi/mcp.json`.
