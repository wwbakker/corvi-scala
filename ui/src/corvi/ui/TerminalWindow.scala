package corvi.ui

import corvi.contract.Ended

/** A window's identity, kept distinct from a position: both are `Int`, and confusing them is exactly the mistake the tab strip invites —
  * `ctrl-b <n>` takes a position, a drag takes ids, and `closed` takes an id while `moved` takes two. Opaque, so the compiler keeps them
  * apart.
  */
private[corvi] opaque type WindowId = Int

private[corvi] object WindowId:
  def apply(value: Int): WindowId = value

  given CanEqual[WindowId, WindowId] = CanEqual.derived

  extension (id: WindowId)
    def value: Int = id
    def next: WindowId = id + 1

/** How a window's shell ended, as the strip reports it. A sum rather than three flags, because a shell ends in exactly one of these ways
  * and the tab dresses each of them differently.
  */
private[corvi] enum Ending derives CanEqual:

  /** The shell exited by itself. The code is advisory — a host-initiated close reports whatever the PTY made of the signal — and the host
    * may not have one.
    */
  case Exited(code: Option[Int])

  /** The reader failed: `reason` is what ended the session. */
  case Failed(reason: String)

  /** The client asked for the session to end, which is what closing a tab does. You did this, so the tab carries no extra marker for it.
    */
  case Closed

private[corvi] object Ending:
  /** `closed` wins over a code, because a close's code is the signal the server sent, not the shell's own exit status; a reader failure
    * wins over both, because that is what actually went wrong.
    */
  def from(ended: Ended): Ending =
    if ended.closed then Closed
    else
      ended.reason match
        case Some(reason) => Failed(reason)
        case None         => Exited(ended.code)

/** One terminal window as the strip shows it: what it is called, what its shell is doing, whether output has arrived that you have not
  * looked at, and — once it has stopped — how it ended. Immutable; `ShellState` holds these and the strip draws them.
  */
private[corvi] final case class TerminalWindow(
    id: WindowId,
    label: String,
    state: TerminalWindow.State,
    activity: TerminalWindow.Activity,
    ending: Option[Ending]
) derives CanEqual:

  /** The label the tab shows. A natural exit carries its code here, because it is the one thing worth reading at a glance; a failure and a
    * client close are the tooltip's business.
    */
  def display: String = ending match
    case Some(Ending.Exited(Some(code))) => s"$label (exit $code)"
    case _                               => label

  /** How the shell ended, in words, or `None` for a window that is still running or was closed by the user. The tab's tooltip carries it
    * next to the chord that selects the window.
    */
  def endingDetail: Option[String] = ending.flatMap {
    case Ending.Exited(Some(code)) => Some(s"the shell exited with $code")
    case Ending.Exited(None)       => Some("the shell exited")
    case Ending.Failed(reason)     => Some(s"the session failed: $reason")
    case Ending.Closed             => None
  }

private[corvi] object TerminalWindow:
  /** What the window's icon reports. Green means "an agent is working" and this spike has no agents — the plan puts agent-status colours
    * out of scope — so a shell is `Idle` while it runs and `Ended` once it has stopped.
    */
  enum State derives CanEqual:
    case Idle, Ended

  /** Output that arrived in a window you were not looking at: what the `--warn` bell shows. */
  enum Activity derives CanEqual:
    case Quiet, Unseen
