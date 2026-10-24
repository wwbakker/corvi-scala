package corvi.renderer

import javafx.scene.input.KeyCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The terminal's key encoding: which C0 byte a Control chord sends, and which two spellings belong to the window's copy and paste. Pure
  * values, so the key path can be checked without a keyboard.
  */
final class ControlKeysTest:

  private def control(code: KeyCode, shift: Boolean = false): Option[String] = ControlKeys.controlByte(code, shift)

  @Test def `control and a letter send its position in the alphabet`(): Unit =
    assertEquals(Some("\u0001"), control(KeyCode.A))
    assertEquals(Some("\u0003"), control(KeyCode.C))
    assertEquals(Some("\u0004"), control(KeyCode.D))
    assertEquals(Some("\u0005"), control(KeyCode.E))
    assertEquals(Some("\u001a"), control(KeyCode.Z))

  @Test def `the letters that are already keys keep their byte`(): Unit =
    assertEquals(Some("\u0008"), control(KeyCode.H), "Ctrl+H is Backspace")
    assertEquals(Some("\t"), control(KeyCode.I), "Ctrl+I is Tab")
    assertEquals(Some("\n"), control(KeyCode.J), "Ctrl+J is line feed")
    assertEquals(Some("\r"), control(KeyCode.M), "Ctrl+M is carriage return")

  @Test def `control and space is NUL`(): Unit =
    assertEquals(Some("\u0000"), control(KeyCode.SPACE))

  @Test def `the punctuation after Z continues the sequence`(): Unit =
    assertEquals(Some("\u001b"), control(KeyCode.OPEN_BRACKET))
    assertEquals(Some("\u001c"), control(KeyCode.BACK_SLASH))
    assertEquals(Some("\u001d"), control(KeyCode.CLOSE_BRACKET))
    assertEquals(Some("\u001e"), control(KeyCode.CIRCUMFLEX))
    assertEquals(Some("\u001f"), control(KeyCode.UNDERSCORE))

  @Test def `control-slash is unit separator and control-question-mark is delete`(): Unit =
    assertEquals(Some("\u001f"), control(KeyCode.SLASH), "Ctrl+/ is 0x1f")
    assertEquals(Some("\u007f"), control(KeyCode.SLASH, shift = true), "Ctrl+? is Delete")

  @Test def `a key control does not turn into a byte`(): Unit =
    assertEquals(None, control(KeyCode.F1))
    assertEquals(None, control(KeyCode.LEFT))

  @Test def `copy is command-C, or control-shift-C`(): Unit =
    assertTrue(ControlKeys.isCopy(KeyCode.C, meta = true, control = false, shift = false, alt = false))
    assertTrue(ControlKeys.isCopy(KeyCode.C, meta = false, control = true, shift = true, alt = false))
    assertFalse(ControlKeys.isCopy(KeyCode.C, meta = false, control = true, shift = false, alt = false), "Ctrl+C is SIGINT")
    assertFalse(ControlKeys.isCopy(KeyCode.V, meta = true, control = false, shift = false, alt = false))

  @Test def `paste is command-V, or control-shift-V`(): Unit =
    assertTrue(ControlKeys.isPaste(KeyCode.V, meta = true, control = false, shift = false, alt = false))
    assertTrue(ControlKeys.isPaste(KeyCode.V, meta = false, control = true, shift = true, alt = false))
    assertFalse(ControlKeys.isPaste(KeyCode.V, meta = false, control = true, shift = false, alt = false))

  @Test def `alt is never a copy or paste chord`(): Unit =
    assertFalse(ControlKeys.isCopy(KeyCode.C, meta = true, control = false, shift = false, alt = true))
    assertFalse(ControlKeys.isPaste(KeyCode.V, meta = true, control = false, shift = false, alt = true))
