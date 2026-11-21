package corvi.renderer

import corvi.contract.TerminalKey
import javafx.scene.input.KeyCode

/** JavaFX's key codes, translated to the contract's semantic keys. This is the renderer's one table; what the sequence means is the host's
  * decision.
  *
  * The letter and punctuation mapping exists only for Alt-as-Meta/Escape, where the pressed key is the key and the platform's composed
  * `KEY_TYPED` is not the text to send. The table follows the US layout for shifted digits and punctuation: on another layout those covered
  * keys still use the US character. A key outside the table falls back to the platform's typed character in `TerminalView`.
  */
private[corvi] object KeyMapping:

  def terminalKey(code: KeyCode): Option[TerminalKey] =
    code match
      case KeyCode.UP         => Some(TerminalKey.Up)
      case KeyCode.DOWN       => Some(TerminalKey.Down)
      case KeyCode.LEFT       => Some(TerminalKey.Left)
      case KeyCode.RIGHT      => Some(TerminalKey.Right)
      case KeyCode.HOME       => Some(TerminalKey.Home)
      case KeyCode.END        => Some(TerminalKey.End)
      case KeyCode.PAGE_UP    => Some(TerminalKey.PageUp)
      case KeyCode.PAGE_DOWN  => Some(TerminalKey.PageDown)
      case KeyCode.INSERT     => Some(TerminalKey.Insert)
      case KeyCode.DELETE     => Some(TerminalKey.Delete)
      case KeyCode.BACK_SPACE => Some(TerminalKey.Backspace)
      case KeyCode.ENTER      => Some(TerminalKey.Enter)
      case KeyCode.TAB        => Some(TerminalKey.Tab)
      case KeyCode.ESCAPE     => Some(TerminalKey.Escape)
      case KeyCode.F1         => Some(TerminalKey.F1)
      case KeyCode.F2         => Some(TerminalKey.F2)
      case KeyCode.F3         => Some(TerminalKey.F3)
      case KeyCode.F4         => Some(TerminalKey.F4)
      case KeyCode.F5         => Some(TerminalKey.F5)
      case KeyCode.F6         => Some(TerminalKey.F6)
      case KeyCode.F7         => Some(TerminalKey.F7)
      case KeyCode.F8         => Some(TerminalKey.F8)
      case KeyCode.F9         => Some(TerminalKey.F9)
      case KeyCode.F10        => Some(TerminalKey.F10)
      case KeyCode.F11        => Some(TerminalKey.F11)
      case KeyCode.F12        => Some(TerminalKey.F12)
      case _                  => None

  /** The editing keys that keep their historical direct path when no modifier is held. */
  def unmodifiedEditingKey(code: KeyCode): Boolean =
    code == KeyCode.BACK_SPACE || code == KeyCode.ENTER || code == KeyCode.TAB || code == KeyCode.ESCAPE

  /** The character a printable key stands for, ignoring a composing layout. */
  def printable(code: KeyCode, shift: Boolean): Option[String] =
    code match
      case KeyCode.SPACE         => Some(" ")
      case _ if code.isLetterKey =>
        val base = code.getChar
        Some(if shift then base.toUpperCase else base.toLowerCase)
      case _ if code.isDigitKey =>
        val digit = code.getChar.charAt(0)
        if shift then Some(")!@#$%^&*(".charAt(digit - '0').toString) else Some(digit.toString)
      case KeyCode.MINUS         => Some(if shift then "_" else "-")
      case KeyCode.EQUALS        => Some(if shift then "+" else "=")
      case KeyCode.OPEN_BRACKET  => Some(if shift then "{" else "[")
      case KeyCode.CLOSE_BRACKET => Some(if shift then "}" else "]")
      case KeyCode.BACK_SLASH    => Some(if shift then "|" else "\\")
      case KeyCode.SEMICOLON     => Some(if shift then ":" else ";")
      case KeyCode.QUOTE         => Some(if shift then "\"" else "'")
      case KeyCode.COMMA         => Some(if shift then "<" else ",")
      case KeyCode.PERIOD        => Some(if shift then ">" else ".")
      case KeyCode.SLASH         => Some(if shift then "?" else "/")
      case KeyCode.BACK_QUOTE    => Some(if shift then "~" else "`")
      case _                     => None
