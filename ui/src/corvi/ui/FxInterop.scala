package corvi.ui

import javafx.scene.input.{KeyCode, MouseButton}
import javafx.scene.paint.Color

/** Java's and JavaFX's value types compare by identity or value but are not `CanEqual` instances, and this module compiles with
  * `-language:strictEquality`. These say the comparisons the UI actually means.
  */
given keyCodeCanEqual: CanEqual[KeyCode, KeyCode] = CanEqual.derived
given mouseButtonCanEqual: CanEqual[MouseButton, MouseButton] = CanEqual.derived
given colorCanEqual: CanEqual[Color, Color] = CanEqual.derived
