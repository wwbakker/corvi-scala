package corvi.renderer

import corvi.contract.*
import corvi.protocol.*
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.geometry.{Insets, Orientation}
import javafx.scene.control.ScrollBar
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, MouseButton, ScrollEvent}
import javafx.scene.layout.{Background, BackgroundFill, BorderPane, CornerRadii}
import javafx.scene.paint.Color
import javafx.util.Duration
import kyo.*
import kyo.Frame
import scala.util.Try

/** One terminal on screen: the canvas, its scrollbar, and the input that goes back to the host.
  *
  * It is the client's view of a session and nothing more. Pushes arrive from the host's fiber through `FrameSink` and are handed to the FX
  * thread; everything the runtime says back arrives through `SessionEvents`, already on the FX thread. Requests (text, resize, history) go
  * the other way through `TerminalSession`, so this class never enters an effect and the runtime never touches a node.
  *
  * Resizing is debounced here rather than in the host: the host applies what it is told, and the side that owns the window is the only one
  * that knows whether the user is still dragging its edge. 500 ms as upstream's panel does, and 100 ms on the alternate screen, where a
  * full-screen program repainting on every step would be the expensive part.
  */
object TerminalView:

  /** Room kept for the scrollbar whether it is showing or not: a column that appears when history arrives would otherwise re-grid the
    * terminal, and the host would be asked to resize for no reason the user can see. A factory fitting a new session to the window takes
    * the same room off, so the first grid and the view's own agree.
    */
  val scrollbarWidth: Double = 16

final class TerminalView(calls: SessionCalls, val session: SessionId, metrics: TerminalMetrics)
    extends BorderPane
    with FrameSink
    with SessionEvents:

  private val style: TerminalStyle = TerminalStyle.default
  private val canvas: TerminalCanvas = TerminalCanvas(metrics, style)
  private val scrollbar: ScrollBar = new ScrollBar()
  scrollbar.setOrientation(Orientation.VERTICAL)

  private var model: Option[ScreenModel] = None
  private var selection: Option[Selection] = None
  private var failure: Option[String] = None
  private var end: Option[Ended] = None
  private var blinking: Boolean = false
  private var activityListeners: Vector[() => Unit] = Vector.empty
  private var endedListeners: Vector[Ended => Unit] = Vector.empty
  private var blinkOverride: Option[Boolean] = None
  private var liveBefore: Boolean = true

  /** Damage that arrived before the snapshot did: the host's first push can beat its own attach reply across two fibers, and the delta is
    * computed against the snapshot, so it is exactly what this view needs once seeded rather than something to drop.
    */
  private var pendingDamage: Vector[Damage] = Vector.empty

  /** How much damage a view that has not attached yet may hold. The host can push before the attach answer lands, so the deltas are kept
    * until the seed arrives — but a view whose attach never completes must not grow the window's memory with a session it will never show.
    * The newest deltas win, and dropping the oldest is lossy: a row whose only change fell outside the window keeps the seed's older value
    * until it changes again, so a view attaching after a gap of roughly two seconds (128 ticks at 60 Hz) may show stale cells in rows that
    * are otherwise idle.
    */
  private val maxPendingDamage: Int = 128

  /** How far one wheel notch moves the view. */
  private val stepsPerWheel: Int = 3

  /** Requests the host takes a while to answer are coalesced: a resize waits for the drag to stop, and the timer's delay follows the mode
    * the frame is in.
    */
  private val resizeTimer = PauseTransition(Duration.millis(500))

  locally:
    setFocusTraversable(true)
    setCenter(canvas)
    setRight(scrollbar)
    // The view itself is the terminal's colour, so the pixels between the grid and the window edge are not the toolkit's theme.
    setBackground(Background(BackgroundFill(Color.rgb(13, 17, 23), CornerRadii.EMPTY, Insets.EMPTY)))
    scrollbar.setVisible(false)
    // The scrollbar runs the other way round from the model: its bottom is the live grid, because that is where a scrollbar's "end" is.
    val _ = scrollbar.valueProperty().addListener((_, _, value) => scrollTo(math.round(scrollbar.getMax() - value.doubleValue()).toInt))
    val _ = focusedProperty().addListener((_, _, focused) => canvas.setBaseFocus(focused))
    val _ = widthProperty().addListener((_, _, _) => scheduleResize())
    val _ = heightProperty().addListener((_, _, _) => scheduleResize())
    // Printable characters come from KEY_TYPED; the keys that are not characters come from KEY_PRESSED, because KEY_TYPED reports them as
    // control codes the shell would not recognise (`\b` where macOS's `stty erase` expects `^?`). A Command or Control shortcut's typed
    // companion is ignored: the platform sends a KEY_TYPED for the same keystroke even when the window took it, and \u2318C must not also
    // type a "c". Option is deliberately not in that test: an Option-composed character *is* this event, with Alt still down, and it is the
    // text the shell should receive. Arrows are still not forwarded: they need the host's mode-dependent key encoding.
    val _ = setOnKeyTyped { event =>
      val typed = event.getCharacter
      val shortcut = event.isMetaDown || event.isControlDown
      if !shortcut && typed.nonEmpty && typed.charAt(0) >= ' ' && typed.charAt(0) != 0x7f.toChar then calls.text(session, typed)
    }
    val _ = setOnKeyPressed { event =>
      val code = event.getCode
      val meta = event.isMetaDown
      val control = event.isControlDown
      val shift = event.isShiftDown
      val alt = event.isAltDown
      // Copy and paste are the window's own chords, decided before anything is sent: a terminal keeps Ctrl+C for SIGINT, so copy is
      // Cmd+C (or Ctrl+Shift+C as the alias). Control codes then carry every other Control chord to the shell.
      if ControlKeys.isCopy(code, meta, control, shift, alt) then
        event.consume()
        val _ = copySelection()
      else if ControlKeys.isPaste(code, meta, control, shift, alt) then
        event.consume()
        pasteClipboard()
      else if control && !meta && !alt then
        ControlKeys.controlByte(code, shift) match
          case Some(bytes) =>
            event.consume()
            calls.text(session, bytes)
          case None => editingKey(code, event)
      else editingKey(code, event)
    }
    val _ = setOnMousePressed(event =>
      if event.getButton == MouseButton.PRIMARY then
        requestFocus()
        selection = Some(Selection.at(canvas.positionAt(event.getX, event.getY)))
        redraw()
    )
    val _ = setOnMouseDragged(event =>
      selection = selection.map(current => current.draggedTo(canvas.positionAt(event.getX, event.getY)))
      redraw()
    )
    val _ = setOnScroll((event: ScrollEvent) => scrollBy(if event.getDeltaY > 0 then stepsPerWheel else -stepsPerWheel))
    resizeTimer.setOnFinished(_ => requestGridForSize())

  /** Joins the host's session: ask for the snapshot that seeds this view. Called after the sink has plugged it, so a delta the host sends
    * before the snapshot cannot slip past both.
    */
  def attach(): Unit = calls.attach(session, this)

  def screen: Option[ScreenModel] = model

  def frame: Option[corvi.contract.Frame] = model.map(_.frame)

  /** How many deltas are waiting for the attach; for the renderer's own tests. */
  private[corvi] def pendingDamageCount: Int = pendingDamage.size

  def ended: Option[Ended] = end

  def error: Option[String] = failure

  /** Output arrived: what the shell's bell is made of. Called on the FX thread, after the frame is applied. */
  def onOutput(listener: () => Unit): Unit =
    activityListeners = activityListeners :+ listener

  /** The shell behind this view ended. */
  def onSessionEnded(listener: Ended => Unit): Unit =
    endedListeners = endedListeners :+ listener

  def selectedText: Option[String] = selection.filterNot(_.isEmpty).map(cells => text(cells))

  /** Types without a keyboard, which is how a harness drives a session. Real input arrives through the key handler below and takes the same
    * path.
    */
  def typeText(text: String): Unit = calls.text(session, text)

  def select(from: CellPosition, to: CellPosition): Unit =
    selection = Some(Selection(from, to))
    redraw()

  def clearSelection(): Unit =
    selection = None
    redraw()

  /** The selection as text, and a copy of it on the clipboard. A clipboard that refuses (no display, no permission) is reported, not
    * thrown.
    */
  def copySelection(): String =
    val copied = selectedText.getOrElse("")
    if copied.nonEmpty then
      Try {
        val content = ClipboardContent()
        content.putString(copied)
        Clipboard.getSystemClipboard().setContent(content)
      }.failed.foreach(error => report(s"the clipboard refused the selection: $error"))
    copied

  /** Types the clipboard at the shell. An empty or unreadable clipboard is not a keystroke, so it sends nothing. */
  def pasteClipboard(): Unit =
    val pasted = Try(Option(Clipboard.getSystemClipboard().getString)).toOption.flatten.getOrElse("")
    if pasted.nonEmpty then calls.text(session, pasted)

  /** The keys that are sent as themselves rather than as a character: Backspace is `^?` (what macOS's `stty erase` expects), and Enter, Tab
    * and Escape are the bytes a terminal sends for them.
    */
  private def editingKey(code: KeyCode, event: javafx.scene.input.KeyEvent): Unit =
    val bytes = code match
      case KeyCode.BACK_SPACE => Some("\u007f")
      case KeyCode.ENTER      => Some("\r")
      case KeyCode.TAB        => Some("\t")
      case KeyCode.ESCAPE     => Some("\u001b")
      case _                  => None
    bytes.foreach { sent =>
      event.consume()
      calls.text(session, sent)
    }

  private def report(message: String): Unit = java.lang.System.err.println(s"corvi renderer: $message")

  /** Turns cursor blinking off (or on) for good: the frame asks for blinking on every damage and this must still win, or a capture taken
    * after any output would be a coin flip.
    */
  def setCursorBlinking(enabled: Boolean): Unit =
    blinkOverride = Some(enabled)
    blinking = enabled
    canvas.setBlinking(enabled)

  /** Focus, for a harness without a window manager: focused draws a filled cursor, unfocused a hollow one. */
  def showFocus(focused: Boolean): Unit = canvas.showFocus(focused)

  /** Asks the host to close this session, which is what closing a tab does. The host then reports the end through the usual push. */
  def closeSession(): Unit = calls.close(session)

  /** Releases what this view owns: the resize timer waits on the FX thread and the client runtime parks on a queue, and both would outlive
    * a closed tab otherwise.
    */
  def dispose(): Unit = resizeTimer.stop()

  /** Moves the viewport by `delta` rows: positive goes further back into history, negative towards the live grid. Reaching the bottom is
    * what returns to the live grid; anything above it is history the client asks the host for.
    */
  def scrollBy(delta: Int): Unit =
    model.foreach { current =>
      val next = current.scrolledBy(delta)
      model = Some(next)
      // A selection is in viewport rows, so it stops meaning anything the moment they move: the terminal this replaces clears it too.
      if next.scrollOffset != current.scrollOffset then selection = None
      if next.missingHistory > 0 then calls.history(session, from = 1, count = math.min(next.maxScroll, next.scrollOffset + next.rows))
      updateScrollbar(next)
      redraw()
    }

  def scrollTo(offset: Int): Unit =
    model.foreach { current =>
      if current.scrollOffset != offset then scrollBy(offset - current.scrollOffset)
    }

  // --- the runtime's side ---------------------------------------------------------------------------------------------------------

  override def damage(id: SessionId, damage: Damage): Unit < Sync =
    pushDamage(id, damage)
    Sync.defer(())

  override def ended(id: SessionId, ended: Ended): Unit < Sync =
    pushEnded(id, ended)
    Sync.defer(())

  /** A push, from whichever thread the host is on; the view hops to the FX thread itself. A sink shared by several tabs calls this
    * directly, because a session's damage goes only to the view that shows that session.
    */
  def pushDamage(id: SessionId, damage: Damage): Unit =
    if id == session then
      val _ = Platform.runLater(() => applyDamage(damage))

  def pushEnded(id: SessionId, ended: Ended): Unit =
    if id == session then
      val _ = Platform.runLater(() => acceptEnd(ended))

  override def sessionEnded(ended: Ended): Unit = acceptEnd(ended)

  override def failed(message: String): Unit =
    failure = Some(message)
    java.lang.System.err.println(s"corvi renderer: $message")

  override def attached(snapshot: Snapshot): Unit =
    val next = ScreenModel.from(snapshot)
    model = Some(next)
    applyBackground(next.frame.theme)
    updateScrollbar(next)
    val pending = pendingDamage
    pendingDamage = Vector.empty
    pending.foreach(applyToModel)
    redraw()
    scheduleResize()

  override def history(rows: Vector[Row]): Unit =
    model.foreach { current =>
      val next = current.withHistory(rows)
      model = Some(next)
      updateScrollbar(next)
      redraw()
    }

  /** The view's own background is the session's, so the pixels between the canvas and the window edge are the terminal's and not Modena's.
    */
  private def applyBackground(theme: TerminalTheme): Unit =
    setBackground(Background(BackgroundFill(TerminalStyle.toColor(theme.background), CornerRadii.EMPTY, Insets.EMPTY)))

  private def acceptEnd(ended: Ended): Unit =
    end = Some(ended)
    endedListeners.foreach(listener => listener(ended))
    redraw()

  private def applyDamage(damage: Damage): Unit =
    if !damage.isEmpty then activityListeners.foreach(listener => listener())
    model match
      case Some(_) => applyToModel(damage); redraw()
      case None    => pendingDamage = (pendingDamage :+ damage).takeRight(maxPendingDamage)

  private def applyToModel(damage: Damage): Unit =
    model.foreach { current =>
      val next = current.withFrame(current.frame.apply(damage))
      model = Some(next)
      updateScrollbar(next)
    }

  // --- painting and layout ---------------------------------------------------------------------------------------------------------

  private def redraw(): Unit =
    model.foreach { current =>
      // A new frame while the user is reading history keeps the viewport where it is and marks that output arrived.
      if current.isLive != liveBefore then
        liveBefore = current.isLive
        updateScrollbar(current)
      val shouldBlink = blinkOverride.getOrElse(current.frame.cursor.blinking)
      if shouldBlink != blinking then
        blinking = shouldBlink
        canvas.setBlinking(shouldBlink)
      canvas.show(current, selection)
    }

  private def updateScrollbar(model: ScreenModel): Unit =
    scrollbar.setVisible(model.maxScroll > 0)
    if model.maxScroll > 0 then
      scrollbar.setMin(0)
      scrollbar.setMax(model.maxScroll)
      scrollbar.setVisibleAmount(math.max(1, model.rows * model.maxScroll / math.max(1, model.maxScroll + model.rows)))
      scrollbar.setValue(model.maxScroll - model.scrollOffset)

  /** The grid the view has room for, in cells. */
  private def gridForSize(): (Int, Int) =
    val layout = metrics.layout
    (layout.colsFor(math.max(0, getWidth) - TerminalView.scrollbarWidth), layout.rowsFor(math.max(0, getHeight)))

  private def scheduleResize(): Unit =
    if model.isDefined then
      resizeTimer.setDuration(Duration.millis(if model.exists(_.frame.modes.alternateScreen) then 100 else 500))
      resizeTimer.playFromStart()

  /** A view that has not been laid out yet has no size, and asking the host to fit a grid to nothing would shrink its shell to a corner.
    * Only a view with room for the canvas and the scrollbar asks for a grid.
    */
  private def requestGridForSize(): Unit =
    model.foreach { current =>
      if getWidth > TerminalView.scrollbarWidth && getHeight > 0 then
        val (cols, rows) = gridForSize()
        if cols != current.cols || rows != current.rows then calls.resize(session, TermSize(cols = cols, rows = rows))
    }

  private def text(selection: Selection): String = model.map(_.text(selection)).getOrElse("")
