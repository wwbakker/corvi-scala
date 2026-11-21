package corvi.contract

/** The non-text keys a terminal can send. Printable text stays on `session.text`; this is the semantic key a client sends when the host has
  * to encode it in the session's current key modes.
  *
  * `Printable` exists for Alt-as-Meta/Escape: when Alt is not composing a character, the key is still text plus a modifier, and the host
  * prepends the Meta prefix. Keeping it here rather than sending an escape string from the renderer preserves the rule that the host owns
  * terminal encoding.
  *
  * Keypad keys are absent for now. The engine encodes only its keypad cursor constants (`VK_KP_UP`/`DOWN`/`LEFT`/`RIGHT`), and they are the
  * same sequences as the arrow keys; it has no keypad digits or Enter. JavaFX does expose `KP_*` and `NUMPAD*` codes, so a keypad contract
  * would be a separate, partial mapping rather than a free addition to this enum.
  */
enum TerminalKey derives CanEqual:
  case Up, Down, Left, Right
  case Home, End, PageUp, PageDown, Insert, Delete
  case Backspace, Enter, Tab, Escape
  case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
  case Printable(text: String)

/** The modifiers a key carries. Shift and Control are the terminal's own; Alt is Meta/Escape when the application's Alt mode says so, and
  * Meta is the platform key (Command on macOS, Super elsewhere).
  */
final case class KeyModifiers(shift: Boolean, alt: Boolean, control: Boolean, meta: Boolean) derives CanEqual

object KeyModifiers:
  val none: KeyModifiers = KeyModifiers(shift = false, alt = false, control = false, meta = false)

/** One key event, in the contract's vocabulary: what was pressed and which modifiers were held. The host decides how that becomes bytes.
  */
final case class KeyInput(key: TerminalKey, modifiers: KeyModifiers) derives CanEqual
