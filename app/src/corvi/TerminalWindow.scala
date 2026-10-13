package corvi

/** One terminal window as the strip shows it: what it is called, what its shell is doing, and whether output has arrived that you have not
  * looked at. Immutable; `ShellState` holds these and `WindowTabs` draws them.
  */
private[corvi] final case class TerminalWindow(
    id: Int,
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
