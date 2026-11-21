package corvi.host

import com.techsenger.jeditermfx.core.ArrayTerminalDataStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The host's 1004 detector: it sees the private-mode sequence and leaves the stream's bytes alone. */
final class FocusTrackingDataStreamTest:

  private def observe(input: String): Vector[Boolean] =
    val events = scala.collection.mutable.ArrayBuffer.empty[Boolean]
    val stream = new FocusTrackingDataStream(new ArrayTerminalDataStream(input.toCharArray), enabled => { val _ = events += enabled })
    while !stream.isEmpty do { val _ = stream.getChar() }
    events.toVector

  @Test def `1004 enables and disables focus reporting`(): Unit =
    assertEquals(Vector(true, false), observe("\u001b[?1004h\u001b[?1004l"))

  @Test def `1004 is found in a private mode list`(): Unit =
    assertEquals(Vector(true), observe("\u001b[?1000;1004h"))
    assertEquals(Vector.empty, observe("\u001b[?1000;1002h"))

  @Test def `a malformed or unrelated sequence is ignored`(): Unit =
    assertEquals(Vector.empty, observe("\u001b[1004h"))
    assertEquals(Vector.empty, observe("\u001b[?1004"))
    assertEquals(Vector(true), observe("\u001b[?1004h"))

  @Test def `the wrapper leaves bytes, pushes and non-control reads unchanged`(): Unit =
    val inner = new ArrayTerminalDataStream("abc".toCharArray)
    val stream = new FocusTrackingDataStream(inner, _ => ())
    assertEquals("abc", stream.readNonControlCharacters(3))
    assertTrue(stream.isEmpty)

    val pushed = new FocusTrackingDataStream(new ArrayTerminalDataStream(Array.emptyCharArray), _ => ())
    pushed.pushChar('x')
    assertEquals('x', pushed.getChar())

    val back = new FocusTrackingDataStream(new ArrayTerminalDataStream("cd".toCharArray), _ => ())
    back.pushBackBuffer(Array('a', 'b'), 2)
    val characters = new StringBuilder
    while !back.isEmpty do { val _ = characters.append(back.getChar()) }
    assertEquals("abcd", characters.toString)
