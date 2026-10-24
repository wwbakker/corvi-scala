package corvi.host

import corvi.contract.*
import java.awt.event.KeyEvent as AwtKeyEvent
import java.nio.charset.StandardCharsets

/** Encodes a semantic key in the session's current key modes.
  *
  * The engine owns the mode-dependent part: `JediTerminal.getCodeForKey` knows whether DECCKM is on (the emulator calls
  * `setApplicationArrowKeys`), emits xterm's `CSI 1;N` modifier suffixes for cursor and function keys, and has Shift+Tab's back-tab
  * sequence. This wrapper maps the contract's keys to the AWT virtual-key codes it reads and supplies what the engine cannot:
  *
  *   - Escape and plain Tab have no entry in the engine's table.
  *   - The engine treats Alt and Meta on cursor and function keys as an escape prefix (`alwaysSendEsc`), so it cannot produce xterm's
  *     modified arrow. For those keys this wrapper asks for the base sequence and inserts the full modifier suffix itself.
  *   - Alt on the keys that have no modifier suffix is a Meta prefix, which this wrapper adds once for Backspace, Enter, Tab and Escape.
  */
private[host] object KeyEncoder:

  /** The AWT modifier bits `TerminalKeyEncoder` reads. They are the low-bit `*_MASK` values, not the `*_DOWN_MASK` values Java now prefers:
    * the engine tests for 1, 2, 4 and 8.
    */
  private val ShiftModifier: Int = 1
  private val ControlModifier: Int = 2
  private val MetaModifier: Int = 4

  private val Escape: Array[Byte] = Array[Byte](0x1b)
  private val Tab: Array[Byte] = Array[Byte]('\t'.toByte)
  private val BackTab: Array[Byte] = "\u001b[Z".getBytes(StandardCharsets.US_ASCII)

  /** `codeFor` is `JediTerminal.getCodeForKey`, supplied separately so the mapping can be checked without a PTY. */
  def encode(input: KeyInput, codeFor: (Int, Int) => Array[Byte]): Option[Array[Byte]] =
    input.key match
      case TerminalKey.Printable(text) =>
        // The host can express printable text, an Alt prefix and whatever Shift the text already carries. Control and Meta have no
        // meaning here: Meta+letter is not a plain character, and silently dropping the modifier would type the letter instead.
        if input.modifiers.control || input.modifiers.meta then None
        else
          val bytes = text.getBytes(StandardCharsets.UTF_8)
          Some(if input.modifiers.alt then Escape ++ bytes else bytes)
      case key =>
        keyCode(key).flatMap { code =>
          val bytes =
            if modifierSuffix(key) then
              // Never ask the engine for a modified suffix: `TerminalKeyEncoder.getCodeWithModifiers` inserts into the map's array in
              // place, so one modified F-key would corrupt that key's plain sequence for the rest of the session. The base sequence is
              // read once and the suffix is inserted into a copy.
              Option(codeFor(code, 0)).map(base => addModifier(base, modifierCode(input.modifiers)))
            else Option(codeFor(code, engineModifiers(input.modifiers))).orElse(fallback(key, input.modifiers))
          bytes.map(encoded => metaPrefix(key, input.modifiers) ++ encoded)
        }

  /** Alt is Meta for the keys the engine does not encode with a `CSI 1;N` suffix. Cursor and function keys already carry Alt in that
    * suffix.
    */
  private def metaPrefix(key: TerminalKey, modifiers: KeyModifiers): Array[Byte] =
    if modifiers.alt && !modifierSuffix(key) then Escape else Array.emptyByteArray

  /** The modifiers handed to the engine for keys that have no modifier suffix. Alt is left out because this wrapper adds its prefix; the
    * engine would prefix them too. Suffix keys are always asked for their base sequence and get the suffix inserted by this wrapper.
    */
  private def engineModifiers(modifiers: KeyModifiers): Int =
    (if modifiers.shift then ShiftModifier else 0) |
      (if modifiers.control then ControlModifier else 0) |
      (if modifiers.meta then MetaModifier else 0)

  private def modifierCode(modifiers: KeyModifiers): Int =
    1 +
      (if modifiers.shift then 1 else 0) +
      (if modifiers.alt then 2 else 0) +
      (if modifiers.control then 4 else 0) +
      (if modifiers.meta then 8 else 0)

  /** The engine's own insertion rule: `ESC[1;N` for a three-byte cursor key, `ESC[15;N~` for the longer function keys, and an application
    * cursor key's `ESC O` becomes `ESC [` first.
    */
  private def addModifier(sequence: Array[Byte], code: Int): Array[Byte] =
    if code <= 1 then sequence
    else
      val rewritten =
        if sequence.length > 1 && sequence(0) == 0x1b.toByte && sequence(1) == 'O'.toByte then
          val copy = sequence.clone()
          copy(1) = '['.toByte
          copy
        else sequence
      val insertion = (if rewritten.length == 3 then "1;" else ";") + code
      val inserted = insertion.getBytes(StandardCharsets.US_ASCII)
      rewritten.take(rewritten.length - 1) ++ inserted ++ rewritten.takeRight(1)

  /** Keys the engine can encode with a modifier suffix. */
  private def modifierSuffix(key: TerminalKey): Boolean =
    key match
      case TerminalKey.Up | TerminalKey.Down | TerminalKey.Left | TerminalKey.Right | TerminalKey.Home | TerminalKey.End |
          TerminalKey.PageUp | TerminalKey.PageDown | TerminalKey.Insert | TerminalKey.Delete =>
        true
      case TerminalKey.F1 | TerminalKey.F2 | TerminalKey.F3 | TerminalKey.F4 | TerminalKey.F5 | TerminalKey.F6 | TerminalKey.F7 |
          TerminalKey.F8 | TerminalKey.F9 | TerminalKey.F10 | TerminalKey.F11 | TerminalKey.F12 =>
        true
      case _ => false

  /** The keys the engine's table does not cover. Shift+Tab does have an entry; plain Tab and Escape do not. */
  private def fallback(key: TerminalKey, modifiers: KeyModifiers): Option[Array[Byte]] =
    key match
      case TerminalKey.Tab    => Some(if modifiers.shift then BackTab else Tab)
      case TerminalKey.Escape => Some(Escape)
      case _                  => None

  private def keyCode(key: TerminalKey): Option[Int] =
    key match
      case TerminalKey.Up           => Some(AwtKeyEvent.VK_UP)
      case TerminalKey.Down         => Some(AwtKeyEvent.VK_DOWN)
      case TerminalKey.Left         => Some(AwtKeyEvent.VK_LEFT)
      case TerminalKey.Right        => Some(AwtKeyEvent.VK_RIGHT)
      case TerminalKey.Home         => Some(AwtKeyEvent.VK_HOME)
      case TerminalKey.End          => Some(AwtKeyEvent.VK_END)
      case TerminalKey.PageUp       => Some(AwtKeyEvent.VK_PAGE_UP)
      case TerminalKey.PageDown     => Some(AwtKeyEvent.VK_PAGE_DOWN)
      case TerminalKey.Insert       => Some(AwtKeyEvent.VK_INSERT)
      case TerminalKey.Delete       => Some(AwtKeyEvent.VK_DELETE)
      case TerminalKey.Backspace    => Some(AwtKeyEvent.VK_BACK_SPACE)
      case TerminalKey.Enter        => Some(AwtKeyEvent.VK_ENTER)
      case TerminalKey.Tab          => Some(AwtKeyEvent.VK_TAB)
      case TerminalKey.Escape       => Some(AwtKeyEvent.VK_ESCAPE)
      case TerminalKey.F1           => Some(AwtKeyEvent.VK_F1)
      case TerminalKey.F2           => Some(AwtKeyEvent.VK_F2)
      case TerminalKey.F3           => Some(AwtKeyEvent.VK_F3)
      case TerminalKey.F4           => Some(AwtKeyEvent.VK_F4)
      case TerminalKey.F5           => Some(AwtKeyEvent.VK_F5)
      case TerminalKey.F6           => Some(AwtKeyEvent.VK_F6)
      case TerminalKey.F7           => Some(AwtKeyEvent.VK_F7)
      case TerminalKey.F8           => Some(AwtKeyEvent.VK_F8)
      case TerminalKey.F9           => Some(AwtKeyEvent.VK_F9)
      case TerminalKey.F10          => Some(AwtKeyEvent.VK_F10)
      case TerminalKey.F11          => Some(AwtKeyEvent.VK_F11)
      case TerminalKey.F12          => Some(AwtKeyEvent.VK_F12)
      case TerminalKey.Printable(_) => None
