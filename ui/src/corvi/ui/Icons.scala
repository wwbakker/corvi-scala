package corvi.ui

import scalafx.scene.Node
import scalafx.scene.layout.StackPane
import scalafx.scene.shape.{Circle, SVGPath, Shape}

/** Corvi's icons, copied from `apps/web/src/app-root/icons.tsx`. Strokes are left to CSS (`.tab-icon`, `.gear-icon`) — a JavaFX parent
  * cannot style its children's strokes for them — so every shape carries the style classes itself.
  */
private[corvi] object Icons:
  /** The cog is the one glyph drawn on a 24 grid: at thirteen pixels a hand-built tooth ring turns into a sunburst, while the familiar
    * outline survives.
    */
  private val gearBox: Double = 24

  private val cogPath: String =
    "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 " +
      "0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 " +
      "2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 " +
      "2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 " +
      "2 0 0 0-2-2z"

  /** The terminal glyph: the chevron and the underscore, stroke 2, round caps and joins. */
  def terminal(styleClasses: String*): Node =
    glyph(Theme.terminalIconSize, Theme.iconBox, styleClasses, "M2.5 4l3.5 4-3.5 4", "M8.5 12.5h5.5")

  /** The sidebar's settings cog: the cog outline with its hub. */
  def gear(styleClasses: String*): Node =
    val icon = iconBox(Theme.gearIconSize)
    val scale = Theme.gearIconSize / gearBox
    add(icon, svgPath(cogPath), styleClasses, scale)
    val hub = new Circle:
      centerX = gearBox / 2
      centerY = gearBox / 2
      radius = 3
    add(icon, hub, styleClasses, scale)
    icon

  private def glyph(size: Double, viewBox: Double, styleClasses: Seq[String], paths: String*): StackPane =
    val icon = iconBox(size)
    paths.foreach(path => add(icon, svgPath(path), styleClasses, size / viewBox))
    icon

  /** A fixed-size box; the `StackPane` keeps each shape centred while it is scaled. */
  private def iconBox(size: Double): StackPane =
    new StackPane:
      minWidth = size
      minHeight = size
      prefWidth = size
      prefHeight = size
      maxWidth = size
      maxHeight = size

  private def svgPath(path: String): SVGPath =
    new SVGPath:
      this.content = path

  private def add(box: StackPane, shape: Shape, styleClasses: Seq[String], scale: Double): Unit =
    val _ = shape.styleClass ++= styleClasses
    shape.scaleX = scale
    shape.scaleY = scale
    val _ = box.children += shape
