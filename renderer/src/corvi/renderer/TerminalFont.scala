package corvi.renderer

import javafx.scene.text.{Font, Text}

/** A monospaced font and the size of one of its cells. Measured once, on the FX thread, because JavaFX is the only thing that knows how
  * wide a glyph really is; everything after that is arithmetic (`Layout`).
  */
final case class TerminalMetrics(font: Font, cellWidth: Double, cellHeight: Double, ascent: Double):

  def bold: Font = Font.font(font.getFamily, javafx.scene.text.FontWeight.BOLD, font.getSize)

  def italic: Font = Font.font(font.getFamily, javafx.scene.text.FontPosture.ITALIC, font.getSize)

  /** Both attributes at once: a bold italic face is its own font, not one attribute layered on the other. */
  def boldItalic: Font =
    Font.font(font.getFamily, javafx.scene.text.FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, font.getSize)

  def layout: Layout = Layout(cellWidth, cellHeight)

object TerminalMetrics:

  /** Measures the widest common glyph and the tallest line, so a cell never clips. A terminal font is monospaced, so one glyph's width is
    * every glyph's width; `Mg` is measured for its ascender and descender.
    */
  def measure(font: Font): TerminalMetrics =
    val wide = Text("M")
    wide.setFont(font)
    val tall = Text("Mg")
    tall.setFont(font)
    val width = math.max(1.0, math.ceil(wide.getLayoutBounds.getWidth))
    val height = math.max(1.0, math.ceil(tall.getLayoutBounds.getHeight))
    // The baseline, measured from the top of the cell: text is drawn by its baseline, cells are laid out by their top edge.
    val ascent = math.max(1.0, tall.getBaselineOffset)
    TerminalMetrics(font, width, height, ascent)

  /** The first font of the list the platform actually has, at the given size. */
  def monospaced(size: Double): TerminalMetrics =
    val families = Seq("Menlo", "Monaco", "DejaVu Sans Mono", "Consolas", "Courier New", "monospaced")
    val family = families.find(name => Font.getFamilies.contains(name)).getOrElse("monospaced")
    measure(Font.font(family, size))
