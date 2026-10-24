package corvi.host

import com.techsenger.jeditermfx.core.TerminalDisplay
import com.techsenger.jeditermfx.core.model.{JediTerminal, StyleState, TerminalTextBuffer}

/** A `JediTerminal` whose key encoder is read under the same monitor as the mode changes that mutate it.
  *
  * The emulator changes DECCKM, the keypad mode, auto-newline and Alt-sends-escape from its reader fiber, and those calls rewrite the
  * encoder's map. A client's key request arrives on another fiber and calls `codeForKey`; without one monitor the map could be read while
  * it is being replaced. The buffer lock is not that monitor: mode handling is not a terminal-buffer write.
  */
private[host] final class KeySynchronizedTerminal(display: TerminalDisplay, buffer: TerminalTextBuffer, styleState: StyleState)
    extends JediTerminal(display, buffer, styleState):

  /** The encoder read and every mode setter below use this instance as the monitor. It is `this`, not a field, because the engine's
    * constructor calls `setAutoNewLine` before a subclass field would be initialised.
    */
  def codeForKey(keyCode: Int, modifiers: Int): Array[Byte] =
    synchronized(getCodeForKey(keyCode, modifiers))

  override def setApplicationArrowKeys(enabled: Boolean): Unit =
    synchronized(super.setApplicationArrowKeys(enabled))

  override def setApplicationKeypad(enabled: Boolean): Unit =
    synchronized(super.setApplicationKeypad(enabled))

  override def setAutoNewLine(enabled: Boolean): Unit =
    synchronized(super.setAutoNewLine(enabled))

  override def setAltSendsEscape(enabled: Boolean): Unit =
    synchronized(super.setAltSendsEscape(enabled))
