package corvi.app

import corvi.contract.*
import corvi.renderer.*
import corvi.renderer.TerminalView as TerminalPane
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.jdk.CollectionConverters.*

/** Re-measuring a terminal keeps its model, selection and viewport, and asks the host for the new grid. */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FontSizeTest:

  private val calls = new FontCalls
  private val view = AtomicReference[Option[TerminalPane]](None)

  @Start
  def start(stage: Stage): Unit =
    val terminal = TerminalPane(calls, SessionId("font-test"), TerminalMetrics.monospaced(13))
    val scene = new Scene(terminal, 640, 320)
    stage.setScene(scene)
    stage.show()
    terminal.attached(snapshot(Vector("hello world", "second row"), historyRows = 2))
    terminal.select(CellPosition(0, 0), CellPosition(0, 4))
    view.set(Some(terminal))

  @Test
  def `a font change re-measures and keeps the model and selection`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    val beforeFrame = onFx(terminal.frame)
    val beforeSelection = onFx(terminal.selectedText)
    calls.reset()

    onFx(terminal.setFontSize(18.0))

    assertEquals(18.0, onFx(terminal.fontMetrics.font.getSize), 0.001)
    assertEquals(beforeFrame, onFx(terminal.frame), "the frame is not rebuilt by the font change")
    assertEquals(beforeSelection, onFx(terminal.selectedText), "the selection survives the canvas replacement")
    assertTrue(calls.resizes.nonEmpty, "the new metrics ask the host for a fresh grid")

  @Test
  def `a font change keeps a scrolled viewport`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.history(Vector(row("older one"), row("older two"))))
    onFx(terminal.scrollTo(1))
    assertEquals(1, onFx(terminal.screen).map(_.scrollOffset).getOrElse(-1))
    onFx(terminal.setFontSize(15.0))
    assertEquals(15.0, onFx(terminal.fontMetrics.font.getSize), 0.001)
    assertEquals(1, onFx(terminal.screen).map(_.scrollOffset).getOrElse(-1), "the reader stays in the same scrollback row")

  private final class FontCalls extends SessionCalls:
    private val sizes = new java.util.concurrent.ConcurrentLinkedQueue[TermSize]()
    def resizes: Vector[TermSize] = sizes.iterator().asScala.toVector
    def reset(): Unit = sizes.clear()
    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit = ()
    def mouse(session: SessionId, input: MouseInput): Unit = ()
    def key(session: SessionId, input: KeyInput): Unit = ()
    def focus(session: SessionId, focused: Boolean): Unit = ()
    def resize(session: SessionId, size: TermSize): Unit =
      val _ = sizes.add(size)
    def history(session: SessionId, from: Int, count: Int): Unit = ()
    def close(session: SessionId): Unit = ()

  private def row(text: String): Row =
    Row(text.map(character => Cell(character.toString, CellWidth.Narrow, None, None, false, false, false, false)).toVector)

  private def snapshot(rows: Vector[String], historyRows: Int): Snapshot =
    val width = rows.map(_.length).maxOption.getOrElse(0)
    val padded = rows.map(text => Row(row(text).cells.padTo(width, Cell(" ", CellWidth.Narrow, None, None, false, false, false, false))))
    Snapshot(
      id = SessionId("font-test"),
      size = TermSize(cols = width, rows = padded.size),
      rows = padded,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = historyRows,
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
