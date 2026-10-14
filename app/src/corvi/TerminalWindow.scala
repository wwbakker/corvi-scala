package corvi

/** A window's identity, kept distinct from a position: both are `Int`, and confusing them is exactly the mistake the tab strip invites —
  * `ctrl-b <n>` takes a position, a drag takes ids, and `closed` takes an id while `moved` takes two. Opaque, so the compiler keeps them
  * apart.
  */
private[corvi] opaque type WindowId = Int

private[corvi] object WindowId:
  def apply(value: Int): WindowId = value

  extension (id: WindowId)
    def value: Int = id
    def next: WindowId = id + 1

/** One terminal window as the strip shows it: what it is called, what its shell is doing, and whether output has arrived that you have not
  * looked at. Immutable; `ShellState` holds these and `WindowTabs` draws them.
  */
private[corvi] final case class TerminalWindow(
    id: WindowId,
    label: String,
    state: TerminalWindow.State,
    activity: TerminalWindow.Activity
)

private[corvi] object TerminalWindow:
  /** What the window's icon reports. Green means "an agent is working" and this spike has no agents — the plan puts agent-status colours
    * out of scope — so a shell is `Idle` while it runs and `Ended` once it has stopped.
    */
  enum State:
    case Idle, Ended

  /** Output that arrived in a window you were not looking at: what the `--warn` bell shows. */
  enum Activity:
    case Quiet, Unseen
