package corvi.app

import corvi.contract.*
import corvi.renderer.{SessionCalls, SessionEvents, TerminalMetrics, TerminalView}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}

/** A view's two orderings: damage that arrives before its snapshot, and damage for a view that never gets one. The host pushes on a timer
  * we do not control, so the client has to survive both; the fake `SessionCalls` and a seeded `Snapshot` make each case a value, not a
  * race.
  */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PendingDamageTest:

  private val calls = KeyTestCalls()
  private val view = AtomicReference[Option[TerminalView]](None)

  @Start
  def start(stage: Stage): Unit =
    val terminal = TerminalView(calls, SessionId("pending-test"), TerminalMetrics.monospaced(13))
    stage.setScene(new Scene(terminal, 800, 400))
    stage.show()
    view.set(Some(terminal))

  @Test
  def `damage that arrives before the snapshot is applied after it`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    // The delta names the row the host will show; the seed is what the attach answer carries.
    onFx(terminal.pushDamage(terminal.session, damage(row = 0, "CHANGED")))
    onFx(terminal.attached(snapshot("original")))
    assertEquals(Some("CHANGED"), onFx(rowText(terminal, 0)), "the delta that arrived first is applied to the seed")
    assertEquals(0, terminal.pendingDamageCount, "and the buffer is empty once the seed has been applied")

  @Test
  def `damage for a view that never attaches stays bounded`(): Unit =
    val terminal = view.get().getOrElse(fail("no terminal"))
    (1 to 400).foreach(index => onFx(terminal.pushDamage(terminal.session, damage(row = 0, s"ROW$index"))))
    assertTrue(terminal.pendingDamageCount <= 128, s"the buffer is capped: ${terminal.pendingDamageCount}")
    onFx(terminal.attached(snapshot("original")))
    assertEquals(Some("ROW400"), onFx(rowText(terminal, 0)), "the newest delta still wins when the seed finally arrives")

  // --- helpers -----------------------------------------------------------------------------------------------------------------------

  private def rowText(terminal: TerminalView, row: Int): Option[String] =
    terminal.frame.flatMap(frame => frame.rows.lift(row)).map(cells => cells.cells.map(_.text).mkString.trim)

  private def cell(text: String): Cell = Cell(text, CellWidth.Narrow, None, None, false, false, false, false)

  private def snapshot(text: String): Snapshot =
    val cells = text.map(character => cell(character.toString)).toVector
    Snapshot(
      id = SessionId("pending-test"),
      size = TermSize(cols = math.max(1, cells.size), rows = 1),
      rows = Vector(Row(cells)),
      cursor = Cursor(0, 0, None, blinking = false, visible = true),
      modes = Modes(false, MouseMode.Off, MouseFormat.X10, bracketedPaste = false, title = ""),
      historyRows = 0,
      theme = TerminalTheme(Rgb(255, 255, 255), Rgb(0, 0, 0))
    )

  private def damage(row: Int, text: String): Damage =
    Damage(Vector(RowDamage(row, Row(Vector(cell(text))))), None, None, None, None)

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

  /** The fake client for a view that never reaches a host. */
  private final class KeyTestCalls extends SessionCalls:
    def attach(session: SessionId, events: SessionEvents): Unit = ()
    def text(session: SessionId, text: String): Unit = ()
    def resize(session: SessionId, size: TermSize): Unit = ()
    def history(session: SessionId, from: Int, count: Int): Unit = ()
    def close(session: SessionId): Unit = ()
