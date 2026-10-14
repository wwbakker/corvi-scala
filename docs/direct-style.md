# Direct style in this app: what Ox owns, and what JavaFX will not let it

## The arrangement

`Main` opens **one** `supervised` scope on the process's main thread and calls `Application.launch` — through
`CorviApp`, a `JFXApp3` — from inside it. JavaFX keeps the thread macOS documents for its app kit, and Ox owns
every long-lived piece of work: a terminal's watcher (blocking on its shell, tearing the widget down when it
goes) is a `forkDiscard` of that scope rather than a thread of its own. Per session there is therefore no raw
thread, no close-signal channel and no `AtomicBoolean` trio, only one lifecycle value.

`SessionForker` is the one bridge between the two worlds, and it exists because of the first experiment below:
the FX thread cannot fork, so it hands a freshly created session to a channel, and a loop forked *inside* the
scope does the forking. It is the only channel in the app, and the loop keeps no state — the handles are not
needed, because closing a session destroys its shell, which is what wakes its watcher; the scope's own
cancellation covers the rest when the app quits.

## The two experiments (throwaway harnesses, deleted after measuring)

**1. Can a foreign thread fork into a scope owned by another thread? No.**

```
EXP main thread: main
Exception in thread "foreign" java.lang.IllegalStateException: Forks can only be started inside a running
concurrency scope, or from other forks started in the scope. Creating this fork was attempted from an
unrelated thread: foreign.
```

(The check lives in `ThreadHerd.verifyCurrentThreadInScopeTree`; the compiler enforces the same rule — `fork`
needs `using Ox` in lexical scope, which is why the experiment had to hand the capability over explicitly
before it could even be attempted at runtime.)

**2. Can an app-level scope own JavaFX? Yes for its lifetime, no for its handlers.**

The real window, launched two ways, twice each:

| | window shown | first prompt | scene |
| --- | --- | --- | --- |
| `CorviApp.main` on the process main thread (today's shape) | 804–954 ms | 1186–1342 ms | 1180×712, 5463 differing samples |
| `supervised { forkUser { CorviApp.main(args) }.join() }` | 793–801 ms | 1191–1199 ms | identical |

The scope variant ends cleanly (`EXP scope ended`) and costs nothing measurable. A fork attempted from the FX
thread inside it still fails:

```
EXP FX-thread fork: IllegalStateException: … attempted from an unrelated thread: JavaFX Application Thread.
```

That is the whole reason for the bridge: JavaFX runs its handlers on a thread the *toolkit* owns, whatever
thread called `Application.launch`.

*Aside, correcting the plan:* the plan says `OxApp` is incompatible with JavaFX because it runs the entry
point on a virtual thread. Launching on an Ox fork (`forkUser`) *did* render correctly here — the toolkit
started, the window rendered its real content, the prompt appeared, the scope ended. No macOS main-thread
failure was reproducible. We still keep `Application.launch` on the process main thread inside the scope:
that is the documented-safe arrangement for the app kit, and the measurements above show it costs nothing.

## What cannot move, and why

* **The FX-thread-confined state.** `ShellState`, the chord state, the drag state and the session's lifecycle
  are touched only from the FX thread (plus the one marshalled `Platform.runLater` a session makes when output
  arrives or a shell exits). JavaFX is single threaded, and its thread cannot enter a scope, so Ox cannot
  protect this state and must not try; the holders are `ObjectProperty`/`AtomicReference` values confined to
  `CorviShell` and its collaborators, documented at each one.
* **Event handlers.** The strip's mouse handlers, the scene's two key filters and the window's close handler
  are JavaFX callbacks: they run on the FX thread at times the toolkit chooses, and a `fork` there throws (see
  experiment 2). They therefore do what they can on the FX thread — mutate state, update nodes in place — and
  hand the one genuinely concurrent act (starting a watcher) to `SessionForker`.
* **JavaFX types at the boundary.** The strip is wired with raw JavaFX handlers (pick results and mouse
  buttons are JavaFX types), and `Chords.Key.from` is the single place a `KeyEvent` is read; everything past
  it is `Chords.Key`, our own value. pty4j's environment is a Java `Map`, handed over as `asJava` of an
  immutable Scala map, so no mutable collection crosses that boundary either.

## Ox features used, and the ones deliberately not used

Used: `supervised` (the app scope), `forkDiscard` (the bridge loop and each session's watcher),
`Channel.buffered`/`sendOrClosed`/`foreachOrError`/`done` (the bridge), `.discard` for the once-only sends.

Not used, with the reason:

* `useInScope` / `releaseAfterScope`: chapter 170 is explicit that scope-managed resources are released
  *after* the forks are joined, so a kill registered there runs when the join has already deadlocked. Here the
  teardown is the watcher's own `finally` (and the synchronous `close` for a user-initiated one), which is the
  ordering the app needs: the shell must be dead *before* `Platform.exit()`.
* `Actor`: the bridge serialises *intents*, not state — there is nothing for an actor's `logic` to own. (Ox 1.0.9
  has no `Ref` either; the atomics that remain are deviations, below.)
* `either` blocks: the failures here are startup failures (a shell that cannot spawn, a missing stylesheet),
  which the caller cannot handle meaningfully — throwing is the rule chapter 200 gives. The one union that
  reads better is used: `sendOrClosed(...).discard`.

## Remaining deviations, and why each is justified

1. **The session's lifecycle is an `AtomicReference`, which the skill forbids.** *"Never use `AtomicReference`
   as a lifecycle, cancellation, or shutdown flag. Model lifecycle with Ox scope structure or channel
   completion"* (`150-shared-state-across-threads.md`), and its selection table says the same: *"never use it
   for lifecycle flags"*. It stays because it is the one value that has to cross both worlds: `close` writes
   `Closing` from the FX thread while the watcher's fork CASes `Exited`, so no scope can own it. The CAS is what
   makes "did the shell exit on its own?" deterministic — exactly one of the two paths reports — and the skill's
   remedy, channel completion, would mean a second channel plus a `select` for one three-valued cell, on the
   thread that must not block.

   The close-versus-exit contract that falls out of it: **an exit observed first wins** — `watch` CASes
   `Open → Exited` *before* it marshals the report, so a `close` arriving in between sees `Exited` and does not
   dispose a second time — and **a late report for a closed window is a no-op**, because `ShellState.ended` on
   an id that is no longer there returns the same state.
2. **`SessionForker`'s channel.** Forced by experiment 1+2: the FX thread cannot fork, so *some* hop is
   required. Kept as small as possible (one direction, no state, three lines of loop).
3. **The harnesses host a scope of their own.** `TabsProbeMain` and `TerminalProbeMain` run their checks
   inside `supervised` on a platform thread they start, and use the same `SessionForker` the app does;
   `SnapshotMain` (a `JFXApp3`) opens its scope on a helper thread for the same reason the app's scope lives
   on `Main`. Harness code, and the app's own arrangement is the one being exercised through it.
4. **`Platform.runLater` for the outputs.** `Session.watch` and `CorviShell.noteOutput` marshal to the FX
   thread because that is where the state they touch lives. The alternative — channels back into the scope —
   would add a second bridge to carry one flag.
5. **The session's id is a plain `Int`.** `corvi.terminal` does not need the shell's `WindowId`; the shell
   converts at the two call sites. Keeping the dependency out of that package is worth the two `.value`s.
6. **The harnesses' counters** (an `AtomicInteger` of reads, a `CountDownLatch` for an exit) are probe-only
   bookkeeping, kept because they are what the checks observe from outside the app.
