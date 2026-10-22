package corvi.renderer

import javafx.scene.input.{KeyCode, MouseButton}

/** Java's input enums are singletons, so identity is their equality; the givens say so for a code base that compiles with
  * `-language:strictEquality`. They live at package level because the enums' own companions are Java's, which cannot carry them.
  */
given keyCodeCanEqual: CanEqual[KeyCode, KeyCode] = CanEqual.derived
given mouseButtonCanEqual: CanEqual[MouseButton, MouseButton] = CanEqual.derived
