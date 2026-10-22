package corvi.renderer

import javafx.scene.input.KeyCode

/** The C0 control bytes a terminal sends when Control is held, as a pure mapping so the key path can be tested without a keyboard.
  *
  * A terminal translates Control and a letter to the letter's position in the alphabet (Ctrl+A is 0x01, Ctrl+Z is 0x1a); the punctuation
  * that follows Z continues the same sequence, which is why Ctrl+[ is Escape's byte and Ctrl+? is Delete's. Everything here is what the
  * shell's line editor expects to receive as input — the terminal does not interpret it, it only encodes it.
  */
private[corvi] object ControlKeys:

  /** The byte to send for a Control chord, or `None` when Control does not make a control character out of this key.
    *
    * Shift matters for one key: Ctrl+/ is 0x1f, and Ctrl+? — Shift+/ on most layouts — is Delete's 0x7f. Elsewhere Shift is not part of the
    * chord: Ctrl+Shift+C is still ^C on the wire, which is what the copy alias relies on (copy is decided before this is consulted).
    */
  def controlByte(code: KeyCode, shift: Boolean): Option[String] =
    code match
      // Ctrl+Space and Ctrl+@ are NUL (0x00); the space bar is the one a person can reach.
      case KeyCode.SPACE | KeyCode.AT => Some("\u0000")
      case _                          =>
        code match
          case KeyCode.A => Some("\u0001")
          case KeyCode.B => Some("\u0002")
          case KeyCode.C => Some("\u0003")
          case KeyCode.D => Some("\u0004")
          case KeyCode.E => Some("\u0005")
          case KeyCode.F => Some("\u0006")
          case KeyCode.G => Some("\u0007")
          case KeyCode.H => Some("\u0008")
          case KeyCode.I => Some("\t")
          case KeyCode.J => Some("\n")
          case KeyCode.K => Some("\u000b")
          case KeyCode.L => Some("\u000c")
          case KeyCode.M => Some("\r")
          case KeyCode.N => Some("\u000e")
          case KeyCode.O => Some("\u000f")
          case KeyCode.P => Some("\u0010")
          case KeyCode.Q => Some("\u0011")
          case KeyCode.R => Some("\u0012")
          case KeyCode.S => Some("\u0013")
          case KeyCode.T => Some("\u0014")
          case KeyCode.U => Some("\u0015")
          case KeyCode.V => Some("\u0016")
          case KeyCode.W => Some("\u0017")
          case KeyCode.X => Some("\u0018")
          case KeyCode.Y => Some("\u0019")
          case KeyCode.Z => Some("\u001a")
          // The punctuation after Z continues the sequence: ^[, ^\, ^], ^^, ^_ and ^? (Delete).
          case KeyCode.OPEN_BRACKET  => Some("\u001b")
          case KeyCode.BACK_SLASH    => Some("\u001c")
          case KeyCode.CLOSE_BRACKET => Some("\u001d")
          case KeyCode.CIRCUMFLEX    => Some("\u001e")
          case KeyCode.UNDERSCORE    => Some("\u001f")
          case KeyCode.SLASH         => Some(if shift then "\u007f" else "\u001f")
          case _                     => None

  /** Whether this key is the copy chord: Command+C on macOS (the platform's own copy), and Control+Shift+C as the alias for the platforms
    * and remappings where the window does not own Control+C — a terminal keeps Ctrl+C for SIGINT.
    */
  def isCopy(code: KeyCode, meta: Boolean, control: Boolean, shift: Boolean, alt: Boolean): Boolean =
    code == KeyCode.C && !alt && ((meta && !control) || (control && shift && !meta))

  /** Whether this key is the paste chord, with the same two spellings as copy. */
  def isPaste(code: KeyCode, meta: Boolean, control: Boolean, shift: Boolean, alt: Boolean): Boolean =
    code == KeyCode.V && !alt && ((meta && !control) || (control && shift && !meta))
