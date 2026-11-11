package corvi.app

import corvi.contract.*
import corvi.renderer.{AltMode, CellPosition, Preedit, SessionCalls, SessionEvents, TerminalMetrics, TerminalView}
import java.util.concurrent.atomic.{AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.geometry.Point2D
import javafx.scene.Scene
import javafx.scene.input.{
  Clipboard,
  ClipboardContent,
  InputMethodEvent,
  InputMethodHighlight,
  InputMethodTextRun,
  KeyCode,
  KeyEvent,
  MouseButton as JfxMouseButton,
  MouseEvent,
  ScrollEvent
}
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.jdk.CollectionConverters.*

/** The key and pointer path: what input sends to the session, and which gestures belong to the window instead.
  *
  * A fake `SessionCalls` records exactly what would have gone to the host, and a seeded `Snapshot` gives the view something to select, so
  * every check here is about the view's own encoding — no PTY, no daemon, no window manager. The scene's chord machine is covered in
  * `ui.test`; this is where Control+letter becomes a byte, a mouse report becomes a cell and button, and Command+C becomes a copy.
  */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class KeyTest:

  private final class RecordingCalls extends SessionCalls:
    private val texts = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private val mice = new java.util.concurrent.ConcurrentLinkedQueue[MouseInput]()
    private val keys = new java.util.concurrent.ConcurrentLinkedQueue[KeyInput]()
    private val focuses = new java.util.concurrent.ConcurrentLinkedQueue[Boolean]()

    def sent: Vector[String] = texts.iterator.asScala.toVector
    def mouseEvents: Vector[MouseInput] = mice.iterator.asScala.toVector
    def keyEvents: Vector[KeyInput] = keys.iterator.asScala.toVector
    def focusEvents: Vector[Boolean] = focuses.iterator.asScala.toVector
    def reset(): Unit =
      texts.clear()
      mice.clear()
      keys.clear()
      focuses.clear()

    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit =
      val _ = texts.add(text)
    def mouse(session: SessionId, input: MouseInput): Unit =
      val _ = mice.add(input)
    def key(session: SessionId, input: KeyInput): Unit =
      val _ = keys.add(input)
    def focus(session: SessionId, focused: Boolean): Unit =
      val _ = focuses.add(focused)
    def resize(session: SessionId, size: TermSize): Unit = ()
    def history(session: SessionId, from: Int, count: Int): Unit = ()
    def close(session: SessionId): Unit = ()

  private val calls = RecordingCalls()
  private val view = AtomicReference[Option[TerminalView]](None)

  /** The clipboard is the developer's, not this test's: put back what was there before each test that overwrites it. */
  private val savedClipboard = AtomicReference[Option[String]](None)

  @Start
  def start(stage: Stage): Unit =
    calls.reset()
    val terminal = TerminalView(calls, SessionId("key-test"), TerminalMetrics.monospaced(13), AltMode.Compose)
    val scene = new Scene(terminal, 800, 400)
    stage.setScene(scene)
    stage.show()
    terminal.requestFocus()
    terminal.attached(snapshot("hello world"))
    view.set(Some(terminal))

  @Test
  def `control keys send their C0 byte`(): Unit =
    clear()
    press(KeyCode.C, control = true)
    press(KeyCode.D, control = true)
    press(KeyCode.A, control = true)
    press(KeyCode.E, control = true)
    press(KeyCode.L, control = true)
    press(KeyCode.Z, control = true)
    press(KeyCode.SPACE, control = true)
    assertEquals(Vector("\u0003", "\u0004", "\u0001", "\u0005", "\u000c", "\u001a", "\u0000"), calls.sent)

  @Test
  def `the editing keys keep their bytes`(): Unit =
    clear()
    press(KeyCode.BACK_SPACE)
    press(KeyCode.ENTER)
    press(KeyCode.TAB)
    press(KeyCode.ESCAPE)
    assertEquals(Vector("\u007f", "\r", "\t", "\u001b"), calls.sent)

  @Test
  def `arrows and navigation keys go through the key route`(): Unit =
    clear()
    press(KeyCode.UP)
    press(KeyCode.DOWN)
    press(KeyCode.LEFT)
    press(KeyCode.RIGHT)
    press(KeyCode.HOME)
    press(KeyCode.END)
    press(KeyCode.PAGE_UP)
    press(KeyCode.PAGE_DOWN)
    press(KeyCode.INSERT)
    press(KeyCode.DELETE)
    assertEquals(
      Vector(
        KeyInput(TerminalKey.Up, KeyModifiers.none),
        KeyInput(TerminalKey.Down, KeyModifiers.none),
        KeyInput(TerminalKey.Left, KeyModifiers.none),
        KeyInput(TerminalKey.Right, KeyModifiers.none),
        KeyInput(TerminalKey.Home, KeyModifiers.none),
        KeyInput(TerminalKey.End, KeyModifiers.none),
        KeyInput(TerminalKey.PageUp, KeyModifiers.none),
        KeyInput(TerminalKey.PageDown, KeyModifiers.none),
        KeyInput(TerminalKey.Insert, KeyModifiers.none),
        KeyInput(TerminalKey.Delete, KeyModifiers.none)
      ),
      calls.keyEvents
    )
    assertEquals(Vector.empty, calls.sent, "a navigation key is not text")

  @Test
  def `function keys go through the key route`(): Unit =
    clear()
    val keys = Vector(
      KeyCode.F1 -> TerminalKey.F1,
      KeyCode.F2 -> TerminalKey.F2,
      KeyCode.F3 -> TerminalKey.F3,
      KeyCode.F4 -> TerminalKey.F4,
      KeyCode.F5 -> TerminalKey.F5,
      KeyCode.F6 -> TerminalKey.F6,
      KeyCode.F7 -> TerminalKey.F7,
      KeyCode.F8 -> TerminalKey.F8,
      KeyCode.F9 -> TerminalKey.F9,
      KeyCode.F10 -> TerminalKey.F10,
      KeyCode.F11 -> TerminalKey.F11,
      KeyCode.F12 -> TerminalKey.F12
    )
    keys.foreach { case (code, _) => press(code) }
    assertEquals(keys.map { case (_, key) => KeyInput(key, KeyModifiers.none) }, calls.keyEvents)
    assertEquals(Vector.empty, calls.sent)

  @Test
  def `shift-tab is a key and plain tab stays text`(): Unit =
    clear()
    press(KeyCode.TAB, shift = true)
    press(KeyCode.TAB)
    assertEquals(Vector(KeyInput(TerminalKey.Tab, KeyModifiers.none.copy(shift = true))), calls.keyEvents)
    assertEquals(Vector("\t"), calls.sent)

  @Test
  def `modified arrows carry their modifiers`(): Unit =
    clear()
    press(KeyCode.LEFT, control = true)
    press(KeyCode.RIGHT, shift = true)
    press(KeyCode.UP, control = true, shift = true)
    press(KeyCode.DOWN, alt = true)
    assertEquals(
      Vector(
        KeyInput(TerminalKey.Left, KeyModifiers.none.copy(control = true)),
        KeyInput(TerminalKey.Right, KeyModifiers.none.copy(shift = true)),
        KeyInput(TerminalKey.Up, KeyModifiers.none.copy(control = true, shift = true)),
        KeyInput(TerminalKey.Down, KeyModifiers.none.copy(alt = true))
      ),
      calls.keyEvents
    )

  @Test
  def `option composition stays text in compose mode`(): Unit =
    clear()
    press(KeyCode.V, alt = true)
    typed("√", alt = true)
    assertEquals(Vector.empty, calls.keyEvents, "Alt+printable is not a key in Compose mode")
    assertEquals(Vector("√"), calls.sent)

  @Test
  def `alt printable is a meta key in escape mode`(): Unit =
    withAltMode(AltMode.Escape) { terminal =>
      clear()
      pressOn(terminal, KeyCode.A, alt = true)
      typedOn(terminal, "å", alt = true)
      assertEquals(Vector(KeyInput(TerminalKey.Printable("a"), KeyModifiers.none.copy(alt = true))), calls.keyEvents)
      assertEquals(Vector.empty, calls.sent, "the platform's composed companion is swallowed")
    }

  @Test
  def `alt printable is a meta key in meta mode`(): Unit =
    withAltMode(AltMode.Meta) { terminal =>
      clear()
      pressOn(terminal, KeyCode.B, alt = true)
      typedOn(terminal, "∫", alt = true)
      assertEquals(Vector(KeyInput(TerminalKey.Printable("b"), KeyModifiers.none.copy(alt = true))), calls.keyEvents)
      assertEquals(Vector.empty, calls.sent)
    }

  @Test
  def `alt shift digit uses its shifted symbol`(): Unit =
    withAltMode(AltMode.Escape) { terminal =>
      clear()
      pressOn(terminal, KeyCode.DIGIT1, alt = true, shift = true)
      assertEquals(
        Vector(KeyInput(TerminalKey.Printable("!"), KeyModifiers.none.copy(shift = true, alt = true))),
        calls.keyEvents
      )
    }

  @Test
  def `an alt typed companion falls back to a printable key`(): Unit =
    withAltMode(AltMode.Escape) { terminal =>
      clear()
      typedOn(terminal, "é", alt = true)
      assertEquals(Vector(KeyInput(TerminalKey.Printable("é"), KeyModifiers.none.copy(alt = true))), calls.keyEvents)
      assertEquals(Vector.empty, calls.sent)
    }

  @Test
  def `ctrl-alt and meta-alt typed companions do not leak a meta key`(): Unit =
    withAltMode(AltMode.Escape) { terminal =>
      clear()
      typedOn(terminal, "a", alt = true, control = true)
      typedOn(terminal, "a", alt = true, meta = true)
      assertEquals(Vector.empty, calls.keyEvents)
      assertEquals(Vector.empty, calls.sent)
    }

  @Test
  def `alt modified arrows are still keys in compose mode`(): Unit =
    clear()
    press(KeyCode.LEFT, alt = true)
    assertEquals(Vector(KeyInput(TerminalKey.Left, KeyModifiers.none.copy(alt = true))), calls.keyEvents)

  @Test
  def `plain printable text still takes the text route`(): Unit =
    clear()
    typed("a")
    typed("Z", shift = true)
    assertEquals(Vector("a", "Z"), calls.sent)
    assertEquals(Vector.empty, calls.keyEvents)

  @Test
  def `control-C interrupts and does not copy`(): Unit =
    clear()
    onFx {
      val content = ClipboardContent()
      content.putString("untouched")
      Clipboard.getSystemClipboard().setContent(content)
      selectFirstWord()
    }
    press(KeyCode.C, control = true)
    assertEquals(Vector("\u0003"), calls.sent, "^C is SIGINT, not the copy chord")
    assertEquals("untouched", onFx(clipboardText), "Ctrl+C leaves the clipboard alone")

  @Test
  def `the copy chord copies the selection`(): Unit =
    clear()
    onFx(selectFirstWord())
    press(KeyCode.C, meta = true)
    press(KeyCode.C, control = true, shift = true)
    assertEquals("hello", onFx(clipboardText), "Command+C (and its Ctrl+Shift+C alias) copy the selection")
    assertEquals(Vector.empty, calls.sent, "and send nothing to the shell")

  @Test
  def `the paste chord pastes the clipboard`(): Unit =
    clear()
    val _ = onFx {
      val content = ClipboardContent()
      content.putString("pasted")
      Clipboard.getSystemClipboard().setContent(content)
    }
    press(KeyCode.V, meta = true)
    press(KeyCode.V, control = true, shift = true)
    assertEquals(Vector("pasted", "pasted"), calls.sent)

  @Test
  def `an empty clipboard is not a keystroke`(): Unit =
    clear()
    val _ = onFx {
      val content = ClipboardContent()
      content.putString("")
      Clipboard.getSystemClipboard().setContent(content)
    }
    press(KeyCode.V, meta = true)
    assertEquals(Vector.empty, calls.sent)

  @Test
  def `a shortcut's typed companion is not typed`(): Unit =
    clear()
    typed("c", meta = true)
    typed("c", control = true)
    assertEquals(Vector.empty, calls.sent, "the platform sends KEY_TYPED for a Command or Control shortcut too; it is not a character")
    typed("c")
    typed("C", shift = true)
    assertEquals(Vector("c", "C"), calls.sent, "plain and Shift-only characters still reach the shell")

  @Test
  def `an option-composed character is sent`(): Unit =
    clear()
    typed("√", alt = true)
    typed("é", alt = true)
    assertEquals(Vector("√", "é"), calls.sent, "Option+V and Option+E compose characters; Alt is not a shortcut")

  @Test
  def `control-space sends one NUL, not a NUL and a space`(): Unit =
    clear()
    press(KeyCode.SPACE, control = true)
    typed(" ", control = true)
    assertEquals(Vector("\u0000"), calls.sent)

  @Test
  def `control-slash sends the unit separator and control-question-mark delete`(): Unit =
    clear()
    press(KeyCode.SLASH, control = true)
    press(KeyCode.SLASH, control = true, shift = true)
    assertEquals(Vector("\u001f", "\u007f"), calls.sent)

  @Test
  def `a click in mouse-reporting mode sends a press and a release`(): Unit =
    clear()
    onFx(view.get().getOrElse(fail("no terminal")).attached(snapshot("hello world", MouseMode.Click, MouseFormat.Sgr)))
    fireMouse(MouseEvent.MOUSE_PRESSED, JfxMouseButton.PRIMARY, 5, 5)
    fireMouse(MouseEvent.MOUSE_RELEASED, JfxMouseButton.PRIMARY, 5, 5)
    assertEquals(
      Vector(
        MouseInput(0, 0, Some(MouseButton.Left), MouseAction.Press, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.Left), MouseAction.Release, MouseModifiers(false, false, false))
      ),
      calls.mouseEvents
    )

  @Test
  def `the wheel goes to a mouse-aware program instead of the scrollback`(): Unit =
    clear()
    onFx(view.get().getOrElse(fail("no terminal")).attached(snapshot("hello world", MouseMode.Click, MouseFormat.Sgr)))
    fireScroll(deltaY = 3)
    assertEquals(
      Vector(MouseInput(0, 0, Some(MouseButton.WheelUp), MouseAction.Press, MouseModifiers(false, false, false))),
      calls.mouseEvents
    )
    assertEquals(0, onFx(view.get().flatMap(_.screen).map(_.scrollOffset).getOrElse(-1)))

  @Test
  def `without mouse reporting the view keeps the click for selection`(): Unit =
    clear()
    fireMouse(MouseEvent.MOUSE_PRESSED, JfxMouseButton.PRIMARY, 5, 5)
    fireMouse(MouseEvent.MOUSE_DRAGGED, JfxMouseButton.PRIMARY, 15, 5)
    assertTrue(calls.mouseEvents.isEmpty)
    assertTrue(onFx(view.get().flatMap(_.selectedText)).exists(text => text.startsWith("h")))

  @Test
  def `IME text location is never null on an unattached or scrolled-back view`(): Unit =
    val unattached = onFx(TerminalView(calls, SessionId("ime-unattached"), TerminalMetrics.monospaced(13)))
    val absent = onFx(unattached.getInputMethodRequests.getTextLocation(0))
    assertNotNull(absent, "an unattached view still answers with a point")
    assertEquals(Point2D.ZERO, absent)
    onFx(unattached.dispose())

    val scrolled = onFx {
      val terminal = TerminalView(calls, SessionId("ime-scrolled"), TerminalMetrics.monospaced(13))
      terminal.attached(snapshot("hello world", historyRows = 2))
      terminal.scrollTo(1)
      terminal
    }
    val scrolledLocation = onFx(scrolled.getInputMethodRequests.getTextLocation(0))
    assertNotNull(scrolledLocation, "a scrolled-back view still answers with a point")
    assertEquals(Point2D.ZERO, scrolledLocation)
    onFx(scrolled.dispose())

  @Test
  def `IME commits text through session.text`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot("hello world")))
    ime(terminal, composed = "", committed = "日本語🙂")
    assertEquals(Vector("日本語🙂"), calls.sent)
    assertTrue(onFx(terminal.composition).isEmpty)

  @Test
  def `IME preedit is painted but not sent until it commits`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot("hello world")))
    assertNotNull(onFx(terminal.getInputMethodRequests), "the view exposes IME candidate-window requests")
    ime(terminal, composed = "にほん", committed = "", caret = 3)
    assertEquals(Vector.empty, calls.sent, "composed text never reaches the host")
    assertEquals(Some(Preedit("にほん", 3)), onFx(terminal.composition))
    ime(terminal, composed = "", committed = "日本")
    assertEquals(Vector("日本"), calls.sent)
    assertTrue(onFx(terminal.composition).isEmpty, "commit clears the overlay")

  @Test
  def `focus loss clears the IME preedit`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot("hello world")))
    ime(terminal, composed = "にほん", committed = "", caret = 3)
    assertEquals(Some(Preedit("にほん", 3)), onFx(terminal.composition))
    onFx(terminal.focusChanged(false))
    assertTrue(onFx(terminal.composition).isEmpty, "losing the keyboard cancels the composition overlay")

  @Test
  def `an IME event with both a commit and a composition does both`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot("hello world")))
    ime(terminal, composed = "にほん", committed = "日本", caret = 2)
    assertEquals(Vector("日本"), calls.sent)
    assertEquals(Some(Preedit("にほん", 2)), onFx(terminal.composition))

  @Test
  def `bracketed paste wraps the clipboard only when the program enabled it`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))
    val _ = onFx {
      val content = ClipboardContent()
      content.putString("one\ntwo")
      Clipboard.getSystemClipboard().setContent(content)
    }
    onFx(terminal.attached(snapshot("hello world", bracketedPaste = true)))
    press(KeyCode.V, control = true, shift = true)
    onFx(terminal.attached(snapshot("hello world", bracketedPaste = false)))
    press(KeyCode.V, control = true, shift = true)
    assertEquals(Vector("\u001b[200~one\ntwo\u001b[201~", "one\ntwo"), calls.sent)

  @Test
  def `focus changes are reported only while focus reporting is on`(): Unit =
    clear()
    val terminal = view.get().getOrElse(fail("no terminal"))

    onFx(terminal.attached(snapshot("hello world", focusReporting = true)))
    onFx(terminal.focusChanged(true))
    onFx(terminal.focusChanged(false))
    assertEquals(Vector(true, false), calls.focusEvents)

    clear()
    onFx(terminal.attached(snapshot("hello world", MouseMode.Off)))
    onFx(terminal.focusChanged(true))
    onFx(terminal.focusChanged(false))
    assertEquals(Vector.empty, calls.focusEvents, "a program that did not ask for 1004 gets no reports")

  @Test
  def `horizontal wheel and extra buttons go to a mouse-aware program`(): Unit =
    clear()
    onFx(
      view
        .get()
        .getOrElse(fail("no terminal"))
        .attached(snapshot("hello world", MouseMode.Click, MouseFormat.Sgr, historyRows = 5))
    )
    fireScroll(deltaY = 0, deltaX = 3)
    fireScroll(deltaY = 0, deltaX = -3)
    fireMouse(MouseEvent.MOUSE_PRESSED, JfxMouseButton.BACK, 5, 5)
    fireMouse(MouseEvent.MOUSE_RELEASED, JfxMouseButton.BACK, 5, 5)
    fireMouse(MouseEvent.MOUSE_PRESSED, JfxMouseButton.FORWARD, 5, 5)
    fireMouse(MouseEvent.MOUSE_RELEASED, JfxMouseButton.FORWARD, 5, 5)
    assertEquals(
      Vector(
        MouseInput(0, 0, Some(MouseButton.WheelRight), MouseAction.Press, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.WheelLeft), MouseAction.Press, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.Back), MouseAction.Press, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.Back), MouseAction.Release, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.Forward), MouseAction.Press, MouseModifiers(false, false, false)),
        MouseInput(0, 0, Some(MouseButton.Forward), MouseAction.Release, MouseModifiers(false, false, false))
      ),
      calls.mouseEvents
    )
    assertEquals(0, onFx(view.get().flatMap(_.screen).map(_.scrollOffset).getOrElse(-1)))

  @Test
  def `an ended session takes no keys, paste or mouse reports`(): Unit =
    clear()
    val ended = onFx {
      val terminal = TerminalView(calls, SessionId("ended"), TerminalMetrics.monospaced(13))
      terminal.attached(snapshot("hello world", MouseMode.Click, MouseFormat.Sgr))
      terminal.sessionEnded(Ended(code = Some(0), closed = false, reason = None))
      terminal
    }
    onFx {
      Event.fireEvent(ended, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false))
      Event.fireEvent(ended, new KeyEvent(KeyEvent.KEY_TYPED, "x", "", KeyCode.UNDEFINED, false, false, false, false))
    }
    fireMouseOn(ended, MouseEvent.MOUSE_PRESSED, JfxMouseButton.PRIMARY, 5, 5)
    fireScrollOn(ended, 3)
    assertEquals(Vector.empty, calls.sent)
    assertTrue(calls.mouseEvents.isEmpty)
    onFx(ended.dispose())

  // --- helpers -----------------------------------------------------------------------------------------------------------------------

  @BeforeEach
  def rememberClipboard(): Unit =
    savedClipboard.set(Some(onFx(clipboardText)))

  @AfterEach
  def restoreClipboard(): Unit =
    val saved = savedClipboard.get().getOrElse("")
    val _ = onFx {
      val content = ClipboardContent()
      if saved.nonEmpty then { val _ = content.putString(saved) }
      Clipboard.getSystemClipboard().setContent(content)
    }

  private def clear(): Unit = calls.reset()
  private def selectFirstWord(): Unit = view.get().foreach(terminal => terminal.select(CellPosition(0, 0), CellPosition(0, 4)))
  private def clipboardText: String = Option(Clipboard.getSystemClipboard().getString).getOrElse("")

  private def press(
      code: KeyCode,
      control: Boolean = false,
      meta: Boolean = false,
      shift: Boolean = false,
      alt: Boolean = false
  ): Unit =
    pressOn(view.get().getOrElse(fail("no terminal")), code, control = control, meta = meta, shift = shift, alt = alt)

  private def pressOn(
      target: TerminalView,
      code: KeyCode,
      control: Boolean = false,
      meta: Boolean = false,
      shift: Boolean = false,
      alt: Boolean
  ): Unit =
    val _ = onFx {
      Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta))
    }

  private def typed(
      character: String,
      control: Boolean = false,
      meta: Boolean = false,
      shift: Boolean = false,
      alt: Boolean = false
  ): Unit =
    typedOn(view.get().getOrElse(fail("no terminal")), character, control = control, meta = meta, shift = shift, alt = alt)

  private def typedOn(
      target: TerminalView,
      character: String,
      control: Boolean = false,
      meta: Boolean = false,
      shift: Boolean = false,
      alt: Boolean
  ): Unit =
    val _ = onFx {
      Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, shift, control, alt, meta))
    }

  private def withAltMode(mode: AltMode)(body: TerminalView => Unit): Unit =
    val terminal = onFx {
      val created = TerminalView(calls, SessionId("alt-mode-test"), TerminalMetrics.monospaced(13), mode)
      created.attached(snapshot("hello world"))
      created
    }
    try body(terminal)
    finally onFx(terminal.dispose())

  private def ime(target: TerminalView, composed: String, committed: String, caret: Int = 0): Unit =
    val runs: java.util.List[InputMethodTextRun] =
      if composed.isEmpty then java.util.List.of[InputMethodTextRun]()
      else java.util.List.of[InputMethodTextRun](new InputMethodTextRun(composed, InputMethodHighlight.UNSELECTED_RAW))
    val _ = onFx {
      Event.fireEvent(target, new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, runs, committed, caret))
    }

  private def snapshot(
      text: String,
      mode: MouseMode = MouseMode.Off,
      format: MouseFormat = MouseFormat.X10,
      bracketedPaste: Boolean = false,
      historyRows: Int = 0,
      focusReporting: Boolean = false
  ): Snapshot =
    val cells = text.map(character => Cell(character.toString, CellWidth.Narrow, None, None, false, false, false, false)).toVector
    Snapshot(
      id = SessionId("key-test"),
      size = TermSize(cols = cells.size, rows = 1),
      rows = Vector(Row(cells)),
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(
        alternateScreen = false,
        mode,
        format,
        bracketedPaste = bracketedPaste,
        title = "",
        focusReporting = focusReporting
      ),
      historyRows = historyRows,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private def fireMouse(kind: javafx.event.EventType[MouseEvent], button: JfxMouseButton, x: Double, y: Double): Unit =
    fireMouseOn(view.get().getOrElse(fail("no terminal")), kind, button, x, y)

  private def fireMouseOn(
      target: TerminalView,
      kind: javafx.event.EventType[MouseEvent],
      button: JfxMouseButton,
      x: Double,
      y: Double
  ): Unit =
    val _ = onFx {
      val event = new MouseEvent(
        kind,
        x,
        y,
        x,
        y,
        button,
        1,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        null
      )
      Event.fireEvent(target, event)
    }

  private def fireScroll(deltaY: Double, deltaX: Double = 0): Unit =
    fireScrollOn(view.get().getOrElse(fail("no terminal")), deltaY, deltaX)

  private def fireScrollOn(target: TerminalView, deltaY: Double, deltaX: Double = 0): Unit =
    val _ = onFx {
      val event = new ScrollEvent(
        ScrollEvent.SCROLL,
        5,
        5,
        5,
        5,
        false,
        false,
        false,
        false,
        false,
        false,
        deltaX,
        deltaY,
        deltaX,
        deltaY,
        ScrollEvent.HorizontalTextScrollUnits.NONE,
        0,
        ScrollEvent.VerticalTextScrollUnits.NONE,
        0,
        0,
        null
      )
      Event.fireEvent(target, event)
    }

  private def onFx[A](action: => A): A =
    if Platform.isFxApplicationThread then action
    else
      val result = AtomicReference[Either[Throwable, A]]()
      val latch = CountDownLatch(1)
      Platform.runLater { () =>
        try result.set(Right(action))
        catch case error: Throwable => result.set(Left(error))
        latch.countDown()
      }
      if !latch.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("the FX thread did not answer")
      result.get() match
        case Right(value) => value
        case Left(error)  => throw error
