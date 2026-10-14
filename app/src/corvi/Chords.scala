package corvi

import javafx.scene.input.{KeyCode, KeyEvent}

/** Corvi's keyboard chords as a small state machine: the scene's key filters feed it presses and typed keys, and it answers what to do and
  * whether the terminal may see the key. Pure, apart from the clock it is handed.
  *
  * Two things are remembered: the tmux-style prefix (`ctrl-b`), which expires by timestamp rather than by a timer — a key that arrives
  * after the deadline is simply not prefixed — and whether the typed companion of a key the strip took still has to be swallowed.
  */
private[corvi] object Chords:
  /** What a press asks the shell to do. A `Passed` key belongs to the terminal. */
  enum Outcome:
    case PrefixArmed
    case Open
    case CloseSelected
    case Select(index: Int)
    case Passed

  /** How long the prefix waits for the key that follows it. */
  private[corvi] val prefixNanos: Long = 1_000_000_000L

  final case class State(prefixUntil: Option[Long], swallowTyped: Boolean):
    def waiting(now: Long): Boolean = prefixUntil.exists(until => now < until)

  val initial: State = State(None, swallowTyped = false)

  /** Whether this is macOS, which is what decides the open and close chords. */
  def isMacPlatform: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")

  /** The chords upstream's cheat sheet gives: `⌘T` opens and `⌘W` closes on macOS, `ctrl-alt-t` opens elsewhere (the cheat sheet has no
    * close chord off macOS — the tab's Close tab is the way), and `ctrl-b` is the prefix wherever you are. Shift is not part of any of
    * them, as upstream's key test has it. Upstream leaves `ctrl-w` alone because the shell wants it.
    */
  def press(state: State, event: KeyEvent, mac: Boolean, now: Long): (State, Outcome) =
    val outcome = outcomeOf(state, event, mac, now)
    // A key the strip takes has its typed companion swallowed; a key it passes leaves that companion to
    // the terminal, so the flag goes with it.
    val next = outcome match
      case Outcome.PrefixArmed => State(Some(now + prefixNanos), swallowTyped = true)
      case Outcome.Passed      => State(None, swallowTyped = false)
      case _                   => State(None, swallowTyped = true)
    (next, outcome)

  /** A `KEY_TYPED`, which the platform sends for the same keystroke even when the press was consumed. The companion of a key the strip took
    * is swallowed; anything else belongs to the terminal.
    */
  def typed(state: State): (State, Boolean) =
    if state.swallowTyped then (State(state.prefixUntil, swallowTyped = false), true)
    else (state, false)

  private def outcomeOf(state: State, event: KeyEvent, mac: Boolean, now: Long): Outcome =
    val code = event.getCode
    val meta = event.isMetaDown
    val control = event.isControlDown
    val alt = event.isAltDown
    val shift = event.isShiftDown
    val plain = !meta && !control && !alt && !shift

    if mac && meta && !control && !alt && !shift && code == KeyCode.T then Outcome.Open
    else if mac && meta && !control && !alt && !shift && code == KeyCode.W then Outcome.CloseSelected
    else if !mac && control && alt && !meta && !shift && code == KeyCode.T then Outcome.Open
    else if control && !meta && !alt && !shift && code == KeyCode.B then Outcome.PrefixArmed
    else if state.waiting(now) && plain then prefixed(code)
    else Outcome.Passed

  /** The key after the prefix: a digit switches to that window, `c` opens one, anything else is the terminal's (and takes the prefix with
    * it).
    */
  private def prefixed(code: KeyCode): Outcome =
    digit(code) match
      case Some(index) => Outcome.Select(index)
      case None        => if code == KeyCode.C then Outcome.Open else Outcome.Passed

  /** The position a digit names, counted from zero as upstream's tabs and tmux count windows. The numpad is not one of them.
    */
  private def digit(code: KeyCode): Option[Int] =
    if !code.isDigitKey then None
    else code.getName.toIntOption.filter(index => index >= 0 && index <= 8)
