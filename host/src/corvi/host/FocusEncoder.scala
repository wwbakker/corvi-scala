package corvi.host

import corvi.contract.MouseMode

/** Encodes DECSET 1004 focus reports, and only while the program has that mode enabled.
  *
  * The engine models focus tracking as a `MouseMode`, so the display already says whether the program asked for it. A focus event in any
  * other mode is not an error; it is an event the program did not ask to receive.
  */
private[host] object FocusEncoder:
  private val FocusIn: String = "\u001b[I"
  private val FocusOut: String = "\u001b[O"

  def encode(focused: Boolean, mode: MouseMode): Option[String] =
    if mode == MouseMode.Focus then Some(if focused then FocusIn else FocusOut) else None
