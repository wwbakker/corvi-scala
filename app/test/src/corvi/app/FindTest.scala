package corvi.app

import corvi.contract.*
import corvi.renderer.*
import corvi.renderer.TerminalView as TerminalPane
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.jdk.CollectionConverters.*

/** The find bar's client behaviour: literal search over loaded history and the live grid, navigation, scroll and clearing. */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FindTest:

  private val calls = new FindCalls
  private val view = AtomicReference[Option[TerminalPane]](None)

  @Start
  def start(stage: Stage): Unit =
    val terminal = TerminalPane(calls, SessionId("find-test"), TerminalMetrics.monospaced(13))
    val scene = new Scene(terminal, 640, 320)
    stage.setScene(scene)
    stage.show()
    terminal.attached(snapshot(Vector("alpha beta", "gamma alpha"), historyRows = 1, id = terminal.session))
    terminal.history(Vector(row("older alpha")))
    view.set(Some(terminal))

  @Test
  def `find searches history and the live grid and scrolls to the first match`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.openFind())
    onFx(terminal.findBar.field.setText("alpha"))
    val (matches, current) = onFx(terminal.findState)
    assertEquals(3, matches.size)
    assertEquals(Some(0), current)
    assertEquals(1, onFx(terminal.screen).map(_.scrollOffset).getOrElse(-1), "the first match is one history row above the grid")

    onFx(terminal.findBar.next.fire())
    assertEquals(Some(1), onFx(terminal.findState)._2)
    assertEquals(0, onFx(terminal.screen).map(_.scrollOffset).getOrElse(-1), "the next match is in the live grid")

    onFx(terminal.findBar.previous.fire())
    assertEquals(Some(0), onFx(terminal.findState)._2)

  @Test
  def `escape closes the find bar and drops the matches`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    onFx(terminal.openFind())
    onFx(terminal.findBar.field.setText("alpha"))
    assertFalse(onFx(terminal.findState)._1.isEmpty)
    onFx(Event.fireEvent(terminal.findBar.field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)))
    assertFalse(onFx(terminal.findBar.isOpen), "Escape hides the bar")
    assertTrue(onFx(terminal.findState)._1.isEmpty, "Escape drops the matches")
    onFx(terminal.clearFind())

  @Test
  def `the first search asks for the whole available history`(): Unit =
    calls.reset()
    val terminal = onFx {
      val created = TerminalPane(calls, SessionId("find-history"), TerminalMetrics.monospaced(13))
      created.attached(snapshot(Vector("alpha beta"), historyRows = 5, id = created.session))
      created.openFind()
      created.findBar.field.setText("alpha")
      created
    }
    assertTrue(calls.historyRequests.contains((1, 5)), s"history request was ${calls.historyRequests}")
    onFx(terminal.clearFind())
    onFx(terminal.dispose())

  @Test
  def `the find bar takes rows, requests a resize and restores them on close`(): Unit =
    // An unattached view with an explicit size: this measures the bar's own extent, not whatever size a window manager gave the stage.
    val terminal = onFx {
      val created = TerminalPane(calls, SessionId("find-layout"), TerminalMetrics.monospaced(13))
      created.attached(snapshot(Vector("alpha beta"), historyRows = 0, id = created.session))
      created.resize(640, 320)
      created
    }
    calls.reset()
    val before = onFx(terminal.contentGrid.rows)
    onFx(terminal.openFind())
    val withBar = onFx(terminal.contentGrid.rows)
    assertTrue(withBar < before, s"the open bar takes at least one row: $before -> $withBar")
    assertTrue(await(() => calls.resizes.nonEmpty, 3000), "opening the bar asks the host for a new grid")
    onFx(terminal.clearFind())
    assertEquals(before, onFx(terminal.contentGrid.rows), "closing the bar gives the rows back")
    onFx(terminal.dispose())

  @Test
  def `a history update keeps the current match index`(): Unit =
    val terminal = onFx {
      val created = TerminalPane(calls, SessionId("find-index"), TerminalMetrics.monospaced(13))
      created.attached(snapshot(Vector("alpha beta", "gamma alpha"), historyRows = 0, id = created.session))
      created.openFind()
      created.findBar.field.setText("alpha")
      created.findBar.next.fire()
      created
    }
    assertEquals(Some(1), onFx(terminal.findState)._2)
    onFx(terminal.history(Vector(row("older alpha"), row("older two"))))
    assertEquals(Some(1), onFx(terminal.findState)._2, "history arriving must not jump back to the first match")
    onFx(terminal.clearFind())
    onFx(terminal.dispose())

  @Test
  def `a session that ends drops its find state`(): Unit =
    val terminal = onFx {
      val created = TerminalPane(calls, SessionId("find-ended"), TerminalMetrics.monospaced(13))
      created.attached(snapshot(Vector("alpha beta"), historyRows = 0, id = created.session))
      created.openFind()
      created.findBar.field.setText("alpha")
      created.sessionEnded(Ended(code = Some(0), closed = false, reason = None))
      created
    }
    assertFalse(onFx(terminal.findBar.isOpen))
    assertTrue(onFx(terminal.findState)._1.isEmpty)
    onFx(terminal.dispose())

  private final class FindCalls extends SessionCalls:
    private val requests = new java.util.concurrent.ConcurrentLinkedQueue[(Int, Int)]()
    private val sizes = new java.util.concurrent.ConcurrentLinkedQueue[TermSize]()
    def historyRequests: Vector[(Int, Int)] = requests.iterator().asScala.toVector
    def resizes: Vector[TermSize] = sizes.iterator().asScala.toVector
    def reset(): Unit =
      requests.clear()
      sizes.clear()
    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit = ()
    def mouse(session: SessionId, input: MouseInput): Unit = ()
    def key(session: SessionId, input: KeyInput): Unit = ()
    def focus(session: SessionId, focused: Boolean): Unit = ()
    def resize(session: SessionId, size: TermSize): Unit =
      val _ = sizes.add(size)
    def history(session: SessionId, from: Int, count: Int): Unit =
      val _ = requests.add((from, count))
    def close(session: SessionId): Unit = ()

  private def row(text: String): Row =
    Row(text.map(character => Cell(character.toString, CellWidth.Narrow, None, None, false, false, false, false)).toVector)

  private def snapshot(rows: Vector[String], historyRows: Int, id: SessionId): Snapshot =
    val width = rows.map(_.length).maxOption.getOrElse(0)
    val padded = rows.map(text => Row(row(text).cells.padTo(width, Cell(" ", CellWidth.Narrow, None, None, false, false, false, false))))
    Snapshot(
      id = id,
      size = TermSize(cols = width, rows = padded.size),
      rows = padded,
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(alternateScreen = false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = historyRows,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private def await(condition: () => Boolean, timeoutMillis: Long): Boolean =
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var satisfied = condition()
    while !satisfied && System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition()
    satisfied

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
