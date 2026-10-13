package corvi.terminal

import com.techsenger.jeditermfx.core.{TerminalColor, TextStyle}
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider
import corvi.Theme
import javafx.scene.text.Font

/** Corvi's dress for the terminal: the well behind it, the xterm theme's foreground, Menlo at 13.
  *
  * The palette lives in `corvi.css`, but the terminal is a canvas rather than a styled node, so the two colours it paints itself with are
  * written down here as well.
  */
private[corvi] final class CorviSettingsProvider extends DefaultSettingsProvider:
  override def getDefaultBackground: TerminalColor = CorviSettingsProvider.well

  override def getDefaultForeground: TerminalColor = CorviSettingsProvider.foreground

  override def getSelectionColor: TextStyle =
    new TextStyle(TerminalColor.WHITE, CorviSettingsProvider.selection)

  /** The widget builds its `StyleState` from this — the deprecated hook, not the two above — so it has to say the same thing: left at
    * JediTerm's black-on-white default, the block cursor is drawn as a black rectangle on the well.
    */
  override def getDefaultStyle: TextStyle =
    new TextStyle(CorviSettingsProvider.foreground, CorviSettingsProvider.well)

  override def getTerminalFontSize: Float = Theme.terminalFontSize

  /** Menlo is macOS's monospace default, as upstream's terminal font stack names it. JavaFX falls back to its own default if the font is
    * missing, so other platforms still get a terminal.
    */
  override def getTerminalFont: Font = Font.font(CorviSettingsProvider.fontFamily, Theme.terminalFontSize)

private[corvi] object CorviSettingsProvider:
  /** `--well`. */
  private val well: TerminalColor = TerminalColor.rgb(13, 17, 23)

  /** The xterm theme's foreground, `#e6edf3`. */
  private val foreground: TerminalColor = TerminalColor.rgb(230, 237, 243)

  /** `--accent`, drawn opaque: a canvas has no wash to mix into. */
  private val selection: TerminalColor = TerminalColor.rgb(49, 109, 202)

  private val fontFamily: String = "Menlo"
