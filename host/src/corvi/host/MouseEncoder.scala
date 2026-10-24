package corvi.host

import corvi.contract.*
import java.nio.charset.StandardCharsets

/** Encodes the renderer's cell-relative pointer events in the terminal's current tracking mode and encoding.
  *
  * The protocol names are DECSET's: 1000 is click tracking, 1001 highlight, 1002 button motion and 1003 any motion; 1005, 1006 and 1015
  * select UTF-8, SGR and urxvt coordinates. The button bits are the xterm ones: shift/alt/control in 4/8/16, motion in 32, and the wheel is
  * button 64 plus its direction. This lives in the host because the host is the side that knows the modes the emulator received.
  */
private[host] object MouseEncoder:

  def encode(input: MouseInput, mode: MouseMode, format: MouseFormat): Option[Array[Byte]] =
    if !reports(input, mode) then None
    else
      val code = eventCode(input, format)
      format match
        case MouseFormat.X10   => default(input, code)
        case MouseFormat.Utf8  => utf8(input, code)
        case MouseFormat.Urxvt => urxvt(input, code)
        case MouseFormat.Sgr   => sgr(input, code)

  /** Whether this mode wants this kind of event. Highlight tracking is click tracking with the terminal's highlight behaviour; focus
    * reports are not pointer events at all.
    */
  private def reports(input: MouseInput, mode: MouseMode): Boolean =
    val validButton = input.action == MouseAction.Move || input.button.nonEmpty
    val wheelMove = input.action == MouseAction.Move && input.button.exists(isWheel)
    validButton && !wheelMove && (mode match
      case MouseMode.Off | MouseMode.Focus       => false
      case MouseMode.Click | MouseMode.Highlight => input.action != MouseAction.Move
      case MouseMode.ButtonMotion                => input.action != MouseAction.Move || input.button.nonEmpty
      case MouseMode.AnyMotion                   => true)

  private def eventCode(input: MouseInput, format: MouseFormat): Int =
    val modifiers = (if input.modifiers.shift then 4 else 0) |
      (if input.modifiers.alt then 8 else 0) |
      (if input.modifiers.control then 16 else 0)
    input.button match
      case Some(button) if isWheel(button)                                             => wheelCode(button) | modifiers
      case Some(_) if input.action == MouseAction.Release && format != MouseFormat.Sgr =>
        3 | modifiers
      case Some(button) =>
        buttonCode(button) | (if input.action == MouseAction.Move then 32 else 0) | modifiers
      case None => 3 | (if input.action == MouseAction.Move then 32 else 0) | modifiers

  private def isWheel(button: MouseButton): Boolean =
    button match
      case MouseButton.WheelUp | MouseButton.WheelDown | MouseButton.WheelLeft | MouseButton.WheelRight => true
      case _                                                                                            => false

  private def wheelCode(button: MouseButton): Int =
    button match
      case MouseButton.WheelUp    => 64
      case MouseButton.WheelDown  => 65
      case MouseButton.WheelLeft  => 66
      case MouseButton.WheelRight => 67
      case _                      => 64

  private def buttonCode(button: MouseButton): Int =
    button match
      case MouseButton.Left   => 0
      case MouseButton.Middle => 1
      case MouseButton.Right  => 2
      case _                  => 3

  private def default(input: MouseInput, code: Int): Option[Array[Byte]] =
    val values = Array(code + 32, input.column + 33, input.row + 33)
    if values.exists(_ > 255) then None
    else Some(Array[Byte](0x1b, '['.toByte, 'M'.toByte) ++ values.map(_.toByte))

  private def utf8(input: MouseInput, code: Int): Option[Array[Byte]] =
    val values = Array(code + 32, input.column + 33, input.row + 33)
    if values.exists(value => value < 0 || value > Character.MAX_VALUE) then None
    else
      val encoded = new String(values.map(_.toChar)).getBytes(StandardCharsets.UTF_8)
      Some(Array[Byte](0x1b, '['.toByte, 'M'.toByte) ++ encoded)

  private def urxvt(input: MouseInput, code: Int): Option[Array[Byte]] =
    val finalCode = if input.action == MouseAction.Release && !input.button.exists(isWheel) then 3 else code
    Some(s"\u001b[$finalCode;${input.column + 1};${input.row + 1}M".getBytes(StandardCharsets.US_ASCII))

  private def sgr(input: MouseInput, code: Int): Option[Array[Byte]] =
    val finalByte = if input.action == MouseAction.Release && !input.button.exists(isWheel) then 'm' else 'M'
    Some(s"\u001b[<$code;${input.column + 1};${input.row + 1}$finalByte".getBytes(StandardCharsets.US_ASCII))
