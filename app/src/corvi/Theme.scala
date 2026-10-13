package corvi

/** The layout numbers the shell computes with. The palette is not here: `corvi.css` defines the `-fx-corvi-*` looked-up colours on `.root`,
  * so colours are written down in exactly one place.
  */
private[corvi] object Theme:
  /** Corvi's title bar is 38 px, and a row of tabs is that less the hairline under it. A tab fills the whole row — flush top and bottom,
    * which is why it only rounds its top corners.
    */
  val tabRowHeight: Double = 37

  /** The `--line` hairline the strip carries below its row. */
  val stripHairline: Double = 1

  /** Tabs hang off the left and right edges of the strip by this much. */
  val tabStripSidePadding: Double = 8

  /** The gap the icon, the label and the bell keep between each other *inside* a tab. The strip itself has no column gap: upstream's tabs
    * sit flush, and only the rows are set apart.
    */
  val tabContentSpacing: Double = 6

  /** The gap between two rows of a wrapped strip. */
  val tabRowSpacing: Double = 2

  /** A tab never grows past this; longer labels ellipsize instead. */
  val tabMaxWidth: Double = 220

  /** Everything in a tab that is not its label: 10 px of padding a side, the 13 px icon, and the gap after the icon.
    */
  val tabChromeWidth: Double = 39

  /** What a bell adds to that: the gap before it and its 6 px dot. */
  val tabBellWidth: Double = 12

  /** Icons are drawn on a 16×16 view box. */
  val iconBox: Double = 16
  val terminalIconSize: Double = 13

  /** The cog is drawn on a 24 grid instead (see `Icons.gear`) and shown a size up from the status glyphs, as upstream's sidebar does.
    */
  val gearIconSize: Double = 15

  val sidebarWidth: Double = 216

  val stageWidth: Double = 1180
  val stageHeight: Double = 740
  val stageMinWidth: Double = 720
  val stageMinHeight: Double = 420
