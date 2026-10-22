package corvi.app

import corvi.contract.{Damage, Ended, SessionId}
import corvi.protocol.{ClientRoutes, FrameSink, HostPaths, SocketWire, TerminalClient, TerminalError}
import corvi.renderer.{TerminalMetrics, TerminalView}
import corvi.ui.ShellView
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.{ContextMenu, Label, MenuItem}
import javafx.scene.input.{ContextMenuEvent, MouseButton, MouseEvent, PickResult}
import javafx.scene.Parent
import javafx.scene.layout.FlowPane
import javafx.stage.{Stage, Window}
import kyo.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.{ApplicationExtension, Start}
import scala.concurrent.duration.*

/** The window's regression suite: the real daemon, the real client, the real shell, one stage — the same path `./mill --no-daemon app.run`
  * takes — driven through JavaFX's own event dispatch.
  *
  * One test, because the scenario is one: a window appears at once, the prompt paints, `new` opens a second tab, every tab selects and
  * shows its own terminal, the context menu closes one, a background tab bells, and `exit` marks its tab ended. The tab-selection steps are
  * the regression for the reported "tabs stopped working after the second terminal" bug: they click through the real press/release/click
  * sequence, which is what leaves a tab's drag state behind if the release does not clear it.
  *
  * Synthetic dispatch rather than TestFX's glass robot: on macOS the robot needs Accessibility permission for the process, and where it is
  * not granted the events are dropped silently instead of failing, which would make this suite hang rather than fail.
  * `ApplicationExtension` still starts the toolkit and owns the fixture; the events are the ones a person's click sends.
  */
@ExtendWith(Array(classOf[ApplicationExtension]))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class UiTest:

  /** The window under test. A holder rather than a field `var`: the fixture is written in `@Start`, the assertions read it in the test. */
  private val window = AtomicReference[Option[AppWindow]](None)

  private val waitTimeout: scala.concurrent.duration.Duration = 60.seconds

  /** TestFX calls this reflectively, which is why it is not private (and why it carries no arguments the compiler can check). */
  @Start
  def start(stage: Stage): Unit =
    assertNotNull(
      java.lang.System.getenv("CORVI_HOST_SOCKET"),
      "the test run has a socket of its own, so it cannot touch the user's daemon"
    )
    window.set(Some(AppWindow(stage, TerminalMetrics.monospaced(13), java.lang.System.getProperty("user.dir"))))

  @Test
  def `the window opens once, opens tabs, and every tab selects its own terminal`(): Unit =
    val app = window.get().getOrElse(fail("the window was not started"))
    val stage = onFx(Window.getWindows.toArray.toVector.collectFirst { case shown: Stage => shown }).getOrElse(fail("no stage"))
    resetHostDirectory()
    app.connect()
    try
      // One window, on screen immediately, before the host has necessarily answered: the placeholder is in it, not a black scene.
      assertEquals(1, showingWindows(), "there is exactly one window")
      assertTrue(onFx(stage.getScene.getRoot.lookup(".status-view") != null), "the placeholder is what the window shows while it connects")

      val shell = awaitShell(app)
      awaitSelectedPrompt(shell)
      assertEquals(3, tabs(shell).size, "Overview, one window, and new")
      assertEquals(1, currentTabIndex(shell), "the first window is the current tab (the strip's index 1, after Overview)")

      // `new`: a second tab, selected, with its own terminal on screen and the keyboard in it.
      click(tab(shell, tabs(shell).size - 1))
      awaitTabs(shell, 4)
      assertEquals(4, tabs(shell).size, "Overview, two windows, and new")
      assertTrue(onFx(tab(shell, 2).getStyleClass.contains("current")), "the new window is current")
      assertEquals(2, terminalViews(shell).size, "both terminals stay mounted")
      assertEquals(1, terminalViews(shell).count(_.isVisible), "only one terminal is on screen")
      awaitFocus(shell, sessionOf(shell, 2))
      // A blank second terminal is the original report: focus and visibility alone would pass, the frame is the claim.
      awaitSelectedPrompt(shell)

      // The regression: clicking back to the first tab has to move the strip's current tab, the visible terminal and the keyboard.
      click(tab(shell, 1))
      assertEquals(1, currentTabIndex(shell), "the first window is current after clicking its tab")
      assertTrue(onFx(viewOf(shell, sessionOf(shell, 1)).exists(_.isVisible)), "its terminal is the one on screen")
      assertFalse(onFx(viewOf(shell, sessionOf(shell, 2)).exists(_.isVisible)), "the other terminal is hidden")
      awaitFocus(shell, sessionOf(shell, 1))
      awaitSelectedPrompt(shell)

      // And back again, so the move works in both directions.
      click(tab(shell, 2))
      assertEquals(2, currentTabIndex(shell), "the second window is current again")
      assertTrue(onFx(viewOf(shell, sessionOf(shell, 2)).exists(_.isVisible)), "its terminal is the one on screen")
      awaitSelectedPrompt(shell)

      // The strip's context menu closes a tab, and the view goes with it.
      val closing = sessionOf(shell, 2)
      rightClick(tab(shell, 2))
      assertTrue(fireLastMenuItem(), "the context menu offered an item")
      awaitTabs(shell, 3)
      assertEquals(3, tabs(shell).size, "the tab is gone")
      assertEquals(1, terminalViews(shell).size, "and its terminal is not in the well any more")
      assertEquals(None, onFx(viewOf(shell, closing)), "the closed session has no view")

      // Output in a background tab bells it, and never the one on screen.
      click(tab(shell, tabs(shell).size - 1))
      awaitTabs(shell, 4)
      val background = sessionOf(shell, 1)
      typeInto(shell, background, "printf 'BELL_%s\n' RING\r")
      assertTrue(await(waitTimeout)(bellOn(shell, 1)), "the background tab carries the bell")
      assertFalse(bellOn(shell, 2), "the selected tab stays quiet")

      // Exit marks the tab ended, with its code and no bell.
      typeInto(shell, background, "exit\r")
      assertTrue(await(waitTimeout)(onFx(tab(shell, 1).getStyleClass.contains("ended"))), "the tab says ended")
      assertTrue(labelOf(shell, 1).exists(_.contains("(exit ")), s"the label carries the code: ${labelOf(shell, 1)}")
      assertFalse(bellOn(shell, 1), "an ended tab does not bell")

      // Still one window: nothing above opened a second one.
      assertEquals(1, showingWindows(), "the run still has exactly one window")
    finally
      onFx(app.close())
      keepHostLog()
      stopHost()

  // --- the window, on the FX thread --------------------------------------------------------------------------------------------------

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

  private def await(timeout: scala.concurrent.duration.Duration)(condition: => Boolean): Boolean =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && java.lang.System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition
    satisfied

  private def awaitShell(app: AppWindow): ShellView =
    assertTrue(await(waitTimeout)(app.shell.isDefined), "the shell replaced the placeholder")
    app.shell.getOrElse(fail("no shell in the window"))

  private def awaitTabs(shell: ShellView, count: Int): Unit =
    assertTrue(await(waitTimeout)(onFx(tabs(shell).size) == count), s"the strip has $count tabs")

  /** Waits until the terminal on screen has painted its prompt. A cold start paints in a second or two; a connection that never delivers a
    * frame is a failure worth reporting quickly, not after a minute.
    */
  private def awaitSelectedPrompt(shell: ShellView): Unit =
    val painted = await(20.seconds)(
      onFx(
        terminalViews(shell).exists(view =>
          view.isVisible && view.frame.exists(rows => rows.rows.exists(_.cells.exists(_.text.trim.nonEmpty)))
        )
      )
    )
    assertTrue(painted, s"the selected terminal painted a prompt; views: ${onFx(describeViews(shell))}")

  /** What the mounted views had, for a failure message: a seed with no prompt is a push that never arrived; no frame at all is an attach
    * that never did.
    */
  private def describeViews(shell: ShellView): String =
    terminalViews(shell)
      .map { view =>
        val first = view.frame
          .flatMap(frame => frame.rows.map(row => row.cells.map(_.text).mkString.trim).find(_.nonEmpty))
        s"${view.session.value} visible=${view.isVisible} size=${view.frame.map(_.size)} first=${first.getOrElse("<blank>")} error=${view.error}"
      }
      .mkString(" | ")

  private def awaitFocus(shell: ShellView, session: corvi.contract.SessionId): Unit =
    val focused = await(waitTimeout)(
      onFx(Option(shell.getScene).flatMap(scene => Option(scene.getFocusOwner)).exists(owner => viewOf(shell, session).exists(_ eq owner)))
    )
    assertTrue(focused, s"the keyboard is in $session's terminal")

  private def showingWindows(): Int = onFx(Window.getWindows.toArray.toVector.collect { case shown: Window => shown }.count(_.isShowing))

  /** The strip's children in order: Overview, the windows, and `new`. Read on the FX thread, where the strip swaps them. */
  private def tabs(shell: ShellView): Vector[Node] =
    onFx {
      val strip = shell.delegate.lookup(".window-tabs").asInstanceOf[FlowPane]
      strip.getChildren.toArray.toVector.collect { case node: Node => node }
    }

  private def tab(shell: ShellView, index: Int): Node = tabs(shell).lift(index).getOrElse(fail(s"no strip child $index"))

  private def currentTabIndex(shell: ShellView): Int =
    onFx(tabs(shell).indexWhere(_.getStyleClass.contains("current")))

  private def bellOn(shell: ShellView, index: Int): Boolean =
    onFx(tab(shell, index).lookup(".bell") != null)

  private def labelOf(shell: ShellView, index: Int): Option[String] =
    onFx(Option(tab(shell, index).lookup(".label")).collect { case label: Label => label.getText })

  private def terminalViews(shell: ShellView): Vector[TerminalView] =
    onFx {
      shell.delegate.lookup(".terminal-area") match
        case area: Parent => area.getChildrenUnmodifiable.toArray.toVector.collect { case view: TerminalView => view }
        case _            => Vector.empty
    }

  private def viewOf(shell: ShellView, session: corvi.contract.SessionId): Option[TerminalView] =
    terminalViews(shell).find(_.session == session)

  private def sessionOf(shell: ShellView, index: Int): corvi.contract.SessionId =
    onFx(shell.current.windows.lift(index - 1).flatMap(window => shell.current.session(window.id))).getOrElse(
      fail(s"no session for tab $index")
    )

  /** Types into a window's own terminal, through the same path a keystroke takes. */
  private def typeInto(shell: ShellView, session: corvi.contract.SessionId, text: String): Unit =
    onFx(viewOf(shell, session)).foreach(_.typeText(text))

  // --- the events a person sends -----------------------------------------------------------------------------------------------------

  private def click(node: Node): Unit =
    fire(node, mouseEvent(MouseEvent.MOUSE_PRESSED, Some(node)))
    fire(node, mouseEvent(MouseEvent.MOUSE_RELEASED, Some(node)))
    fire(node, mouseEvent(MouseEvent.MOUSE_CLICKED, Some(node)))

  private def rightClick(node: Node): Unit =
    fire(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, new PickResult(node, 0, 0)))

  private def fire(node: Node, event: Event): Unit =
    onFx { val _ = Event.fireEvent(node, event) }

  private def mouseEvent(kind: javafx.event.EventType[MouseEvent], target: Option[Node]): MouseEvent =
    new MouseEvent(
      kind,
      0,
      0,
      0,
      0,
      MouseButton.PRIMARY,
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
      target.map(node => new PickResult(node, 0, 0)).orNull
    )

  /** Fires the first item of the context menu the right-click put on screen. */
  private def fireLastMenuItem(): Boolean =
    val menus = onFx(Window.getWindows.toArray.toVector.collect { case menu: ContextMenu => menu })
    menus.lastOption.exists(menu =>
      menu.getItems.toArray.toVector.collectFirst { case item: MenuItem => item }.exists { item =>
        onFx(item.fire())
        true
      }
    )

  // --- the daemon this run started ---------------------------------------------------------------------------------------------------

  /** Keeps the daemon's own log next to the build output, so a failed run can say what the host saw. */
  private def keepHostLog(): Unit =
    val log = HostPaths.defaultPaths().directory.resolve("host.log")
    if Files.exists(log) then
      try
        val target = java.nio.file.Paths.get("out", "corvi-ui-test-host.log")
        val _ = Files.createDirectories(target.getParent)
        val _ = Files.copy(log, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      catch case _: Throwable => ()

  /** Ends the daemon the app started, so the suite leaves no process behind — and takes its directory with it, or the next run inherits a
    * stale socket, token and log.
    */
  private def stopHost(): Unit =
    val paths = HostPaths.defaultPaths()
    destroyMarked(paths)
    awaitGone(paths)
    deleteTree(paths.directory)

  /** A daemon left behind by an interrupted run must not answer this one: attaching to one that is already shutting down loses every push
    * after the attach (the host unbinds the client when its connection ends). Stop it — by its marker if there is one, by asking it
    * otherwise — and wait until nothing answers, so the app below connects to a host it started itself.
    */
  private def resetHostDirectory(): Unit =
    val paths = HostPaths.defaultPaths()
    destroyMarked(paths)
    if SocketWire.answersUnsafe(paths.socket, scala.concurrent.duration.Duration(500, "ms")) then
      val _ = stopDaemon(paths)
    val deadline = java.lang.System.nanoTime() + 15L * 1000 * 1000 * 1000
    while stillListening(paths) && java.lang.System.nanoTime() < deadline do Thread.sleep(50)
    assertFalse(stillListening(paths), s"a stale daemon is still listening on ${paths.socket}")
    deleteTree(paths.directory)

  private def stillListening(paths: HostPaths): Boolean =
    SocketWire.answersUnsafe(paths.socket, scala.concurrent.duration.Duration(200, "ms")) ||
      paths.owner().exists(pid => ProcessHandle.of(pid).isPresent)

  /** Kills the pid the owner marker names, but only if that process really is this daemon: the marker is a file, and a stale one must not
    * be allowed to take an unrelated process down. `host.stop` through the socket is the graceful path; this is the last resort.
    */
  private def destroyMarked(paths: HostPaths): Unit =
    paths.owner().foreach { pid =>
      ProcessHandle.of(pid).ifPresent { handle =>
        val named = handle.info().commandLine().orElse("").contains("corvi.host.HostMain")
        if named then
          val _ = handle.destroy()
          try
            val _ = handle.onExit().get(10, TimeUnit.SECONDS)
          catch case _: Throwable => ()
        else java.lang.System.err.println(s"corvi ui test: ${handle.pid()} is not the corvi host; leaving it alone")
      }
    }

  private def awaitGone(paths: HostPaths): Unit =
    val deadline = java.lang.System.nanoTime() + 10L * 1000 * 1000 * 1000
    while stillListening(paths) && java.lang.System.nanoTime() < deadline do Thread.sleep(20)

  /** `host.stop` through a client of its own: the daemon unlinks its socket and exits, so the next run starts clean even after this one
    * crashed. The stop route ends the process, so a `Closed` on the way out is the success path rather than a failure.
    */
  private def stopDaemon(paths: HostPaths): Boolean =
    runKyo {
      Scope.run {
        for
          closed <- Latch.init(1)
          wire <- SocketWire.connect(paths.socket, closed)
          transport <- JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength)
          handler <- JsonRpcHandler.init(transport, ClientRoutes.routes(Quiet)*)
          client <- Sync.defer(TerminalClient(handler))
          _ <- Abort.run[TerminalError | Closed] {
            for
              _ <- client.hello(paths.token())
              _ <- client.stop
            yield ()
          }
        yield ()
      }
    }.isDefined

  /** A sink that keeps nothing: the stopping client only needs the routes registered. */
  private object Quiet extends FrameSink:
    def damage(id: SessionId, damage: Damage): Unit < Sync = Sync.defer(())
    def ended(id: SessionId, ended: Ended): Unit < Sync = Sync.defer(())

  private def runKyo[A](call: A < (Async & Scope & Abort[Any])): Option[A] =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(30.seconds))(call) match
      case Result.Success(value) => Some(value)
      case Result.Failure(error) =>
        java.lang.System.err.println(s"corvi ui test: stopping a stale host failed: $error")
        None
      case Result.Panic(error) =>
        java.lang.System.err.println(s"corvi ui test: stopping a stale host panicked: $error")
        None

  private def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      val walk = Files.walk(directory)
      try walk.sorted(java.util.Comparator.reverseOrder()).forEach(path => { val _ = Files.deleteIfExists(path) })
      finally walk.close()
