package corvi.app

import corvi.contract.*
import corvi.renderer.*
import corvi.renderer.given
import corvi.renderer.TerminalView as TerminalPane
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.{Cursor as JfxCursor, Scene}
import javafx.scene.input.{MouseButton as JfxMouseButton, MouseEvent}
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.jdk.CollectionConverters.*

/** OSC 8 links in the renderer: hover feedback and the platform click modifier, with unsafe schemes refused. */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LinkTest:

  private val calls = new LinkCalls
  private val opened = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  private val view = AtomicReference[Option[TerminalPane]](None)

  @Start
  def start(stage: Stage): Unit =
    val terminal = TerminalPane(
      calls,
      SessionId("link-test"),
      TerminalMetrics.monospaced(13),
      openLink = uri => { val _ = opened.add(uri) }
    )
    val scene = new Scene(terminal, 640, 320)
    stage.setScene(scene)
    stage.show()
    view.set(Some(terminal))

  @Test
  def `hovering a link changes the cursor and underlines it`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot(Some("https://example.com"))))
    onFx(fireMouse(terminal, MouseEvent.MOUSE_MOVED, JfxMouseButton.NONE, 1, 1, control = false))
    assertEquals(Some("https://example.com"), onFx(terminal.hoveredLink))
    assertEquals(JfxCursor.HAND, onFx(terminal.getCursor))

    onFx(fireMouse(terminal, MouseEvent.MOUSE_MOVED, JfxMouseButton.NONE, 120, 1, control = false))
    assertEquals(None, onFx(terminal.hoveredLink))
    assertEquals(JfxCursor.DEFAULT, onFx(terminal.getCursor))

  @Test
  def `the platform click modifier opens an allowed link`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot(Some("https://example.com"))))
    opened.clear()
    val control = !java.lang.System.getProperty("os.name").toLowerCase.contains("mac")
    onFx(fireMouse(terminal, MouseEvent.MOUSE_PRESSED, JfxMouseButton.PRIMARY, 1, 1, control = control, meta = !control))
    assertEquals(Vector("https://example.com"), opened.iterator().asScala.toVector)

    opened.clear()
    onFx(fireMouse(terminal, MouseEvent.MOUSE_PRESSED, JfxMouseButton.SECONDARY, 1, 1, control = control, meta = !control))
    onFx(fireMouse(terminal, MouseEvent.MOUSE_PRESSED, JfxMouseButton.MIDDLE, 1, 1, control = control, meta = !control))
    assertTrue(opened.isEmpty, "only the primary button opens a link")

  @Test
  def `an unsafe scheme is neither hovered nor opened`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.attached(snapshot(Some("javascript:alert(1)"))))
    opened.clear()
    onFx(fireMouse(terminal, MouseEvent.MOUSE_MOVED, JfxMouseButton.NONE, 1, 1, control = false))
    assertEquals(None, onFx(terminal.hoveredLink))
    val control = !java.lang.System.getProperty("os.name").toLowerCase.contains("mac")
    onFx(fireMouse(terminal, MouseEvent.MOUSE_PRESSED, JfxMouseButton.PRIMARY, 1, 1, control = control, meta = !control))
    assertTrue(opened.isEmpty, "an unsafe OSC 8 target must not reach the opener")

  private final class LinkCalls extends SessionCalls:
    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit = ()
    def mouse(session: SessionId, input: MouseInput): Unit = ()
    def key(session: SessionId, input: KeyInput): Unit = ()
    def focus(session: SessionId, focused: Boolean): Unit = ()
    def resize(session: SessionId, size: TermSize): Unit = ()
    def history(session: SessionId, from: Int, count: Int): Unit = ()
    def close(session: SessionId): Unit = ()

  private def snapshot(link: Option[String]): Snapshot =
    val cells = "LINK".map(character => Cell(character.toString, CellWidth.Narrow, None, None, false, false, false, false, link)).toVector
    Snapshot(
      id = SessionId("link-test"),
      size = TermSize(cols = cells.size + 16, rows = 2),
      rows = Vector(Row(cells), Row(Vector.empty)),
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = 0,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private def fireMouse(
      target: TerminalPane,
      kind: javafx.event.EventType[MouseEvent],
      button: JfxMouseButton,
      x: Double,
      y: Double,
      control: Boolean,
      meta: Boolean = false
  ): Unit =
    val _ = Event.fireEvent(
      target,
      new MouseEvent(
        kind,
        x,
        y,
        x,
        y,
        button,
        1,
        false,
        control,
        false,
        meta,
        button == JfxMouseButton.PRIMARY,
        false,
        false,
        false,
        false,
        false,
        null
      )
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
