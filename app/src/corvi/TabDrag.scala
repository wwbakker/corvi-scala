package corvi

import scalafx.beans.property.ObjectProperty

/** Where a tab drag is. One value for the three flags the strip used to keep in its closure, so the shell can hold it and the strip can
  * read and write it across renders.
  */
private[corvi] enum TabDrag:
  case Idle
  case Pressed(from: WindowId)
  case Carrying(from: WindowId)

  /** A drag that moved a window: the click the platform sends after it is not a selection, so it only clears this state. A state rather
    * than a flag, so a stale "suppress the next click" cannot outlive a gesture.
    */
  case Dropped(from: WindowId)

  /** A gesture is in flight: a render now would rebuild the very nodes it is running on, and the drag would land in nothing.
    */
  def inFlight: Boolean = this match
    case Idle | Dropped(_) => false
    case _                 => true

/** The drag, and whether a render had to wait for it. */
private[corvi] final case class TabDragRender(drag: TabDrag, renderWaiting: Boolean)

/** The drag in flight, and the render that may be waiting for it. FX-thread-only — the strip's handlers write the gesture and read it back,
  * and the shell reads it to decide whether a render can run — so it is a plain property with no listener, like the shell's other holders,
  * and setting it never re-renders anything.
  *
  * `onGestureEnded` is called when a gesture that was in flight finishes — the moment a render that waited can run — which is why the
  * holder is told about it here rather than left for the next state change to notice.
  */
private[corvi] final class TabDragState(onGestureEnded: () => Unit):
  private val current: ObjectProperty[TabDragRender] = ObjectProperty(TabDragRender(TabDrag.Idle, renderWaiting = false))

  def drag: TabDrag = current.value.drag

  def renderWaiting: Boolean = current.value.renderWaiting

  def update(drag: TabDrag): Unit =
    val was = current.value.drag
    current.value = current.value.copy(drag = drag)
    if was.inFlight && !drag.inFlight then onGestureEnded()

  /** A render arrived while a gesture was in flight. */
  def waited(): Unit = current.value = current.value.copy(renderWaiting = true)

  /** A render has run: nothing is waiting any more. */
  def rendered(): Unit = current.value = current.value.copy(renderWaiting = false)
