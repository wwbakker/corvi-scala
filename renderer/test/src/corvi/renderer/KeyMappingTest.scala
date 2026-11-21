package corvi.renderer

import corvi.contract.TerminalKey
import javafx.scene.input.KeyCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The renderer's JavaFX-to-terminal key table, without a scene: the printable mapping is what Alt-as-Meta uses when the platform's typed
  * character is not the key.
  */
final class KeyMappingTest:

  @Test def `letters follow Shift`(): Unit =
    assertEquals(Some("a"), KeyMapping.printable(KeyCode.A, shift = false))
    assertEquals(Some("A"), KeyMapping.printable(KeyCode.A, shift = true))
    assertEquals(Some(" "), KeyMapping.printable(KeyCode.SPACE, shift = false))

  @Test def `digits use the US shifted symbols`(): Unit =
    val digits = Vector(
      KeyCode.DIGIT0 -> ("0", ")"),
      KeyCode.DIGIT1 -> ("1", "!"),
      KeyCode.DIGIT2 -> ("2", "@"),
      KeyCode.DIGIT3 -> ("3", "#"),
      KeyCode.DIGIT4 -> ("4", "$"),
      KeyCode.DIGIT5 -> ("5", "%"),
      KeyCode.DIGIT6 -> ("6", "^"),
      KeyCode.DIGIT7 -> ("7", "&"),
      KeyCode.DIGIT8 -> ("8", "*"),
      KeyCode.DIGIT9 -> ("9", "(")
    )
    digits.foreach { case (code, (plain, shifted)) =>
      assertEquals(Some(plain), KeyMapping.printable(code, shift = false))
      assertEquals(Some(shifted), KeyMapping.printable(code, shift = true))
    }

  @Test def `punctuation follows the US shifted table`(): Unit =
    val punctuation = Vector(
      KeyCode.MINUS -> ("-", "_"),
      KeyCode.EQUALS -> ("=", "+"),
      KeyCode.OPEN_BRACKET -> ("[", "{"),
      KeyCode.CLOSE_BRACKET -> ("]", "}"),
      KeyCode.BACK_SLASH -> ("\\", "|"),
      KeyCode.SEMICOLON -> (";", ":"),
      KeyCode.QUOTE -> ("'", "\""),
      KeyCode.COMMA -> (",", "<"),
      KeyCode.PERIOD -> (".", ">"),
      KeyCode.SLASH -> ("/", "?"),
      KeyCode.BACK_QUOTE -> ("`", "~")
    )
    punctuation.foreach { case (code, (plain, shifted)) =>
      assertEquals(Some(plain), KeyMapping.printable(code, shift = false))
      assertEquals(Some(shifted), KeyMapping.printable(code, shift = true))
    }

  @Test def `a key outside the table needs the typed fallback`(): Unit =
    assertEquals(None, KeyMapping.printable(KeyCode.UNDEFINED, shift = false))
    assertEquals(Some(TerminalKey.F1), KeyMapping.terminalKey(KeyCode.F1))
