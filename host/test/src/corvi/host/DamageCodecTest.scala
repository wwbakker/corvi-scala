package corvi.host

import corvi.contract.*
import corvi.protocol.DamageCodec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The binary damage payload has to round-trip everything the renderer draws, including runs, wide cells, styles and links. */
final class DamageCodecTest:

  @Test
  def `a full damage value round-trips through the binary payload`(): Unit =
    val blank = Cell(" ", CellWidth.Narrow, None, None, false, false, false, false)
    val cells = Vector(
      blank,
      blank,
      Cell("A", CellWidth.Narrow, Some(Rgb(1, 2, 3)), Some(Rgb(4, 5, 6)), bold = true, italic = true, underline = true, inverse = true),
      Cell("世", CellWidth.Wide, None, None, false, false, false, false),
      Cell("", CellWidth.Continuation, None, None, false, false, false, false),
      Cell("L", CellWidth.Narrow, None, None, false, false, false, false, Some("https://example.com/世界"))
    )
    val damage = Damage(
      rows = Vector(RowDamage(3, Row(cells)), RowDamage(7, Row(Vector(blank)))),
      size = Some(TermSize(8, 9)),
      cursor = Some(Cursor(4, 5, Some(CursorShape.VerticalBar), blinking = true, visible = false)),
      modes = Some(Modes(true, MouseMode.AnyMotion, MouseFormat.Sgr, bracketedPaste = true, "π - Work", focusReporting = true)),
      historyRows = Some(42)
    )
    assertEquals(damage, DamageCodec.decode(DamageCodec.encode(damage)), "the binary payload is lossless")
    assertTrue(DamageCodec.encode(damage).nonEmpty, "the payload is non-empty")

  @Test
  def `an empty damage stays empty`(): Unit =
    assertEquals(Damage.none, DamageCodec.decode(DamageCodec.encode(Damage.none)))
