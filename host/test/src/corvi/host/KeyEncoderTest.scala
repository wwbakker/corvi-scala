package corvi.host

import com.techsenger.jeditermfx.core.TerminalKeyEncoder
import corvi.contract.*
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The host's key encoding: the engine's normal and application cursor sequences, xterm's modifier suffixes, Shift+Tab, and the two things
  * the engine does not encode itself (Escape and the Meta prefix).
  */
final class KeyEncoderTest:

  private def encoder(applicationCursor: Boolean): TerminalKeyEncoder =
    val engine = new TerminalKeyEncoder()
    engine.setAltSendsEscape(false)
    if applicationCursor then engine.arrowKeysApplicationSequences()
    engine

  private def encoded(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.none, applicationCursor: Boolean = false): Option[String] =
    encodedWith(encoder(applicationCursor), key, modifiers)

  private def encodedWith(engine: TerminalKeyEncoder, key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.none): Option[String] =
    KeyEncoder.encode(KeyInput(key, modifiers), engine.getCode).map(bytes => new String(bytes, StandardCharsets.UTF_8))

  private def mods(shift: Boolean = false, alt: Boolean = false, control: Boolean = false, meta: Boolean = false): KeyModifiers =
    KeyModifiers(shift = shift, alt = alt, control = control, meta = meta)

  @Test def `the arrows use normal cursor sequences until DECCKM is set`(): Unit =
    assertEquals(Some("\u001b[A"), encoded(TerminalKey.Up))
    assertEquals(Some("\u001b[B"), encoded(TerminalKey.Down))
    assertEquals(Some("\u001b[D"), encoded(TerminalKey.Left))
    assertEquals(Some("\u001b[C"), encoded(TerminalKey.Right))
    assertEquals(Some("\u001bOA"), encoded(TerminalKey.Up, applicationCursor = true))
    assertEquals(Some("\u001bOB"), encoded(TerminalKey.Down, applicationCursor = true))
    assertEquals(Some("\u001bOD"), encoded(TerminalKey.Left, applicationCursor = true))
    assertEquals(Some("\u001bOC"), encoded(TerminalKey.Right, applicationCursor = true))

  @Test def `home, end, paging, insert and delete have their own sequences`(): Unit =
    assertEquals(Some("\u001b[H"), encoded(TerminalKey.Home))
    assertEquals(Some("\u001b[F"), encoded(TerminalKey.End))
    assertEquals(Some("\u001b[5~"), encoded(TerminalKey.PageUp))
    assertEquals(Some("\u001b[6~"), encoded(TerminalKey.PageDown))
    assertEquals(Some("\u001b[2~"), encoded(TerminalKey.Insert))
    assertEquals(Some("\u001b[3~"), encoded(TerminalKey.Delete))

  @Test def `the function keys use the xterm sequences`(): Unit =
    assertEquals(Some("\u001bOP"), encoded(TerminalKey.F1))
    assertEquals(Some("\u001bOQ"), encoded(TerminalKey.F2))
    assertEquals(Some("\u001bOR"), encoded(TerminalKey.F3))
    assertEquals(Some("\u001bOS"), encoded(TerminalKey.F4))
    assertEquals(Some("\u001b[15~"), encoded(TerminalKey.F5))
    assertEquals(Some("\u001b[24~"), encoded(TerminalKey.F12))

  @Test def `shift-tab is the back-tab sequence`(): Unit =
    assertEquals(Some("\u001b[Z"), encoded(TerminalKey.Tab, mods(shift = true)))

  @Test def `cursor and function keys carry xterm modifier suffixes`(): Unit =
    assertEquals(Some("\u001b[1;2A"), encoded(TerminalKey.Up, mods(shift = true)))
    assertEquals(Some("\u001b[1;5D"), encoded(TerminalKey.Left, mods(control = true)))
    assertEquals(Some("\u001b[1;3D"), encoded(TerminalKey.Left, mods(alt = true)))
    assertEquals(Some("\u001b[1;6C"), encoded(TerminalKey.Right, mods(shift = true, control = true)))
    assertEquals(Some("\u001b[15;5~"), encoded(TerminalKey.F5, mods(control = true)))
    assertEquals(Some("\u001b[1;9P"), encoded(TerminalKey.F1, mods(meta = true)))

  @Test def `the editing keys keep their bytes and escape is the fallback`(): Unit =
    assertEquals(Some("\u007f"), encoded(TerminalKey.Backspace))
    assertEquals(Some("\r"), encoded(TerminalKey.Enter))
    assertEquals(Some("\t"), encoded(TerminalKey.Tab))
    assertEquals(Some("\u001b"), encoded(TerminalKey.Escape))

  @Test def `alt on a non-cursor key is the meta prefix`(): Unit =
    assertEquals(Some("\u001b\u007f"), encoded(TerminalKey.Backspace, mods(alt = true)))
    assertEquals(Some("\u001b\r"), encoded(TerminalKey.Enter, mods(alt = true)))
    assertEquals(Some("\u001b\u001b"), encoded(TerminalKey.Escape, mods(alt = true)))

  @Test def `printable input is escaped for a meta key and plain otherwise`(): Unit =
    assertEquals(Some("\u001ba"), encoded(TerminalKey.Printable("a"), mods(alt = true)))
    assertEquals(Some("\u001bA"), encoded(TerminalKey.Printable("A"), mods(alt = true, shift = true)))
    assertEquals(Some("a"), encoded(TerminalKey.Printable("a")))

  @Test def `printable input rejects modifiers it cannot express`(): Unit =
    assertTrue(encoded(TerminalKey.Printable("a"), mods(control = true)).isEmpty)
    assertTrue(encoded(TerminalKey.Printable("a"), mods(meta = true)).isEmpty)
    assertTrue(encoded(TerminalKey.Printable("a"), mods(alt = true, control = true)).isEmpty)
    assertTrue(encoded(TerminalKey.Printable("a"), mods(alt = true, meta = true)).isEmpty)

  @Test def `a modified function key does not corrupt the plain key after it`(): Unit =
    val engine = encoder(applicationCursor = false)
    assertEquals(Some("\u001b[1;2P"), encodedWith(engine, TerminalKey.F1, mods(shift = true)))
    assertEquals(Some("\u001bOP"), encodedWith(engine, TerminalKey.F1))
    assertEquals(Some("\u001b[15;5~"), encodedWith(engine, TerminalKey.F5, mods(control = true)))
    assertEquals(Some("\u001b[15~"), encodedWith(engine, TerminalKey.F5))

  @Test def `a modified application arrow does not corrupt the plain arrow after it`(): Unit =
    val engine = encoder(applicationCursor = true)
    assertEquals(Some("\u001b[1;2A"), encodedWith(engine, TerminalKey.Up, mods(shift = true)))
    assertEquals(Some("\u001bOA"), encodedWith(engine, TerminalKey.Up))
    assertEquals(Some("\u001b[1;6C"), encodedWith(engine, TerminalKey.Right, mods(shift = true, control = true)))
    assertEquals(Some("\u001bOC"), encodedWith(engine, TerminalKey.Right))
