package corvi.app

import corvi.contract.*
import corvi.renderer.{CellPosition, SessionCalls, SessionEvents, TerminalMetrics, TerminalView}
import java.util.concurrent.atomic.{AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyEvent}
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.jdk.CollectionConverters.*

/** The key path: what a keystroke sends to the session, and which chords belong to the window instead.
  *
  * A fake `SessionCalls` records exactly what would have gone to the host, and a seeded `Snapshot` gives the view something to select, so
  * every check here is about the view's own encoding — no PTY, no daemon, no window manager. The scene's chord machine is covered in
  * `ui.test`; this is where Control+letter becomes a byte and Command+C becomes a copy.
  */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class KeyTest:

  private final class RecordingCalls extends SessionCalls:
    private val texts = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def sent: Vector[String] = texts.iterator.asScala.toVector
    def reset(): Unit = texts.clear()

    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit =
      val _ = texts.add(text)
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
    val terminal = TerminalView(calls, SessionId("key-test"), TerminalMetrics.monospaced(13))
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
    val _ = onFx {
      Event.fireEvent(
        view.get().getOrElse(fail("no terminal")),
        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta)
      )
    }

  private def typed(
      character: String,
      control: Boolean = false,
      meta: Boolean = false,
      shift: Boolean = false,
      alt: Boolean = false
  ): Unit =
    onFx {
      val _ = Event.fireEvent(
        view.get().getOrElse(fail("no terminal")),
        new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, shift, control, alt, meta)
      )
    }

  private def snapshot(text: String): Snapshot =
    val cells = text.map(character => Cell(character.toString, CellWidth.Narrow, None, None, false, false, false, false)).toVector
    Snapshot(
      id = SessionId("key-test"),
      size = TermSize(cols = cells.size, rows = 1),
      rows = Vector(Row(cells)),
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = 0,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

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
