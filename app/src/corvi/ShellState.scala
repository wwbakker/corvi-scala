package corvi

import corvi.terminal.TerminalSession

/** Which surface is on screen: the change's overview, or one of its terminals. A sum, so "the overview is showing" and "window 3 is
  * showing" cannot both hold, and neither can be missing.
  */
private[corvi] enum Selection:
  case Overview
  case Window(id: Int)

/** The shell's state: the windows it has, the live session behind each of them, and which surface is on screen. Immutable — `CorviShell`
  * holds one of these and renders whatever it says, so every change to the screen goes through one of the transitions below.
  */
private[corvi] final case class ShellState(
    windows: Vector[TerminalWindow],
    selection: Selection,
    sessions: Map[Int, TerminalSession],
    nextId: Int
):
  def window(id: Int): Option[TerminalWindow] = windows.find(_.id == id)

  def session(id: Int): Option[TerminalSession] = sessions.get(id)

  def isSelected(id: Int): Boolean = selection == Selection.Window(id)

  /** The session on screen, which is the one whose shell the keyboard belongs in. */
  def selectedSession: Option[TerminalSession] = selection match
    case Selection.Window(id) => session(id)
    case Selection.Overview   => None

  /** Opening a window: its session is already running, and it becomes the one on screen. */
  def opened(label: String, session: TerminalSession): ShellState =
    val window = TerminalWindow(nextId, label, TerminalWindow.State.Idle, TerminalWindow.Activity.Quiet)
    copy(
      windows = windows :+ window,
      sessions = sessions + (window.id -> session),
      selection = Selection.Window(window.id),
      nextId = nextId + 1
    )

  /** Selecting a window — or the overview — clears its bell: you are looking at it now. */
  def selected(selection: Selection): ShellState =
    selection match
      case Selection.Overview   => if this.selection == Selection.Overview then this else copy(selection = selection)
      case Selection.Window(id) => selectedWindow(id)

  /** A window that is not there is nothing to select; one that is already on screen with nothing to clear is already what was asked for.
    * Both come back as this very value, which is also what stops the strip re-rendering for nothing.
    */
  private def selectedWindow(id: Int): ShellState =
    window(id) match
      case None                                                                               => this
      case Some(target) if isSelected(id) && target.activity == TerminalWindow.Activity.Quiet => this
      case Some(_)                                                                            =>
        copy(
          windows = windows.map(window => if window.id == id then window.copy(activity = TerminalWindow.Activity.Quiet) else window),
          selection = Selection.Window(id)
        )

  /** Closing a window: the ones after it keep their order, and the selection moves to the window that takes its place, or back to the
    * overview once none are left.
    */
  def closed(id: Int): ShellState =
    val index = windows.indexWhere(_.id == id)
    if index < 0 then this
    else
      val remaining = windows.patch(index, Nil, 1)
      copy(windows = remaining, sessions = sessions - id, selection = selectionAfterClosing(id, index, remaining))

  /** Output arrived: it raises the bell only in a window you are not looking at, and a window whose shell has ended does not ask for
    * attention again.
    */
  def activity(id: Int): ShellState =
    if isSelected(id) then this
    else
      update(id) { window =>
        if window.state == TerminalWindow.State.Ended then window else window.copy(activity = TerminalWindow.Activity.Unseen)
      }

  /** The shell exited: the window says so, and stops asking for attention. */
  def ended(id: Int): ShellState =
    update(id)(window => window.copy(state = TerminalWindow.State.Ended, activity = TerminalWindow.Activity.Quiet))

  private def selectionAfterClosing(id: Int, index: Int, remaining: Vector[TerminalWindow]): Selection =
    if selection != Selection.Window(id) then selection
    else
      remaining
        .lift(index)
        .orElse(remaining.lastOption)
        .map(window => Selection.Window(window.id))
        .getOrElse(Selection.Overview)

  private def update(id: Int)(change: TerminalWindow => TerminalWindow): ShellState =
    window(id) match
      case None         => this
      case Some(target) =>
        val changed = change(target)
        // A change that changes nothing comes back as this very value: every read of a quiet terminal
        // would otherwise rebuild the strip.
        if changed == target then this
        else copy(windows = windows.map(window => if window.id == id then changed else window))

private[corvi] object ShellState:
  val initial: ShellState = ShellState(Vector.empty, Selection.Overview, Map.empty, nextId = 0)
