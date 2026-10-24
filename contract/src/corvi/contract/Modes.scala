package corvi.contract

/** Which reports the application asked for, named by DECSET number: 1000 is click, 1001 highlight tracking, 1002 button motion, and 1003
  * any motion.
  *
  * `Focus` is DECSET 1004, focus in/out reports. It is not mouse reporting at all, but jeditermfx-core models it as a `MouseMode`, so the
  * contract keeps it beside the others and a client that only cares about mice can ignore it. Mode 9 (the original "X10" reporting) has no
  * case: the engine does not implement it, and an enum case nothing can produce is a lie in every `match` over it.
  */
enum MouseMode derives CanEqual:
  case Off, Click, Highlight, ButtonMotion, AnyMotion, Focus

/** How a report is encoded, named by DECSET number for the ones that have one: 1005 is UTF-8 coordinates, 1006 SGR, and 1015 urxvt. `X10`
  * is the original three-byte form, which is what the engine uses until a format is set and what it returns to when one is cleared.
  */
enum MouseFormat derives CanEqual:
  case X10, Utf8, Urxvt, Sgr

/** The terminal modes the renderer needs in order to send the right input, plus the title the shell set with OSC 0 or 2.
  *
  * The title lives here, not in `SessionInfo`, so that a title change is a frame change and reaches a client through damage like any other.
  * `SessionInfo` describes the session; this describes what the terminal is doing right now.
  */
final case class Modes(
    alternateScreen: Boolean,
    mouseMode: MouseMode,
    mouseFormat: MouseFormat,
    bracketedPaste: Boolean,
    title: String
) derives CanEqual
