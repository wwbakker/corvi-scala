package corvi.ui

/** Corvi's keyboard chords as a small state machine: the scene's key filters translate a key event into a `Key` and feed it presses and
  * typed keys, and the machine answers what to do and whether the terminal may see the key. Pure, apart from the clock it is handed, and
  * free of JavaFX types — `Key.from` is the one thin adapter that reads a `KeyEvent`.
  *
  * Two things are remembered: the tmux-style prefix (`ctrl-b`), which expires by timestamp rather than by a timer — a key that arrives
  * after the deadline is simply not prefixed — and whether the typed companion of a key the strip took still has to be swallowed.
  */
private[corvi] object Chords:
  /** What a press asks the shell to do. A `Passed` key belongs to the terminal and its typed companion does too; a `PassedShortcut` is also
    * the terminal's key (the view turns Ctrl+letter into a control byte), but the platform will still send a `KEY_TYPED` for it, which
    * would type a plain letter into the shell — so that companion is swallowed.
    */
  enum Outcome derives CanEqual:
    case PrefixArmed
    case Open
    case CloseSelected
    case Select(index: Int)
    case Passed
    case PassedShortcut

  /** How long the prefix waits for the key that follows it. */
  private[corvi] val prefixNanos: Long = 1_000_000_000L

  /** How long a taken key's typed companion may take to arrive. The platform sends it immediately after the press, but a Command chord on
    * macOS sends none at all, and the flag must not survive to eat the next character from IME or a dead key. Short, and cleared by the
    * first typed event either way.
    */
  private[corvi] val swallowNanos: Long = 100_000_000L

  final case class State(prefixUntil: Option[Long], swallowUntil: Option[Long]):
    def waiting(now: Long): Boolean = prefixUntil.exists(until => now < until)
    def swallowing(now: Long): Boolean = swallowUntil.exists(until => now < until)

  val initial: State = State(None, None)

  /** Whether this is macOS, which is what decides the open and close chords. */
  def isMacPlatform: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")

  /** The chords upstream's cheat sheet gives: `⌘T` opens and `⌘W` closes on macOS, `ctrl-alt-t` opens elsewhere (the cheat sheet has no
    * close chord off macOS — the tab's Close tab is the way), and `ctrl-b` is the prefix wherever you are. Shift is not part of any of
    * them, as upstream's key test has it. Upstream leaves `ctrl-w` alone because the shell wants it.
    */
  def press(state: State, key: Key, mac: Boolean, now: Long): (State, Outcome) =
    val outcome = outcomeOf(state, key, mac, now)
    // A key the strip takes has its typed companion swallowed; a key it passes leaves that companion to the
    // terminal, so the flag goes with it.
    val next = outcome match
      case Outcome.PrefixArmed    => State(Some(now + prefixNanos), Some(now + swallowNanos))
      case Outcome.Passed         => State(None, None)
      case Outcome.PassedShortcut => State(None, Some(now + swallowNanos))
      case _                      => State(None, Some(now + swallowNanos))
    (next, outcome)

  /** A `KEY_TYPED`, which the platform sends for the same keystroke even when the press was consumed. The companion of a key the strip took
    * is swallowed, but only within `swallowNanos` of that press: the flag is one-shot, and a Command chord sends no companion at all, so
    * without a deadline it would eat a later character. Anything else belongs to the terminal.
    */
  def typed(state: State, now: Long): (State, Boolean) =
    val swallowed = state.swallowing(now)
    (State(state.prefixUntil, None), swallowed)

  private def outcomeOf(state: State, key: Key, mac: Boolean, now: Long): Outcome =
    key match
      case Key.Open                               => Outcome.Open
      case Key.Close if mac                       => Outcome.CloseSelected
      case Key.Prefix                             => Outcome.PrefixArmed
      case Key.Create if state.waiting(now)       => Outcome.Open
      case Key.Digit(index) if state.waiting(now) => Outcome.Select(index)
      case Key.Shortcut                           => Outcome.PassedShortcut
      case _                                      => Outcome.Passed

  /** A keystroke, as the chord machine sees it: the key, with its modifiers already accounted for. `Shortcut` is any key with Command or
    * Control down that is not one of the app's chords: it belongs to the terminal (which sends a control byte for Control+letter), but its
    * typed companion is not a character. Alt is not a shortcut modifier: Option-composed characters are input.
    */
  enum Key derives CanEqual:
    case Open, Close, Prefix, Create, Shortcut, Other
    case Digit(index: Int)

  object Key:
    /** What the platform's key event means for the chords — the one adapter that knows about JavaFX, and the only place a `KeyEvent` is
      * read.
      */
    def from(event: javafx.scene.input.KeyEvent, mac: Boolean): Key =
      import javafx.scene.input.KeyCode
      val code = event.getCode
      val meta = event.isMetaDown
      val control = event.isControlDown
      val alt = event.isAltDown
      val shift = event.isShiftDown
      val plain = !meta && !control && !alt && !shift

      if mac && meta && !control && !alt && !shift && code == KeyCode.T then Key.Open
      else if mac && meta && !control && !alt && !shift && code == KeyCode.W then Key.Close
      else if !mac && control && alt && !meta && !shift && code == KeyCode.T then Key.Open
      else if control && !meta && !alt && !shift && code == KeyCode.B then Key.Prefix
      else if plain && code == KeyCode.C then Key.Create
      else if plain then digit(code).map(Key.Digit.apply).getOrElse(Key.Other)
      // Option produces a character on macOS (Option+E is a dead key for é), so Alt is not a shortcut here: its KEY_TYPED is the text.
      else if meta || control then Key.Shortcut
      else Key.Other

    /** The position a digit names, counted from zero as upstream's tabs and tmux count windows. The numpad is not one of them.
      */
    private def digit(code: javafx.scene.input.KeyCode): Option[Int] =
      if !code.isDigitKey then None
      else code.getName.toIntOption.filter(index => index >= 0 && index <= 8)
