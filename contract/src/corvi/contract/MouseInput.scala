package corvi.contract

/** The buttons a terminal can report. Wheels are buttons in the protocol, not a separate event: they differ only in the code the terminal
  * sends.
  */
enum MouseButton derives CanEqual:
  case Left, Middle, Right, WheelUp, WheelDown, WheelLeft, WheelRight

/** What happened to the pointer. A wheel notch is a press-and-release in the protocol, but the terminal only wants one report for it, so it
  * is modelled as `Press`; a pointer move that is not a drag carries no button.
  */
enum MouseAction derives CanEqual:
  case Press, Release, Move

/** The modifiers a mouse report carries. They are part of the button code's low bits, beside the motion flag, not keyboard events. */
final case class MouseModifiers(shift: Boolean, alt: Boolean, control: Boolean) derives CanEqual

/** One pointer event, in terminal cells: zero-based, because that is how a `Frame` indexes its grid. The host knows the mouse mode and
  * encoding, so this is the seam that keeps the renderer from hard-coding one terminal protocol.
  */
final case class MouseInput(
    column: Int,
    row: Int,
    button: Option[MouseButton],
    action: MouseAction,
    modifiers: MouseModifiers
) derives CanEqual
