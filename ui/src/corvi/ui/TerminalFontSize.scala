package corvi.ui

/** The terminal font size preference, shared by every view in the window.
  *
  * It is runtime state for now: there is no settings store, so the default is restored on restart. The renderer owns the actual font
  * measurement; this is the small arithmetic the chord machine needs.
  */
private[corvi] object TerminalFontSize:
  val default: Double = 13.0
  val minimum: Double = 8.0
  val maximum: Double = 32.0
  val step: Double = 1.0

  def increased(size: Double): Double = math.min(maximum, size + step)
  def decreased(size: Double): Double = math.max(minimum, size - step)
  def reset: Double = default
