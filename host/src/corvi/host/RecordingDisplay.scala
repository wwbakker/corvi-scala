package corvi.host

import com.techsenger.jeditermfx.core.emulator.mouse.{MouseFormat, MouseMode}
import com.techsenger.jeditermfx.core.model.TerminalSelection
import com.techsenger.jeditermfx.core.util.TermSize
import com.techsenger.jeditermfx.core.{CursorShape, RequestOrigin, TerminalDisplay}
import java.util.concurrent.atomic.AtomicReference

/** A `TerminalDisplay` that only remembers what the terminal asks for: the host has no screen to draw on, so every callback the UI used to
  * paint with is recorded instead of rendered.
  *
  * It is the one mutable thing in the host that is written by the emulator's fiber and read by whoever asks for a snapshot, so it is a
  * single immutable state value behind an `AtomicReference` — and it is a *plain* Java callback boundary, which is why the update is not an
  * effect.
  */
private[host] final class RecordingDisplay extends TerminalDisplay:
  private val current: AtomicReference[RecordingDisplay.State] = new AtomicReference(RecordingDisplay.initial)

  import RecordingDisplay.State

  def state: State = current.get

  private def update(change: State => State): Unit =
    val _ = current.updateAndGet(state => change(state))

  override def setCursor(x: Int, y: Int): Unit = update(_.copy(cursorX = x, cursorY = y))

  /** The engine passes `null` for "the terminal's default", which the contract spells `None`. */
  override def setCursorShape(shape: CursorShape): Unit =
    update { state =>
      Option(shape) match
        case Some(known) =>
          val (contractShape, blinking) = RecordingDisplay.shapeOf(known)
          state.copy(cursorShape = Some(contractShape), cursorBlinking = blinking)
        case None => state.copy(cursorShape = None, cursorBlinking = false)
    }

  override def setCursorVisible(visible: Boolean): Unit = update(_.copy(cursorVisible = visible))

  override def beep(): Unit = ()

  override def scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int): Unit = ()

  override def useAlternateScreenBuffer(enabled: Boolean): Unit = update(_.copy(alternateScreen = enabled))

  override def getWindowTitle(): String = current.get.title

  override def setWindowTitle(title: String): Unit = update(_.copy(title = title))

  override def getSelection(): TerminalSelection = null

  override def terminalMouseModeSet(mode: MouseMode): Unit = update(_.copy(mouseMode = RecordingDisplay.mouseModeOf(mode)))

  override def setMouseFormat(format: MouseFormat): Unit = update(_.copy(mouseFormat = RecordingDisplay.mouseFormatOf(format)))

  override def ambiguousCharsAreDoubleWidth(): Boolean = false

  override def setBracketedPasteMode(enabled: Boolean): Unit = update(_.copy(bracketedPaste = enabled))

  /** The terminal's own size changes are driven by `HostSession.resize`, not by a display callback. */
  override def onResize(newTermSize: TermSize, origin: RequestOrigin): Unit = ()

private[host] object RecordingDisplay:
  /** Java's enums are singletons, so identity is their equality — which is what the contract's mirror of them compares. The givens say so,
    * for a code base that otherwise compiles with `-language:strictEquality`.
    */
  given CanEqual[CursorShape, CursorShape] = CanEqual.derived
  given CanEqual[MouseMode, MouseMode] = CanEqual.derived
  given CanEqual[MouseFormat, MouseFormat] = CanEqual.derived

  /** Everything the display was told, as one value, in the contract's vocabulary. */
  final case class State(
      cursorX: Int,
      cursorY: Int,
      cursorShape: Option[corvi.contract.CursorShape],
      cursorBlinking: Boolean,
      cursorVisible: Boolean,
      alternateScreen: Boolean,
      mouseMode: corvi.contract.MouseMode,
      mouseFormat: corvi.contract.MouseFormat,
      bracketedPaste: Boolean,
      title: String
  )

  val initial: State = State(
    cursorX = 0,
    cursorY = 0,
    cursorShape = None,
    cursorBlinking = false,
    cursorVisible = true,
    alternateScreen = false,
    mouseMode = corvi.contract.MouseMode.Off,
    mouseFormat = corvi.contract.MouseFormat.X10,
    bracketedPaste = false,
    title = ""
  )

  /** DECSCUSR's six shapes are three shapes times blink, so the mapping is total over all six and the blink travels beside the shape.
    * `Cursor.blinking` is where it goes: a client that blinks its own cursor has to be told whether to.
    */
  def shapeOf(shape: CursorShape): (corvi.contract.CursorShape, Boolean) =
    import com.techsenger.jeditermfx.core.CursorShape as Core
    import corvi.contract.CursorShape as Contract
    val contractShape = shape match
      case Core.BLINK_BLOCK | Core.STEADY_BLOCK               => Contract.Block
      case Core.BLINK_UNDERLINE | Core.STEADY_UNDERLINE       => Contract.Underline
      case Core.BLINK_VERTICAL_BAR | Core.STEADY_VERTICAL_BAR => Contract.VerticalBar
    (contractShape, shape.isBlinking)

  /** Total over the engine's enum, including 1004's focus reports: a `MatchError` here runs inside an emulator callback, where nothing
    * catches it and the session dies.
    */
  def mouseModeOf(mode: MouseMode): corvi.contract.MouseMode =
    import corvi.contract.MouseMode as Contract
    mode match
      case MouseMode.MOUSE_REPORTING_NONE          => Contract.Off
      case MouseMode.MOUSE_REPORTING_NORMAL        => Contract.Click
      case MouseMode.MOUSE_REPORTING_HILITE        => Contract.Highlight
      case MouseMode.MOUSE_REPORTING_BUTTON_MOTION => Contract.ButtonMotion
      case MouseMode.MOUSE_REPORTING_ALL_MOTION    => Contract.AnyMotion
      case MouseMode.MOUSE_REPORTING_FOCUS         => Contract.Focus

  def mouseFormatOf(format: MouseFormat): corvi.contract.MouseFormat =
    import corvi.contract.MouseFormat as Contract
    format match
      case MouseFormat.MOUSE_FORMAT_XTERM     => Contract.X10
      case MouseFormat.MOUSE_FORMAT_XTERM_EXT => Contract.Utf8
      case MouseFormat.MOUSE_FORMAT_URXVT     => Contract.Urxvt
      case MouseFormat.MOUSE_FORMAT_SGR       => Contract.Sgr
