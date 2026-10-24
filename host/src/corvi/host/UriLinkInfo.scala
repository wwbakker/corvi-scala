package corvi.host

import com.techsenger.jeditermfx.core.model.hyperlinks.LinkInfo

/** The URI carried by an OSC 8 link.
  *
  * HyperlinkStyle keeps only a LinkInfo (whose public API is a Runnable). Storing the URI in a LinkInfo subclass lets the engine derive new
  * HyperlinkStyle values (for example after SGR changes) and still resolve the original target without an identity map.
  */
private[host] final class UriLinkInfo(val uri: String) extends LinkInfo(() => ())
