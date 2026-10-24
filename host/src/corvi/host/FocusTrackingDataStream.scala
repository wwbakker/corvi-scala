package corvi.host

import com.techsenger.jeditermfx.core.TerminalDataStream

/** Watches the terminal's input for DECSET 1004, which JediTermFX's emulator accepts but does not record.
  *
  * Focus tracking is independent of mouse tracking: a program can enable both, and Pi does. The engine's `MouseMode.Focus` exists but the
  * emulator's 1004 case is a no-op, so the host needs its own small detector. This wrapper delegates the stream and only keeps enough state
  * to recognise a private CSI mode list ending in `h` or `l`; it does not consume or rewrite anything.
  */
private[host] final class FocusTrackingDataStream(inner: TerminalDataStream, onFocusReporting: Boolean => Unit) extends TerminalDataStream:
  import FocusTrackingDataStream.*

  private var state: State = State.Ground
  private val params = new StringBuilder

  def getChar(): Char =
    val character = inner.getChar()
    observe(character)
    character

  def pushChar(character: Char): Unit = inner.pushChar(character)

  def readNonControlCharacters(maxChars: Int): String = inner.readNonControlCharacters(maxChars)

  def pushBackBuffer(characters: Array[Char], length: Int): Unit = inner.pushBackBuffer(characters, length)

  def isEmpty(): Boolean = inner.isEmpty

  private def observe(character: Char): Unit =
    state match
      case State.Ground =>
        if character == Escape then state = State.AfterEscape
      case State.AfterEscape =>
        state =
          if character == '[' then State.AfterCsi
          else if character == Escape then State.AfterEscape
          else State.Ground
      case State.AfterCsi =>
        if character == '?' then
          params.clear()
          state = State.PrivateParams
        else state = if character == Escape then State.AfterEscape else State.Ground
      case State.PrivateParams =>
        if character.isDigit || character == ';' then params.append(character)
        else if character == 'h' || character == 'l' then
          val enabled = character == 'h'
          if params.toString.split(';').exists(_ == "1004") then onFocusReporting(enabled)
          state = State.Ground
        else state = if character == Escape then State.AfterEscape else State.Ground

private[host] object FocusTrackingDataStream:
  private val Escape: Char = 0x1b.toChar

  private enum State:
    case Ground, AfterEscape, AfterCsi, PrivateParams
