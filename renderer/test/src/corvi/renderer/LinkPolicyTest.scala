package corvi.renderer

import corvi.contract.LinkPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The schemes allowed to reach the operating system from an OSC 8 link. */
final class LinkPolicyTest:

  @Test def `http, https and file are allowed`(): Unit =
    assertTrue(LinkPolicy.allows("http://example.com"))
    assertTrue(LinkPolicy.allows("https://example.com/a?b=c"))
    assertTrue(LinkPolicy.allows("file:///tmp/readme"))
    assertTrue(LinkPolicy.allows("HTTPS://EXAMPLE.COM"))

  @Test def `other and malformed schemes are refused`(): Unit =
    assertFalse(LinkPolicy.allows("javascript:alert(1)"))
    assertFalse(LinkPolicy.allows("data:text/plain,hello"))
    assertFalse(LinkPolicy.allows("ftp://example.com"))
    assertFalse(LinkPolicy.allows("not a uri"))
    assertFalse(LinkPolicy.allows(""))
