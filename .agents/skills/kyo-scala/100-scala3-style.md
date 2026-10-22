# Scala 3 Style

## Dependencies

None beyond Scala 3 itself. This project uses **Scala 3.10**.

---

## Braceless syntax

Every construct uses indentation syntax: no braces around bodies, matches, classes or for-comprehensions. `if`/`else`
chains read as a single expression; a `match` arm block is introduced by `case ... =>` and indented.

```scala
private def iconClasses(state: TerminalWindow.State): Seq[String] =
  state match
    case TerminalWindow.State.Idle  => Seq("tab-icon", "idle")
    case TerminalWindow.State.Ended => Seq("tab-icon", "ended")
```

Guards go on the `case` line; multi-line arguments are either indented or parenthesised. Scalafmt normalises the rest:
never hand-align columns.

## Visibility at the declaration

Every top-level class, trait, object, enum and opaque type states its visibility:

- no modifier — public API of the module,
- `private[corvi]`, `private[app]` and finer — chosen by scanning the actual call sites; `private[corvi]` is the usual
  package-level default, `private[host]`/`private[app]` (on a constructor or a factory) the tightest one that compiles.

```scala
private[corvi] final class HostSession private[host] (...)   // constructed only by its own companion
private[corvi] opaque type WindowId = Int                     // a UI detail, not a Corvi-wide id
object Methods                                                // protocol names: genuinely public in the module
```

`private[app]` is used inside the `app` module for values the probes and the window share but that no other module
should see. Default-public is a deliberate statement, not an accident.

## Explicit types on public surface

Every public `def`, `val` and `given` spells out its type. Kyo's `A < S` return types are the whole contract; inferring
them means a refactor can silently widen the effect set.

```scala
def list(using Frame): Seq[SessionInfo] < Sync
def create(cwd: String, size: TermSize)(using Frame): SessionInfo < (Async & Abort[UnknownSession | SessionFailed])
def answers(socket: Path, timeout: Duration): Boolean
```

Inside a function, local `val`s may be inferred when the right-hand side is obvious. Types on locals that carry an
effect (`A < S`) are usually worth writing too, because they document which effects the block needs.

## Immutable state, pure transitions

State is a case class; every change is a method that returns a new value. No class-level `var`, no mutable collections.

```scala
private[corvi] final case class ShellState(
    windows: Vector[TerminalWindow],
    selection: Selection,
    sessions: Map[WindowId, SessionId],
    nextId: WindowId
) derives CanEqual:

  def opened(label: String, session: SessionId): (ShellState, WindowId) =
    val window = TerminalWindow(nextId, label, TerminalWindow.State.Idle, TerminalWindow.Activity.Quiet, ending = None)
    val next = copy(
      windows = windows :+ window,
      sessions = sessions + (window.id -> session),
      selection = Selection.Window(window.id),
      nextId = nextId.next
    )
    (next, window.id)

  def activity(id: WindowId): ShellState =
    if isSelected(id) then this
    else update(id)(window => window.copy(activity = TerminalWindow.Activity.Unseen))
```

Two habits make this work:

- **Return `this` when nothing changed.** A transition that produces the same value lets callers skip work
  (the shell does not repaint for output in the window you are looking at).
- **Return the new identity with the new state** (`(ShellState, WindowId)`) instead of making the caller read
  "the last window" back out. One question, one answer.

Where a foreign toolkit owns the thread (JavaFX), the single holder may be a property — `ObjectProperty[ShellState]` —
because the toolkit's thread confinement is the safety argument. It is still not a `var`, and it is still FX-thread-only:

```scala
private val state: ObjectProperty[ShellState] = ObjectProperty(ShellState.initial)

private def applied(next: ShellState): Unit =
  if next != current then
    state.value = next
    render()
```

The rule's spirit: *state is a value, changes are functions.* A `var` at class level hides both.

## Enums over booleans, opaque types over primitives

A `Boolean` parameter is unnamed intent. Model the two cases:

```scala
private[corvi] enum TabDrag derives CanEqual:
  case Idle
  case Pressed(from: WindowId)
  case Carrying(from: WindowId)
  case Dropped(from: WindowId)

  def inFlight: Boolean = this match
    case Idle | Dropped(_) => false
    case _                 => true
```

Identifiers that are "just an Int" get an opaque type, so a position cannot be passed where an id is expected:

```scala
private[corvi] opaque type WindowId = Int

private[corvi] object WindowId:
  def apply(value: Int): WindowId = value
  given CanEqual[WindowId, WindowId] = CanEqual.derived
  extension (id: WindowId)
    def value: Int = id
    def next: WindowId = id + 1
```

Other opaque types in this code base: `SessionId`/`ClientId` (contract/protocol), `Ending` as an enum with payloads
(`Exited(code)`, `Failed(reason)`, `Closed`), `Activity`/`State` as two-case enums rather than flags.

## `-language:strictEquality` and `CanEqual`

Every module compiles with strict equality, so `==` only works where `CanEqual` exists. Case classes derive it:

```scala
private[corvi] final case class TerminalWindow(...) derives CanEqual
```

Java enums and JavaFX value types cannot carry a given in their companion (it is Java's), so the project puts them at
package level in an `FxInterop.scala`, and callers import them explicitly
(`import corvi.renderer.given` — a wildcard `import p.*` does **not** import givens):

```scala
given keyCodeCanEqual: CanEqual[KeyCode, KeyCode] = CanEqual.derived
given mouseButtonCanEqual: CanEqual[MouseButton, MouseButton] = CanEqual.derived
given colorCanEqual: CanEqual[Color, Color] = CanEqual.derived
```

Reference equality is always available with `eq`/`ne`, which is the right tool for "is this the same node/thread":

```scala
def unplug(view: TerminalView): Unit =
  val _ = targets.updateAndGet(current => current.filterNot(_ eq view))
```

## The compiler flags

Every Scala module in `build.mill` sets these (Kyo modules also add `-Xmax-inlines:128`):

```scala
def scalacOptions = Seq(
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wconf:msg=@nowarn annotation does not suppress any warnings:s," +
    "msg=unused:e," +
    "msg=(discarded.*value|pure.*statement):error," +
    "msg=match may not be exhaustive:e",
  "-language:strictEquality"
)
```

What each buys:

- `-Wvalue-discard` makes "you ignored a value" an error. Kyo's `A < S` values must be composed or explicitly run; a
  dropped one is a bug. The idiom for a deliberately discarded value is `val _ = ...`, which says so out loud.
- `-Wnonunit-statement` flags an expression whose result is discarded inside a block (usually a missing `val _ =`).
- `-Wconf:...` is **one** option whose filters are matched first-wins: silence Kyo's stale `@nowarn` annotations, then
  promote `unused`, discarded values and non-exhaustive matches to errors.
- `-language:strictEquality` forces an explicit `CanEqual` at every comparison of non-obvious types.
- `-Xmax-inlines:128` because Kyo's `A < S` is inline-heavy: a probe with a dozen nested `for` comprehensions otherwise
  exceeds the compiler's inlining budget.

`-Wunused:all` treats an unused import as an error. When a type is only mentioned in a doc comment (as
`Client.scala` mentions `TerminalSession`), do not import it.

## No `null`, no thrown exceptions for recoverable failures

Absence is `Option` (Scala collections) or `Maybe` (Kyo collections); errors are `Abort[E]` values (see
[Effects and Resources](110-effects-and-resources.md)). Exceptions belong to foreign boundaries (JavaFX event dispatch,
`ProcessBuilder.start`), where they are caught once and turned into a typed value:

```scala
private def run[A](call: A < (Async & Abort[TerminalError | Closed]))(using Frame): Option[A] =
  import AllowUnsafe.embrace.danger
  KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(60.seconds))(call) match
    case Result.Success(value) => Some(value)
    case Result.Failure(error) =>
      java.lang.System.err.println(s"corvi ui probe: the host refused: $error")
      None
    case Result.Panic(error) =>
      java.lang.System.err.println(s"corvi ui probe: the host call panicked: $error")
      None
```

Java interop hands back `null` (a lookup that missed, `getParent` on a root). That is the documented exception to this
rule: JavaFX lookups, `SocketChannel.accept()` at EOF and other foreign APIs report absence that way, so `null` is
expected **at the boundary only** — convert it with `Option` (or compare with `ne null`) there and never let it travel:

```scala
private def isInside(node: JfxNode, ancestor: JfxNode): Boolean =
  (node ne null) && ((node eq ancestor) || isInside(node.getParent, ancestor))
```

## One concern per function

A function either does one thing or orchestrates a few named steps; it never validates, transforms, persists and
notifies. Names carry the intent, and a comment explains *why* where the code cannot:

```scala
/** The tab's own colour is the icon's when the window has no state to report (the `new` tab); a window adds its own, and
  * that keeps its colour even while the tab is current.
  */
private def iconClasses(state: TerminalWindow.State): Seq[String] = ...
```

Comments describe the reason, not the mechanics: "White, since it contrasts with black" — never a retelling of the code
or of the commit that produced it.
