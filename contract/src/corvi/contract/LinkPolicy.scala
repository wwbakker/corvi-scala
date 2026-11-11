package corvi.contract

import java.net.URI
import scala.util.Try

/** The schemes Corvi is willing to hand to the operating system. Terminal programs must not be able to launch arbitrary URL handlers
  * through an OSC 8 link.
  */
object LinkPolicy:
  private val allowedSchemes: Set[String] = Set("http", "https", "file")

  def allows(uri: String): Boolean =
    Try(URI(uri)).toOption
      .flatMap(target => Option(target.getScheme))
      .map(_.toLowerCase(java.util.Locale.ROOT))
      .exists(allowedSchemes.contains)
