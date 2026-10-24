package corvi.contract

/** A colour as the host resolved it: the terminal's palette is the host's business, so the wire carries RGB. */
final case class Rgb(red: Int, green: Int, blue: Int) derives CanEqual

/** The colours a session falls back on: a cell that carries no colour of its own is drawn in these. Part of the session's identity rather
  * than of a frame, so the renderer never has to know what the host's default style happens to be.
  */
final case class TerminalTheme(foreground: Rgb, background: Rgb) derives CanEqual

/** How much of a row a cell occupies. A double-width character - a CJK ideograph, an emoji - covers two cells: the first carries the
  * character as `Wide`, the second is `Continuation` and carries no text of its own. The second cell exists so that column indexes stay the
  * terminal's own, which is what the cursor, damage and mouse reporting are expressed in.
  */
enum CellWidth derives CanEqual:
  case Narrow, Wide, Continuation

/** One cell: the text and the attributes the host's emulator resolved for it.
  *
  * `text` is a whole code point rather than a `Char`, because a terminal cell can hold any Unicode scalar value and a `Char` cannot hold
  * one outside the BMP (an emoji is a surrogate pair, and the emulator stores its two halves in two cells). `None` colours are the
  * session's `TerminalTheme`.
  */
final case class Cell(
    text: String,
    width: CellWidth,
    foreground: Option[Rgb],
    background: Option[Rgb],
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    inverse: Boolean
) derives CanEqual

/** One row of the visible grid. */
final case class Row(cells: Vector[Cell]) derives CanEqual

/** The cursor's shape, as DECSCUSR describes it. Whether the shape blinks is `Cursor.blinking`, not a fourth shape: a blinking block and a
  * steady block are the same shape with different blink.
  */
enum CursorShape derives CanEqual:
  case Block, Underline, VerticalBar

/** Where the cursor is, its shape (`None` is the terminal's default), whether that shape blinks, and whether it shows at all. */
final case class Cursor(
    x: Int,
    y: Int,
    shape: Option[CursorShape],
    blinking: Boolean,
    visible: Boolean
) derives CanEqual
