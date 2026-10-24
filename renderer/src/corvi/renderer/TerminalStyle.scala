package corvi.renderer

import corvi.contract.*
import javafx.scene.paint.Color

/** The renderer's own colours: the session's theme decides the default foreground and background, and the selection, the cursor and the
  * new-output mark are chrome, so they live here rather than in the contract.
  */
final case class TerminalStyle(
    selectionFill: Color,
    cursor: Color,
    newOutputFill: Color,
    newOutputText: Color
):

  /** The colour a cell's text is drawn in: its own, or the session's default; `inverse` swaps the two, defaults included.
    *
    * Bold with no colour of its own is brightened. A real terminal leaves that to the palette's bold column; the contract carries resolved
    * RGB, so the renderer brightens the default instead of inventing a palette it cannot see.
    */
  def foreground(cell: Cell, theme: TerminalTheme): Color = TerminalStyle.toColor(foregroundRgb(cell, theme))

  def background(cell: Cell, theme: TerminalTheme): Color = TerminalStyle.toColor(backgroundRgb(cell, theme))

  def foregroundRgb(cell: Cell, theme: TerminalTheme): Rgb =
    if cell.inverse then cell.background.getOrElse(theme.background) else ownForeground(cell, theme)

  def backgroundRgb(cell: Cell, theme: TerminalTheme): Rgb =
    if cell.inverse then ownForeground(cell, theme) else cell.background.getOrElse(theme.background)

  private def ownForeground(cell: Cell, theme: TerminalTheme): Rgb =
    cell.foreground.getOrElse(if cell.bold then TerminalStyle.brighten(theme.foreground) else theme.foreground)

/** JavaFX's `Color` compares by value, and the painter asks whether a cell's background is the theme's; the given says so for a code base
  * that compiles with `-language:strictEquality`.
  */
given colorCanEqual: CanEqual[Color, Color] = CanEqual.derived

object TerminalStyle:

  val default: TerminalStyle = TerminalStyle(
    // Corvi's `--accent` #316dca at 35%, so a selection reads as the same accent the rest of the app uses.
    selectionFill = Color.web("#316dca", 0.35),
    cursor = Color.rgb(230, 237, 243, 0.85),
    newOutputFill = Color.rgb(35, 134, 54, 0.95),
    newOutputText = Color.rgb(240, 246, 252)
  )

  def toColor(rgb: Rgb): Color = Color.rgb(rgb.red, rgb.green, rgb.blue)

  /** A third of the way to white, which is what a bright variant of a dark terminal colour looks like. */
  def brighten(rgb: Rgb): Rgb =
    def towards(value: Int): Int = math.min(255, value + (255 - value) / 3)
    Rgb(towards(rgb.red), towards(rgb.green), towards(rgb.blue))
