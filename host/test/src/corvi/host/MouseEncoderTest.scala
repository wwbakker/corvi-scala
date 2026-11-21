package corvi.host

import corvi.contract.*
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The host's pointer encoding: the event code and the three coordinate encodings, and the tracking modes that decide whether an event is
  * reported at all.
  */
final class MouseEncoderTest:

  private val modifiers = MouseModifiers(shift = false, alt = false, control = false)

  private def input(
      column: Int = 0,
      row: Int = 0,
      button: Option[MouseButton],
      action: MouseAction = MouseAction.Press,
      mods: MouseModifiers = modifiers
  ): MouseInput = MouseInput(column, row, button, action, mods)

  private def encoded(input: MouseInput, mode: MouseMode = MouseMode.Click, format: MouseFormat = MouseFormat.Sgr): Option[String] =
    MouseEncoder.encode(input, mode, format).map(bytes => new String(bytes, StandardCharsets.UTF_8))

  private def encodedBytes(input: MouseInput, mode: MouseMode, format: MouseFormat): Option[Vector[Byte]] =
    MouseEncoder.encode(input, mode, format).map(_.toVector)

  @Test def `SGR reports a press and a release with one-based coordinates`(): Unit =
    assertEquals(Some("\u001b[<0;1;1M"), encoded(input(button = Some(MouseButton.Left))))
    assertEquals(
      Some("\u001b[<0;3;4m"),
      encoded(input(column = 2, row = 3, button = Some(MouseButton.Left), action = MouseAction.Release))
    )

  @Test def `SGR reports middle and right buttons`(): Unit =
    assertEquals(Some("\u001b[<1;1;1M"), encoded(input(button = Some(MouseButton.Middle))))
    assertEquals(Some("\u001b[<2;1;1M"), encoded(input(button = Some(MouseButton.Right))))

  @Test def `SGR reports the wheel and its direction`(): Unit =
    assertEquals(Some("\u001b[<64;3;4M"), encoded(input(column = 2, row = 3, button = Some(MouseButton.WheelUp))))
    assertEquals(Some("\u001b[<65;3;4M"), encoded(input(column = 2, row = 3, button = Some(MouseButton.WheelDown))))
    assertEquals(Some("\u001b[<66;3;4M"), encoded(input(column = 2, row = 3, button = Some(MouseButton.WheelLeft))))
    assertEquals(Some("\u001b[<67;3;4M"), encoded(input(column = 2, row = 3, button = Some(MouseButton.WheelRight))))

  @Test def `SGR reports the extended back and forward buttons`(): Unit =
    assertEquals(Some("\u001b[<128;1;1M"), encoded(input(button = Some(MouseButton.Back))))
    assertEquals(Some("\u001b[<129;1;1M"), encoded(input(button = Some(MouseButton.Forward))))
    assertEquals(
      Some("\u001b[<128;1;1m"),
      encoded(input(button = Some(MouseButton.Back), action = MouseAction.Release))
    )
    assertEquals(
      Some("\u001b[<129;1;1m"),
      encoded(input(button = Some(MouseButton.Forward), action = MouseAction.Release))
    )

  @Test def `the extended buttons use xterm's numbers in every format`(): Unit =
    val back = input(button = Some(MouseButton.Back))
    val forward = input(button = Some(MouseButton.Forward))
    assertEquals(Some("\u001b[128;1;1M"), encoded(back, format = MouseFormat.Urxvt))
    assertEquals(Some("\u001b[129;1;1M"), encoded(forward, format = MouseFormat.Urxvt))
    assertEquals(Some("\u001b[M\u00a0!!"), encoded(back, format = MouseFormat.Utf8))
    assertEquals(Some("\u001b[M\u00a1!!"), encoded(forward, format = MouseFormat.Utf8))
    assertEquals(
      Some(Vector(0x1b, 0x5b, 0x4d, 160, 33, 33).map(_.toByte)),
      encodedBytes(back, mode = MouseMode.Click, format = MouseFormat.X10)
    )
    assertEquals(
      Some(Vector(0x1b, 0x5b, 0x4d, 161, 33, 33).map(_.toByte)),
      encodedBytes(forward, mode = MouseMode.Click, format = MouseFormat.X10)
    )

  @Test def `SGR carries modifiers and the motion bit`(): Unit =
    val all = MouseModifiers(shift = true, alt = true, control = true)
    assertEquals(Some("\u001b[<28;1;1M"), encoded(input(button = Some(MouseButton.Left), mods = all)))
    assertEquals(
      Some("\u001b[<32;1;1M"),
      encoded(input(button = Some(MouseButton.Left), action = MouseAction.Move), mode = MouseMode.ButtonMotion)
    )
    assertEquals(
      Some("\u001b[<35;1;1M"),
      encoded(input(button = None, action = MouseAction.Move), mode = MouseMode.AnyMotion)
    )

  @Test def `X10 uses the one-byte form and release is button three`(): Unit =
    assertEquals(
      Some("\u001b[M !!"),
      encoded(input(button = Some(MouseButton.Left)), format = MouseFormat.X10)
    )
    assertEquals(
      Some("\u001b[M#!!"),
      encoded(input(button = Some(MouseButton.Left), action = MouseAction.Release), format = MouseFormat.X10)
    )

  @Test def `urxvt uses the decimal form`(): Unit =
    assertEquals(
      Some("\u001b[0;3;4M"),
      encoded(input(column = 2, row = 3, button = Some(MouseButton.Left)), format = MouseFormat.Urxvt)
    )

  @Test def `the mode filters the events a program asked for`(): Unit =
    assertTrue(encoded(input(button = Some(MouseButton.Left)), mode = MouseMode.Off).isEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.Left)), mode = MouseMode.Focus).isEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.Left), action = MouseAction.Move), mode = MouseMode.Click).isEmpty)
    assertTrue(encoded(input(button = None, action = MouseAction.Move), mode = MouseMode.ButtonMotion).isEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.Left), action = MouseAction.Move), mode = MouseMode.ButtonMotion).nonEmpty)
    assertTrue(encoded(input(button = None, action = MouseAction.Move), mode = MouseMode.AnyMotion).nonEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.WheelUp)), mode = MouseMode.Click).nonEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.WheelLeft)), mode = MouseMode.Click).nonEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.Back)), mode = MouseMode.Click).nonEmpty)
    assertTrue(encoded(input(button = Some(MouseButton.Forward)), mode = MouseMode.Click).nonEmpty)

  @Test def `an event without a button is only a move`(): Unit =
    assertTrue(encoded(input(button = None, action = MouseAction.Press), mode = MouseMode.AnyMotion).isEmpty)
    assertTrue(encoded(input(button = None, action = MouseAction.Release), mode = MouseMode.AnyMotion).isEmpty)
