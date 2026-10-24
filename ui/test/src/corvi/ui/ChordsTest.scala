package corvi.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The chord machine, and the one adapter that reads a `KeyEvent`. The modifier matrix is where a platform surprise lives, so it is checked
  * here rather than only through the window.
  *
  * Constructing a `KeyEvent` needs the JavaFX classes, not a started toolkit: no display is required for these.
  */
final class ChordsTest:

  private val now: Long = 1_000_000L

  private def armed: (Chords.State, Chords.Outcome) =
    Chords.press(Chords.initial, Chords.Key.Prefix, mac = true, now)

  private def key(
      code: javafx.scene.input.KeyCode,
      shift: Boolean = false,
      control: Boolean = false,
      alt: Boolean = false,
      meta: Boolean = false
  ): javafx.scene.input.KeyEvent =
    new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta)

  // --- the prefix and what follows it ------------------------------------------------------------------------------------------------

  @Test def `ctrl-b arms the prefix`(): Unit =
    val (state, outcome) = armed
    assertEquals(Chords.Outcome.PrefixArmed, outcome)
    assertTrue(state.waiting(now))

  @Test def `a digit after the prefix selects by its 0-based position`(): Unit =
    assertEquals(Chords.Outcome.Select(1), Chords.press(armed._1, Chords.Key.Digit(1), mac = true, now + 1)._2)

  @Test def `the first position is zero`(): Unit =
    assertEquals(Chords.Outcome.Select(0), Chords.press(armed._1, Chords.Key.Digit(0), mac = true, now + 1)._2)

  @Test def `c after the prefix opens a window`(): Unit =
    assertEquals(Chords.Outcome.Open, Chords.press(armed._1, Chords.Key.Create, mac = true, now + 1)._2)

  @Test def `a digit after the prefix expired belongs to the terminal`(): Unit =
    val (state, outcome) = Chords.press(armed._1, Chords.Key.Digit(1), mac = true, now + Chords.prefixNanos + 1)
    assertEquals(Chords.Outcome.Passed, outcome)
    assertFalse(state.waiting(now))

  @Test def `an out-of-range position is passed on, and the strip has no window for it`(): Unit =
    val (state, _) = ShellState.initial.opened("one", corvi.contract.SessionId("s1"))
    assertEquals(Chords.Outcome.Select(9), Chords.press(armed._1, Chords.Key.Digit(9), mac = true, now + 1)._2)
    assertTrue(state.windows.lift(9).isEmpty)

  // --- the typed companion -----------------------------------------------------------------------------------------------------------

  @Test def `the typed companion of a key the strip took is swallowed once`(): Unit =
    assertTrue(Chords.typed(armed._1, now + 1)._2)
    assertFalse(Chords.typed(armed._1, now + 1)._1.swallowing(now + 1))

  @Test def `a shortcut's companion is swallowed inside its deadline and not after it`(): Unit =
    val (afterShortcut, outcome) = Chords.press(Chords.initial, Chords.Key.Shortcut, mac = true, now)
    assertEquals(Chords.Outcome.PassedShortcut, outcome)
    assertTrue(Chords.typed(afterShortcut, now + 1)._2, "the platform's companion arrives immediately after the press")
    assertFalse(
      Chords.typed(afterShortcut, now + Chords.swallowNanos + 1)._2,
      "a Command chord sends no KEY_TYPED; a later character must not be taken for its companion"
    )

  @Test def `alt is not a shortcut, so an option-composed character passes`(): Unit =
    val optionE = Chords.Key.from(key(javafx.scene.input.KeyCode.E, alt = true), true)
    assertEquals(Chords.Key.Other, optionE)
    val (afterOption, outcome) = Chords.press(Chords.initial, optionE, mac = true, now)
    assertEquals(Chords.Outcome.Passed, outcome)
    assertFalse(Chords.typed(afterOption, now + 1)._2, "Option+E composes a character; it belongs to the shell")

  @Test def `a passed key's companion belongs to the terminal`(): Unit =
    val (passed, _) = Chords.press(Chords.initial, Chords.Key.Other, mac = true, now)
    assertFalse(Chords.typed(passed, now + 1)._2)

  // --- the modifier matrix -----------------------------------------------------------------------------------------------------------

  @Test def `the open chord is command-T on macOS`(): Unit =
    assertEquals(Chords.Key.Open, Chords.Key.from(key(javafx.scene.input.KeyCode.T, meta = true), true))

  @Test def `the open chord is ctrl-alt-t elsewhere`(): Unit =
    assertEquals(Chords.Key.Open, Chords.Key.from(key(javafx.scene.input.KeyCode.T, control = true, alt = true), false))

  @Test def `shift is not part of any chord`(): Unit =
    // Still not a chord, but a modified key with the platform menu key held is a shortcut: the terminal gets it, its typed companion does not.
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.T, shift = true, meta = true), true))
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.B, shift = true, control = true), true))

  @Test def `ctrl-b is the prefix on either platform`(): Unit =
    assertEquals(Chords.Key.Prefix, Chords.Key.from(key(javafx.scene.input.KeyCode.B, control = true), false))
    assertEquals(Chords.Key.Prefix, Chords.Key.from(key(javafx.scene.input.KeyCode.B, control = true), true))

  @Test def `command-W closes on macOS`(): Unit =
    assertEquals(Chords.Key.Close, Chords.Key.from(key(javafx.scene.input.KeyCode.W, meta = true), true))
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.W, meta = true), false))

  @Test def `a plain key belongs to the terminal`(): Unit =
    assertEquals(Chords.Key.Create, Chords.Key.from(key(javafx.scene.input.KeyCode.C), true))
    assertEquals(Chords.Key.Other, Chords.Key.from(key(javafx.scene.input.KeyCode.X), true))

  @Test def `a shortcut is passed to the terminal with its typed companion swallowed`(): Unit =
    val ctrlC = Chords.Key.from(key(javafx.scene.input.KeyCode.C, control = true), true)
    val (afterCtrlC, outcome) = Chords.press(Chords.initial, ctrlC, mac = true, now)
    assertEquals(Chords.Key.Shortcut, ctrlC)
    assertEquals(Chords.Outcome.PassedShortcut, outcome)
    assertTrue(Chords.typed(afterCtrlC, now + 1)._2, "the platform's KEY_TYPED for ^C must not type a 'c'")

  @Test def `command and control keys are shortcuts, not open or close`(): Unit =
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.C, meta = true), true))
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.V, meta = true), true))
    assertEquals(Chords.Key.Shortcut, Chords.Key.from(key(javafx.scene.input.KeyCode.SPACE, control = true), true))

  @Test def `a shift-only key is not a shortcut and its companion is the terminal's`(): Unit =
    val shiftC = Chords.Key.from(key(javafx.scene.input.KeyCode.C, shift = true), true)
    assertEquals(Chords.Key.Other, shiftC)
    assertEquals(Chords.Outcome.Passed, Chords.press(Chords.initial, shiftC, mac = true, now)._2)
    assertFalse(Chords.typed(Chords.press(Chords.initial, shiftC, mac = true, now)._1, now + 1)._2, "an uppercase C is a character")

  @Test def `a plain digit names a position, and nine is not one`(): Unit =
    assertEquals(Chords.Key.Digit(0), Chords.Key.from(key(javafx.scene.input.KeyCode.DIGIT0), true))
    assertEquals(Chords.Key.Digit(8), Chords.Key.from(key(javafx.scene.input.KeyCode.DIGIT8), true))
    assertEquals(Chords.Key.Other, Chords.Key.from(key(javafx.scene.input.KeyCode.DIGIT9), true))
