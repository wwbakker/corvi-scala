package corvi.renderer

import corvi.contract.*
import corvi.protocol.*
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.geometry.{Insets, Orientation, Point2D}
import javafx.scene.control.ScrollBar
import javafx.scene.input.{
  Clipboard,
  ClipboardContent,
  InputMethodEvent,
  InputMethodRequests,
  KeyCode,
  MouseButton as JfxMouseButton,
  ScrollEvent
}
import javafx.scene.layout.{Background, BackgroundFill, BorderPane, CornerRadii}
import javafx.scene.paint.Color
import javafx.util.Duration
import kyo.*
import kyo.Frame
import scala.jdk.CollectionConverters.*
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
final class TerminalView(calls: SessionCalls, val session: SessionId, metrics: TerminalMetrics, altMode: AltMode = AltMode.configured)
    extends BorderPane
    with FrameSink
    with SessionEvents:

  private val style: TerminalStyle = TerminalStyle.default
  private var currentMetrics: TerminalMetrics = metrics
  private var canvas: TerminalCanvas = TerminalCanvas(currentMetrics, style)
  private val scrollbar: ScrollBar = new ScrollBar()
  private[corvi] val findBar: FindBar = FindBar()
  scrollbar.setOrientation(Orientation.VERTICAL)

  private var model: Option[ScreenModel] = None
  private var selection: Option[Selection] = None
  private var pressedTerminalButton: Option[MouseButton] = None
  private var failure: Option[String] = None
  private var end: Option[Ended] = None
  private var blinking: Boolean = false
  private var activityListeners: Vector[() => Unit] = Vector.empty
  private var endedListeners: Vector[Ended => Unit] = Vector.empty
  private var blinkOverride: Option[Boolean] = None
  private var focusOverride: Option[Boolean] = None
  private var liveBefore: Boolean = true

  /** The IME's composed text. It lives here and on the canvas, never in the host's grid; committing it sends only the committed text. */
  private var preedit: Option[Preedit] = None

  private var findMatches: Vector[TerminalSearch.Match] = Vector.empty
  private var findIndex: Int = -1
  private var findHistoryRequestedFor: Option[Int] = None

  /** An Alt+printable press was sent as a Meta key, so its typed companion is not a character. It is cleared by that companion, or by the
    * next press when the platform sends none.
    */
  private var metaKeyTaken: Boolean = false

  /** Damage that arrived before the snapshot did: the host's first push can beat its own attach reply across two fibers, and the delta is
    * computed against the snapshot, so it is exactly what this view needs once seeded rather than something to drop.
    */
  private var pendingDamage: Vector[Damage] = Vector.empty

  /** The grid the last resize asked for, until the host's frame confirms it: the answer is a round trip away and the view must not ask
    * twice.
    */
  private var lastRequested: Option[TermSize] = None

  /** The frame size a resize was last scheduled for because it did not match the room, so repeated damage for the same mismatch does not
    * rearm the debounce and defer the request forever.
    */
  private var mismatchScheduled: Option[TermSize] = None

  /** The history page the host has been asked for and not answered yet. Output arriving while the reader is scrolled back can make the
    * viewport need rows it does not have on every damage tick; this keeps that from becoming one request per frame.
    */
  private var historyRequest: Option[(Int, Int)] = None

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

  /** The frame's grid does not get a say in the view's minimum size. A Canvas is not resizable, so the BorderPane would otherwise derive
    * its min (and the window's) from the canvas, which grows with every frame the host applies — and a window that cannot shrink below its
    * own canvas ratchets: shrink a step, get a smaller grid, a larger canvas minimum, and a window that grows back to fit it. The view
    * fills whatever the parent gives it; the canvas is a drawing sized to the frame.
    */
  locally:
    setMinSize(0, 0)

  locally:
    setFocusTraversable(true)
    setCenter(canvas)
    setTop(findBar)
    setRight(scrollbar)
    // The view itself is the terminal's colour, so the pixels between the grid and the window edge are not the toolkit's theme.
    setBackground(Background(BackgroundFill(Color.rgb(13, 17, 23), CornerRadii.EMPTY, Insets.EMPTY)))
    scrollbar.setVisible(false)
    // A hidden scrollbar must not hold a column: managed follows visible, so the content area the grid is computed from is the same area the
    // layout gives the canvas.
    scrollbar.setManaged(false)
    val _ = scrollbar.managedProperty().bind(scrollbar.visibleProperty())
    // The scrollbar runs the other way round from the model: its bottom is the live grid, because that is where a scrollbar's "end" is.
    val _ = scrollbar.valueProperty().addListener((_, _, value) => scrollTo(math.round(scrollbar.getMax() - value.doubleValue()).toInt))
    val _ = focusedProperty().addListener((_, _, focused) => focusChanged(focused))
    val _ = widthProperty().addListener((_, _, _) => scheduleResize())
    val _ = heightProperty().addListener((_, _, _) => scheduleResize())
    val _ = findBar.field.textProperty().addListener((_, _, _) => runFind())
    findBar.next.setOnAction(_ => nextFind())
    findBar.previous.setOnAction(_ => previousFind())
    findBar.close.setOnAction(_ => closeFind())
    val _ = findBar.field.setOnKeyPressed { event =>
      event.getCode match
        case KeyCode.ESCAPE =>
          event.consume()
          closeFind()
        case KeyCode.ENTER =>
          event.consume()
          if event.isShiftDown then previousFind() else nextFind()
        case _ => ()
    }
    // Printable characters come from KEY_TYPED; the keys that are not characters come from KEY_PRESSED, because KEY_TYPED reports them as
    // control codes the shell would not recognise (`\b` where macOS's `stty erase` expects `^?`). A Command or Control shortcut's typed
    // companion is ignored: the platform sends a KEY_TYPED for the same keystroke even when the window took it, and \u2318C must not also
    // type a "c". Option is only text in Compose mode: in Meta/Escape, the pressed key is sent through the key route and the platform's
    // composed companion is swallowed.
    val _ = setOnKeyTyped { event =>
      val typed = event.getCharacter
      val shortcut = event.isMetaDown || event.isControlDown
      if end.isEmpty then
        if event.isAltDown && altMode != AltMode.Compose then
          // The press normally took the key. If this platform sent no press the typed character is still the key, so it becomes printable
          // input rather than falling through as composed text. A Control/Meta companion is a shortcut, not a Meta key: Ctrl+Alt and
          // Cmd+Option must not leak an escape-prefixed letter, and AltGr's Control+Alt report must not either.
          if !metaKeyTaken && !shortcut && isTypedText(typed) then
            calls.key(session, KeyInput(TerminalKey.Printable(typed), keyModifiers(event)))
          metaKeyTaken = false
          event.consume()
        else if !shortcut && isTypedText(typed) then calls.text(session, typed)
    }
    val _ = setOnKeyPressed { event =>
      val code = event.getCode
      val meta = event.isMetaDown
      val control = event.isControlDown
      val shift = event.isShiftDown
      val alt = event.isAltDown
      metaKeyTaken = false
      if findBar.isOpen && code == KeyCode.ESCAPE then
        event.consume()
        closeFind()
      // Copy and paste are the window's own chords, decided before anything is sent: a terminal keeps Ctrl+C for SIGINT, so copy is
      // Cmd+C (or Ctrl+Shift+C as the alias). Control codes then carry every other Control chord to the shell.
      else if ControlKeys.isCopy(code, meta, control, shift, alt) then
        event.consume()
        val _ = copySelection()
      else if end.nonEmpty then
        // The screen is still selectable, but a finished shell takes no input. Swallowing the key also keeps a late key from becoming
        // a `session has ended` error for every keystroke.
        event.consume()
      else if ControlKeys.isPaste(code, meta, control, shift, alt) then
        event.consume()
        pasteClipboard()
      else if control && !meta && !alt then
        ControlKeys.controlByte(code, shift) match
          case Some(bytes) =>
            event.consume()
            calls.text(session, bytes)
          case None => nonControlKey(code, event)
      else nonControlKey(code, event)
    }
    // IME composition arrives separately from KEY_TYPED. The committed text is ordinary input; the composed text is only painted over
    // the cursor cell until the platform commits or cancels it.
    val _ = setOnInputMethodTextChanged { event =>
      if end.isEmpty then inputMethodChanged(event)
    }
    setInputMethodRequests(new InputMethodRequests:
      override def getTextLocation(offset: Int): Point2D = textLocation()
      // The renderer does not own an editable text buffer to map a screen point back into; the platform only needs a location for the
      // candidate window, which `getTextLocation` supplies.
      override def getLocationOffset(x: Int, y: Int): Int = 0
      // The host owns the grid: undoing committed text here would be a second input path.
      override def cancelLatestCommittedText(): Unit = ()
      override def getSelectedText(): String = null)
    val _ = setOnMousePressed(event =>
      requestFocus()
      terminalButton(event.getButton) match
        case Some(button) if reportingMode.nonEmpty && !forcesSelection(event) && insideCanvas(event.getX, event.getY) =>
          pressedTerminalButton = Some(button)
          sendMouse(event.getX, event.getY, Some(button), MouseAction.Press, event)
          event.consume()
        case _ if event.getButton == JfxMouseButton.PRIMARY =>
          pressedTerminalButton = None
          selection = Some(Selection.at(canvas.positionAt(event.getX, event.getY)))
          redraw()
        case _ => ()
    )
    val _ = setOnMouseDragged(event =>
      if reportingMode.nonEmpty && forcesSelection(event) then
        selection = selection.map(current => current.draggedTo(canvas.positionAt(event.getX, event.getY)))
        redraw()
      else
        reportingMode match
          case Some(MouseMode.AnyMotion) if insideCanvas(event.getX, event.getY) =>
            sendMouse(event.getX, event.getY, pressedTerminalButton, MouseAction.Move, event)
            event.consume()
          case Some(MouseMode.ButtonMotion) if insideCanvas(event.getX, event.getY) =>
            pressedTerminalButton.foreach { button =>
              sendMouse(event.getX, event.getY, Some(button), MouseAction.Move, event)
              event.consume()
            }
          case Some(_) => ()
          case None    =>
            selection = selection.map(current => current.draggedTo(canvas.positionAt(event.getX, event.getY)))
            redraw()
    )
    val _ = setOnMouseMoved(event =>
      reportingMode match
        case Some(MouseMode.AnyMotion)
            if pressedTerminalButton.isEmpty && !forcesSelection(event) &&
              insideCanvas(event.getX, event.getY) =>
          sendMouse(event.getX, event.getY, None, MouseAction.Move, event)
          event.consume()
        case _ => ()
    )
    val _ = setOnMouseReleased(event =>
      pressedTerminalButton match
        case Some(button) =>
          // Report the release even when the pointer left the grid: a release outside the canvas must not leave the program believing
          // the button is still down.
          sendMouse(event.getX, event.getY, Some(button), MouseAction.Release, event)
          pressedTerminalButton = None
          event.consume()
        case None =>
          if reportingMode.isEmpty && event.getButton == JfxMouseButton.PRIMARY then redraw()
    )
    val _ = setOnScroll((event: ScrollEvent) =>
      reportingMode match
        case Some(_) if !forcesSelection(event) && insideCanvas(event.getX, event.getY) =>
          val button =
            if event.getDeltaX != 0 then Some(if event.getDeltaX > 0 then MouseButton.WheelRight else MouseButton.WheelLeft)
            else if event.getDeltaY != 0 then Some(if event.getDeltaY > 0 then MouseButton.WheelUp else MouseButton.WheelDown)
            else None
          button.foreach { wheel =>
            sendMouse(event.getX, event.getY, Some(wheel), MouseAction.Press, event)
            event.consume()
          }
        case _ =>
          // A horizontal-only wheel event has nothing to scroll in this viewport; do not treat its zero deltaY as "down".
          if event.getDeltaY != 0 then scrollBy(if event.getDeltaY > 0 then stepsPerWheel else -stepsPerWheel)
    )
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
  def typeText(text: String): Unit =
    if end.isEmpty then calls.text(session, text)

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

  /** Types the clipboard at the shell. An empty or unreadable clipboard is not a keystroke, so it sends nothing. When the program enabled
    * bracketed paste, the text is wrapped and otherwise untouched; without it, the previous plain-text behaviour is the fallback.
    */
  def pasteClipboard(): Unit =
    val pasted = Try(Option(Clipboard.getSystemClipboard().getString)).toOption.flatten.getOrElse("")
    if end.isEmpty && pasted.nonEmpty then
      val wrapped =
        if model.exists(_.frame.modes.bracketedPaste) then s"\u001b[200~$pasted\u001b[201~"
        else pasted
      calls.text(session, wrapped)

  /** The focus property's listener and the deterministic test seam both land here, so a harness can drive the same path without relying on
    * a window manager to move the keyboard.
    */
  private[corvi] def focusChanged(focused: Boolean): Unit =
    canvas.setBaseFocus(focused)
    if !focused then setPreedit(None)
    if end.isEmpty && model.exists(_.frame.modes.focusReporting) then calls.focus(session, focused)

  /** The composed text currently painted over the cursor; for the IME tests and probes. */
  private[corvi] def composition: Option[Preedit] = preedit

  private def inputMethodChanged(event: InputMethodEvent): Unit =
    val committed = Option(event.getCommitted).getOrElse("")
    val composed = Option(event.getComposed).map(_.asScala.map(_.getText).mkString).getOrElse("")
    if committed.nonEmpty then calls.text(session, committed)
    if composed.nonEmpty then setPreedit(Some(Preedit(composed, event.getCaretPosition)))
    else setPreedit(None)

  private def setPreedit(value: Option[Preedit]): Unit =
    preedit = value
    canvas.setPreedit(value)

  /** The screen location of the cursor cell's lower edge, which is where the platform should place its candidate window.
    *
    * JavaFX 26 dereferences this result, so it must never be null: an unattached view, a scrolled-back frame or a node that is not in a
    * showing window gets `Point2D.ZERO` instead.
    */
  private def textLocation(): Point2D =
    model.filter(_.isLive) match
      case Some(current) =>
        val cursor = current.frame.cursor
        Option(canvas.localToScreen(currentMetrics.layout.cellX(cursor.x), currentMetrics.layout.cellY(cursor.y + 1)))
          .getOrElse(Point2D.ZERO)
      case None => Point2D.ZERO

  /** A key the Control-byte table did not consume: a named key goes to the host's encoder, and Alt+printable becomes a Meta key in the
    * modes that ask for it. The unmodified editing keys keep their direct bytes, which is the one path for them.
    */
  private def nonControlKey(code: KeyCode, event: javafx.scene.input.KeyEvent): Unit =
    KeyMapping.terminalKey(code) match
      case Some(key) if !KeyMapping.unmodifiedEditingKey(code) || modified(event) => sendKey(key, event)
      case Some(_)                                                                => editingKey(code, event)
      case None                                                                   =>
        if altMode != AltMode.Compose && event.isAltDown && !event.isControlDown && !event.isMetaDown then
          KeyMapping.printable(code, event.isShiftDown) match
            case Some(text) =>
              metaKeyTaken = true
              event.consume()
              calls.key(session, KeyInput(TerminalKey.Printable(text), keyModifiers(event)))
            case None => editingKey(code, event)
        else editingKey(code, event)

  private def sendKey(key: TerminalKey, event: javafx.scene.input.KeyEvent): Unit =
    event.consume()
    calls.key(session, KeyInput(key, keyModifiers(event)))

  private def keyModifiers(event: javafx.scene.input.KeyEvent): KeyModifiers =
    KeyModifiers(
      shift = event.isShiftDown,
      alt = event.isAltDown,
      control = event.isControlDown,
      meta = event.isMetaDown
    )

  private def modified(event: javafx.scene.input.KeyEvent): Boolean =
    event.isShiftDown || event.isAltDown || event.isControlDown || event.isMetaDown

  private def isTypedText(typed: String): Boolean =
    typed.nonEmpty && typed.charAt(0) >= ' ' && typed.charAt(0) != 0x7f.toChar

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
  def showFocus(focused: Boolean): Unit =
    focusOverride = Some(focused)
    canvas.showFocus(focused)

  /** Replaces the metrics and canvas, keeping the model, selection and viewport, then asks the host for the new grid. Runs on the FX
    * thread; a caller from another thread is marshalled here.
    */
  def setFontSize(size: Double): Unit =
    if size != currentMetrics.font.getSize then
      if Platform.isFxApplicationThread then replaceCanvas(TerminalMetrics.monospaced(size))
      else Platform.runLater(() => setFontSize(size))

  /** The current metrics, for the font-size tests. */
  private[corvi] def fontMetrics: TerminalMetrics = currentMetrics

  private def replaceCanvas(nextMetrics: TerminalMetrics): Unit =
    val previous = canvas
    val replacement = TerminalCanvas(nextMetrics, style)
    currentMetrics = nextMetrics
    model.foreach(current => replacement.show(current, selection))
    blinkOverride.foreach(replacement.setBlinking)
    replacement.setPreedit(preedit)
    focusOverride.fold(replacement.setBaseFocus(isFocused))(replacement.showFocus)
    replacement.setFind(findMatches, Option.when(findIndex >= 0)(findIndex))
    previous.dispose()
    canvas = replacement
    setCenter(replacement)
    resizeTimer.stop()
    lastRequested = None
    mismatchScheduled = None
    requestGridForSize()
    requestLayout()

  /** Opens the find bar and searches the loaded history + live grid. A first search asks for all available history. */
  def openFind(): Unit =
    if end.isEmpty then
      findHistoryRequestedFor = None
      findBar.open()
      runFind()
      scheduleResize()

  /** Hides the find bar and drops its matches. This is what leaving a tab and ending a session do. */
  def clearFind(): Unit =
    val wasOpen = findBar.isOpen
    findMatches = Vector.empty
    findIndex = -1
    findHistoryRequestedFor = None
    findBar.hide()
    findBar.setStatusText("")
    canvas.setFind(Vector.empty, None)
    if wasOpen then scheduleResize()

  private def closeFind(): Unit =
    clearFind()
    requestFocus()

  private def runFind(preserveIndex: Boolean = false): Unit =
    if findBar.isOpen then
      val previous = findIndex
      model.foreach(ensureFindHistory)
      findMatches = model.map(current => TerminalSearch.search(current, findBar.query)).getOrElse(Vector.empty)
      findIndex =
        if findMatches.isEmpty then -1
        else if preserveIndex && previous >= 0 then math.min(previous, findMatches.size - 1)
        else 0
      // A text change starts at the first match; a history update keeps the reader where they were unless there was no match before.
      applyFind(scroll = !preserveIndex || previous < 0)

  private def nextFind(): Unit =
    if findMatches.nonEmpty then
      findIndex = (findIndex + 1) % findMatches.size
      applyFind(scroll = true)

  private def previousFind(): Unit =
    if findMatches.nonEmpty then
      findIndex = Math.floorMod(findIndex - 1, findMatches.size)
      applyFind(scroll = true)

  /** The current matches and selected index, for the find tests and probes. */
  private[corvi] def findState: (Vector[TerminalSearch.Match], Option[Int]) =
    (findMatches, Option.when(findIndex >= 0)(findIndex))

  private def applyFind(scroll: Boolean): Unit =
    findBar.setStatusText(
      if findBar.query.isEmpty then ""
      else if findMatches.isEmpty then "0/0"
      else s"${findIndex + 1}/${findMatches.size}"
    )
    canvas.setFind(findMatches, Option.when(findIndex >= 0)(findIndex))
    if scroll && findIndex >= 0 then model.foreach(current => scrollTo(TerminalSearch.scrollOffsetFor(current, findMatches(findIndex).row)))

  /** A search needs the whole loaded history, not only the page the viewport happens to have asked for. */
  private def ensureFindHistory(current: ScreenModel): Unit =
    val wanted = current.maxScroll
    if wanted > current.history.size && !findHistoryRequestedFor.contains(wanted) then
      findHistoryRequestedFor = Some(wanted)
      calls.history(session, 1, wanted)

  /** Asks the host to close this session, which is what closing a tab does. The host then reports the end through the usual push. */
  def closeSession(): Unit = calls.close(session)

  /** Releases what this view owns: the resize timer waits on the FX thread and the client runtime parks on a queue, and both would outlive
    * a closed tab otherwise.
    */
  def dispose(): Unit =
    clearFind()
    resizeTimer.stop()
    setPreedit(None)
    canvas.dispose()

  /** Moves the viewport by `delta` rows: positive goes further back into history, negative towards the live grid. Reaching the bottom is
    * what returns to the live grid; anything above it is history the client asks the host for.
    */
  def scrollBy(delta: Int): Unit =
    model.foreach { current =>
      val next = current.scrolledBy(delta)
      model = Some(next)
      // A selection is in viewport rows, so it stops meaning anything the moment they move: the terminal this replaces clears it too.
      if next.scrollOffset != current.scrollOffset then selection = None
      requestMissingHistory(next)
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
    lastRequested = None
    historyRequest = None
    model = Some(next)
    applyBackground(next.frame.theme)
    updateScrollbar(next)
    val pending = pendingDamage
    pendingDamage = Vector.empty
    pending.foreach(applyToModel)
    model.foreach(requestMissingHistory)
    redraw()
    noteFrame()

  override def history(rows: Vector[Row]): Unit =
    model.foreach { current =>
      val next = current.withHistory(rows)
      model = Some(next)
      historyRequest = None
      updateScrollbar(next)
      redraw()
      if findBar.isOpen then runFind(preserveIndex = true)
    }

  /** The frame the host applied, after the model has it.
    *
    * The request that produced it is no longer in flight. A frame that does not match the room the view has means a request was overtaken —
    * resize to B, back to A before B's answer landed: the A tick sends nothing because the frame already reads A, then B's frame arrives
    * and nothing would ask again — so a mismatch arms the debounce once more. `mismatchScheduled` keeps repeated damage for the same
    * mismatch from restarting the timer, which would defer the request through continuous output.
    *
    * A host that clamps a size (1x1 becomes its minimum) leaves `lastRequested` on that grid, so the retry it triggers is suppressed as
    * already-asked rather than spinning. Any real grid a user asks for is sent.
    */
  private def noteFrame(): Unit =
    model.foreach { current =>
      val size = current.frame.size
      if lastRequested.contains(size) then lastRequested = None
      if size == contentGrid then mismatchScheduled = None
      else if !mismatchScheduled.contains(size) then
        mismatchScheduled = Some(size)
        scheduleResize()
    }

  /** The view's own background is the session's, so the pixels between the canvas and the window edge are the terminal's and not Modena's.
    */
  private def applyBackground(theme: TerminalTheme): Unit =
    setBackground(Background(BackgroundFill(TerminalStyle.toColor(theme.background), CornerRadii.EMPTY, Insets.EMPTY)))

  private def acceptEnd(ended: Ended): Unit =
    end = Some(ended)
    setPreedit(None)
    clearFind()
    // A session that has ended takes no resizes — the host refuses them — so the view stops asking instead of logging a refusal per tick.
    resizeTimer.stop()
    endedListeners.foreach(listener => listener(ended))
    redraw()

  private def applyDamage(damage: Damage): Unit =
    if !damage.isEmpty then activityListeners.foreach(listener => listener())
    model match
      case Some(_) =>
        applyToModel(damage)
        model.foreach(requestMissingHistory)
        redraw()
        noteFrame()
      case None => pendingDamage = (pendingDamage :+ damage).takeRight(maxPendingDamage)

  private def applyToModel(damage: Damage): Unit =
    model.foreach { current =>
      val next = current.withFrame(current.frame.apply(damage))
      model = Some(next)
      updateScrollbar(next)
    }

  // --- pointer encoding -------------------------------------------------------------------------------------------------------------

  /** The tracking mode the program asked for, or `None` when the mouse belongs to the view. Scrollback has no live grid to report cells
    * against, so the wheel and drag stay local there. `Focus` is a focus-report mode, not a pointer mode.
    */
  private def reportingMode: Option[MouseMode] =
    if end.nonEmpty then None
    else
      model
        .filter(_.isLive)
        .map(_.frame.modes.mouseMode)
        .filter(mode => mode != MouseMode.Off && mode != MouseMode.Focus)

  /** A modifier-held drag is a selection, the way a terminal's own override works: it keeps text selectable while a full-screen program has
    * mouse reporting enabled.
    */
  private def forcesSelection(event: javafx.scene.input.MouseEvent): Boolean =
    event.isShiftDown || event.isAltDown

  private def forcesSelection(event: ScrollEvent): Boolean =
    event.isShiftDown || event.isAltDown

  private def insideCanvas(x: Double, y: Double): Boolean =
    canvas.getWidth > 0 && canvas.getHeight > 0 && x >= 0 && y >= 0 && x < canvas.getWidth && y < canvas.getHeight

  private def terminalButton(button: JfxMouseButton): Option[MouseButton] =
    button match
      case JfxMouseButton.PRIMARY   => Some(MouseButton.Left)
      case JfxMouseButton.MIDDLE    => Some(MouseButton.Middle)
      case JfxMouseButton.SECONDARY => Some(MouseButton.Right)
      case JfxMouseButton.BACK      => Some(MouseButton.Back)
      case JfxMouseButton.FORWARD   => Some(MouseButton.Forward)
      case _                        => None

  private def sendMouse(
      x: Double,
      y: Double,
      button: Option[MouseButton],
      action: MouseAction,
      event: javafx.scene.input.MouseEvent
  ): Unit =
    sendMouse(x, y, button, action, event.isShiftDown, event.isAltDown, event.isControlDown)

  private def sendMouse(
      x: Double,
      y: Double,
      button: Option[MouseButton],
      action: MouseAction,
      event: ScrollEvent
  ): Unit =
    sendMouse(x, y, button, action, event.isShiftDown, event.isAltDown, event.isControlDown)

  private def sendMouse(
      x: Double,
      y: Double,
      button: Option[MouseButton],
      action: MouseAction,
      shift: Boolean,
      alt: Boolean,
      control: Boolean
  ): Unit =
    val position = canvas.positionAt(x, y)
    calls.mouse(
      session,
      MouseInput(
        column = position.col,
        row = position.row,
        button = button,
        action = action,
        modifiers = MouseModifiers(shift = shift, alt = alt, control = control)
      )
    )

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

  /** Asks the host for the part of the scrollback this viewport now needs. The request is deduped until an answer lands, because a reader
    * who is scrolled back while a command produces output would otherwise ask again on every damage tick.
    */
  private def requestMissingHistory(model: ScreenModel): Unit =
    val wanted =
      if model.missingHistory > 0 then Some((1, math.min(model.maxScroll, model.scrollOffset + model.rows)))
      else None
    if wanted != historyRequest then
      historyRequest = wanted
      wanted.foreach((from, count) => calls.history(session, from, count))

  /** The scrollbar is managed exactly while it is visible, so showing or hiding it changes the content area the grid is computed from. That
    * is a layout change the window's size listeners never see, so the transition arms the same debounce a window resize does; the request
    * is deduped by `lastRequested`. History only grows (the host keeps its scrollback), so the transition is one-way in practice and cannot
    * cycle on a boundary.
    */
  private def updateScrollbar(model: ScreenModel): Unit =
    val wasVisible = scrollbar.isVisible
    scrollbar.setVisible(model.maxScroll > 0)
    if scrollbar.isVisible != wasVisible then scheduleResize()
    if model.maxScroll > 0 then
      scrollbar.setMin(0)
      scrollbar.setMax(model.maxScroll)
      scrollbar.setVisibleAmount(math.max(1, model.rows * model.maxScroll / math.max(1, model.maxScroll + model.rows)))
      scrollbar.setValue(model.maxScroll - model.scrollOffset)

  /** The grid the view has room for, in cells, as the resize request computes it; for the window's own tests. */
  private[corvi] def contentGrid: TermSize =
    val (cols, rows) = gridForSize()
    TermSize(cols = cols, rows = rows)

  /** The grid the view has room for, in cells. The find bar is a top pane, so its managed height is not room for rows. */
  private def gridForSize(): (Int, Int) =
    val layout = currentMetrics.layout
    (layout.colsFor(math.max(0, getWidth) - scrollbarExtent), layout.rowsFor(math.max(0, getHeight - findBarExtent)))

  /** The room the scrollbar takes from the content area: the layout's own answer, taken from the scrollbar's managed width, never a
    * constant. The scrollbar is managed exactly while it is visible, so a hidden one takes nothing and the grid stops changing the moment
    * the layout does. (A hard-coded 16 next to a 15-pixel scrollbar is a column of mismatch at every width.)
    */
  private def scrollbarExtent: Double =
    if scrollbar.isManaged then math.max(scrollbar.getWidth, scrollbar.prefWidth(-1)) else 0.0

  /** The room the find bar takes from the content area, measured the same way as the scrollbar. A hidden bar is unmanaged and takes
    * nothing; when it is open, its preferred height is known even before the first layout pass.
    */
  private def findBarExtent: Double =
    if findBar.isManaged then math.max(0.0, math.max(findBar.getHeight, findBar.prefHeight(-1))) else 0.0

  private def scheduleResize(): Unit =
    if end.isEmpty && model.isDefined then
      resizeTimer.setDuration(Duration.millis(if model.exists(_.frame.modes.alternateScreen) then 100 else 500))
      resizeTimer.playFromStart()

  /** A view that has not been laid out yet has no size, and asking the host to fit a grid to nothing would shrink its shell to a corner.
    * Only a view with room for the canvas, the scrollbar and the find bar asks for a grid.
    */
  private def requestGridForSize(): Unit =
    if end.isEmpty then
      model.foreach { current =>
        val extent = scrollbarExtent
        if getWidth > extent && getHeight > findBarExtent then
          val (cols, rows) = gridForSize()
          val wanted = TermSize(cols = cols, rows = rows)
          // Once per size: the frame lags the request by a round trip, so without this every tick between the two would resend the same grid.
          if wanted != current.frame.size && !lastRequested.contains(wanted) then
            lastRequested = Some(wanted)
            calls.resize(session, wanted)
      }

  private def text(selection: Selection): String = model.map(_.text(selection)).getOrElse("")
