package corvi.renderer

/** What Alt (Option on macOS) means to the terminal.
  *
  * `Compose` leaves it to the platform: an Option-composed `KEY_TYPED` is text and goes straight to the shell. `Meta` and `Escape` treat
  * Alt+printable as Meta+key and send it through the key route, where the host prepends Escape; the two names are the same behaviour, kept
  * apart because settings will call them what the user expects.
  *
  * The default is `Compose` on macOS, where Option is how accented characters are typed, and `Escape` elsewhere, where Alt has no composing
  * role. `CORVI_ALT_MODE` (or the `corvi.altMode` system property) overrides it until there is a settings UI.
  */
enum AltMode derives CanEqual:
  case Compose, Meta, Escape

object AltMode:
  val EnvironmentVariable: String = "CORVI_ALT_MODE"
  val SystemProperty: String = "corvi.altMode"

  /** Reads the configured mode once for the application. An unreadable or unknown value falls back to the platform default rather than
    * changing how typing works without saying so.
    */
  def configured: AltMode =
    val fromProperty = Option(System.getProperty(SystemProperty))
    val fromEnvironment = Option(System.getenv(EnvironmentVariable))
    fromProperty.orElse(fromEnvironment).flatMap(parse).getOrElse(default)

  def default: AltMode =
    val os = Option(System.getProperty("os.name")).getOrElse("").toLowerCase
    if os.contains("mac") then AltMode.Compose else AltMode.Escape

  def parse(value: String): Option[AltMode] =
    value.trim.toLowerCase match
      case "compose" => Some(AltMode.Compose)
      case "meta"    => Some(AltMode.Meta)
      case "escape"  => Some(AltMode.Escape)
      case _         => None
