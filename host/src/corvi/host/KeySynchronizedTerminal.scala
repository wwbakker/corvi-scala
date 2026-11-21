package corvi.host

import com.techsenger.jeditermfx.core.{HyperlinkStyle, TerminalDisplay, TextStyle}
import com.techsenger.jeditermfx.core.model.{JediTerminal, StyleState, TerminalTextBuffer}
import corvi.contract.LinkPolicy

/** A `JediTerminal` whose key encoder is read under the same monitor as the mode changes that mutate it.
  *
  * The emulator changes DECCKM, the keypad mode, auto-newline and Alt-sends-escape from its reader fiber, and those calls rewrite the
  * encoder's map. A client's key request arrives on another fiber and calls `codeForKey`; without one monitor the map could be read while
  * it is being replaced. The buffer lock is not that monitor: mode handling is not a terminal-buffer write.
  */
private[host] final class KeySynchronizedTerminal(display: TerminalDisplay, buffer: TerminalTextBuffer, styleState: StyleState)
    extends JediTerminal(display, buffer, styleState):

  /** The URI of the OSC 8 link currently open, if any. SGR changes can replace the style, so the terminal re-applies it. */
  private var activeLink: Option[UriLinkInfo] = None

  /** The explicit target for a cell's hyperlink style. The URI lives in the style's LinkInfo, so a style derived by the engine keeps it. */
  def uriFor(style: TextStyle): Option[String] =
    style match
      case hyperlink: HyperlinkStyle =>
        hyperlink.getLinkInfo match
          case link: UriLinkInfo => Some(link.uri)
          case _                 => None
      case _ => None

  /** Records the OSC 8 URI on the style the engine uses for the link. This bypasses the engine's TextProcessing filter (which would also
    * auto-link ordinary line text) and keeps unsafe schemes as ordinary styled text, with no target. `setLinkUriFinished` restores the
    * previous style through the engine's default implementation.
    */
  override def setLinkUriStarted(uri: String): Unit =
    if LinkPolicy.allows(uri) then
      val info = new UriLinkInfo(uri)
      activeLink = Some(info)
      getStyleState.setCurrent(new HyperlinkStyle(getStyleState.getCurrent, info))

  /** SGR changes can construct a new style without the link (notably `ESC[0m`); carry the open link's LinkInfo onto it. */
  override def characterAttributes(style: TextStyle): Unit =
    super.characterAttributes(style)
    activeLink.foreach { info =>
      getStyleState.getCurrent match
        case hyperlink: HyperlinkStyle if hyperlink.getLinkInfo eq info => ()
        case current                                                    => getStyleState.setCurrent(new HyperlinkStyle(current, info))
    }

  /** The engine restores the style that preceded the link; drop the active URI before that happens. */
  override def setLinkUriFinished(): Unit =
    activeLink = None
    super.setLinkUriFinished()

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
