package corvi.app

import corvi.contract.{Damage, Ended, SessionId}
import corvi.protocol.{ClientRoutes, FrameSink, HostPaths, SocketWire, TerminalClient, TerminalError}
import corvi.renderer.{TerminalMetrics, TerminalView}
import corvi.ui.ShellView
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.{Node, Parent, Scene}
import javafx.scene.control.{ContextMenu, Label, MenuItem}
import javafx.scene.input.{ContextMenuEvent, KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import javafx.scene.layout.FlowPane
import javafx.stage.{Stage, Window}
import kyo.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{AfterAll, BeforeAll, MethodOrderer, Order, Test, TestInstance, TestMethodOrder}
import org.testfx.api.FxToolkit
import scala.concurrent.duration.*

/** The window's regression suite: the real daemon, the real client, the real shell, one stage — the same path `./mill --no-daemon app.run`
  * takes — driven through JavaFX's own event dispatch.
  *
  * The scenario is one window's life, and each phase is its own `@Test` over one shared `AppWindow`, so a failure names the step (the tab
  * whose selection did not move, the bell that did not ring) instead of the whole flow. The phases run in order: the window appears at
  * once, the prompt paints, `new` opens a second tab, every tab selects and shows its own terminal, the context menu closes one, a
  * background tab bells, `exit` marks its tab ended, and control keys interrupt and end a shell.
  *
  * Synthetic dispatch rather than TestFX's glass robot: on macOS the robot needs Accessibility permission for the process, and where it is
  * not granted the events are dropped silently instead of failing, which would make this suite hang rather than fail. TestFX starts the
  * toolkit (`FxToolkit`); the events are the ones a person's click sends.
  */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(classOf[MethodOrderer.OrderAnnotation])
final class UiTest:

  /** The window under test and its two facts: the stage, whether the placeholder was what it first showed, and the shell once it is up.
    * Holders rather than `var`s: `@BeforeAll` writes them, the phases read them.
    */
  private val window = AtomicReference[Option[AppWindow]](None)
  private val stage = AtomicReference[Option[Stage]](None)
  private val shell = AtomicReference[Option[ShellView]](None)
  private val placeholderShown = AtomicReference(false)
  private val waitTimeout: scala.concurrent.duration.Duration = 60.seconds

  @BeforeAll
  def startWindow(): Unit =
    assertNotNull(
      java.lang.System.getenv("CORVI_HOST_SOCKET"),
      "the test run has a socket of its own, so it cannot touch the user's daemon"
    )
    val primary = FxToolkit.registerPrimaryStage()
    stage.set(Some(primary))
    FxToolkit.setupFixture(new Runnable {
      def run(): Unit =
        resetHostDirectory()
        val app = AppWindow(primary, TerminalMetrics.monospaced(13), java.lang.System.getProperty("user.dir"))
        window.set(Some(app))
        // Read before the client's answers can swap the scene: `connect` only schedules the shell, so this is the first frame it showed.
        placeholderShown.set(primary.getScene.getRoot.lookup(".status-view") != null)
        app.connect()
    })

  @AfterAll
  def stopWindow(): Unit =
    window.get().foreach(app => onFx(app.close()))
    keepHostLog()
    stopHost()
    FxToolkit.cleanupStages()

  @Test
  @Order(1)
  def `the window opens once, with the placeholder before the host answers`(): Unit =
    assertEquals(1, showingWindows(), "there is exactly one window")
    assertTrue(placeholderShown.get(), "the placeholder is what the window shows while it connects")

  @Test
  @Order(2)
  def `the shell replaces the placeholder and paints its prompt`(): Unit =
    val shell = awaitShell()
    awaitSelectedPrompt(shell)
    assertEquals(3, tabs(shell).size, "Overview, one window, and new")
    assertEquals(1, currentTabIndex(shell), "the first window is the current tab (the strip's index 1, after Overview)")

  @Test
  @Order(3)
  def `the new tab opens its own terminal and takes the keyboard`(): Unit =
    val shell = shellOf
    click(tab(shell, tabs(shell).size - 1))
    awaitTabs(shell, 4)
    assertEquals(4, tabs(shell).size, "Overview, two windows, and new")
    assertTrue(onFx(tab(shell, 2).getStyleClass.contains("current")), "the new window is current")
    assertEquals(2, terminalViews(shell).size, "both terminals stay mounted")
    assertEquals(1, terminalViews(shell).count(_.isVisible), "only one terminal is on screen")
    awaitFocus(shell, sessionOf(shell, 2))
    // A blank second terminal is the original report: focus and visibility alone would pass, the frame is the claim.
    awaitSelectedPrompt(shell)

  @Test
  @Order(4)
  def `every tab selects its own terminal, in both directions`(): Unit =
    val shell = shellOf
    // The regression: clicking back to the first tab has to move the strip's current tab, the visible terminal and the keyboard.
    click(tab(shell, 1))
    assertEquals(1, currentTabIndex(shell), "the first window is current after clicking its tab")
    assertTrue(onFx(viewOf(shell, sessionOf(shell, 1)).exists(_.isVisible)), "its terminal is the one on screen")
    assertFalse(onFx(viewOf(shell, sessionOf(shell, 2)).exists(_.isVisible)), "the other terminal is hidden")
    awaitFocus(shell, sessionOf(shell, 1))
    awaitSelectedPrompt(shell)
    click(tab(shell, 2))
    assertEquals(2, currentTabIndex(shell), "the second window is current again")
    assertTrue(onFx(viewOf(shell, sessionOf(shell, 2)).exists(_.isVisible)), "its terminal is the one on screen")
    awaitSelectedPrompt(shell)

  @Test
  @Order(5)
  def `the context menu closes a tab and its terminal`(): Unit =
    val shell = shellOf
    val closing = sessionOf(shell, 2)
    rightClick(tab(shell, 2))
    assertTrue(fireLastMenuItem(), "the context menu offered an item")
    awaitTabs(shell, 3)
    assertEquals(3, tabs(shell).size, "the tab is gone")
    assertEquals(1, terminalViews(shell).size, "and its terminal is not in the well any more")
    assertEquals(None, onFx(viewOf(shell, closing)), "the closed session has no view")

  @Test
  @Order(6)
  def `output in a background tab bells it, and never the selected one`(): Unit =
    val shell = shellOf
    click(tab(shell, tabs(shell).size - 1))
    awaitTabs(shell, 4)
    val background = sessionOf(shell, 1)
    typeInto(shell, background, "printf 'BELL_%s\n' RING\r")
    assertTrue(await(waitTimeout)(bellOn(shell, 1)), "the background tab carries the bell")
    assertFalse(bellOn(shell, 2), "the selected tab stays quiet")

  @Test
  @Order(7)
  def `a shell that exits marks its tab ended, with its code and no bell`(): Unit =
    val shell = shellOf
    val background = sessionOf(shell, 1)
    typeInto(shell, background, "exit\r")
    assertTrue(await(waitTimeout)(onFx(tab(shell, 1).getStyleClass.contains("ended"))), "the tab says ended")
    assertTrue(labelOf(shell, 1).exists(_.contains("(exit ")), s"the label carries the code: ${labelOf(shell, 1)}")
    assertFalse(bellOn(shell, 1), "an ended tab does not bell")

  @Test
  @Order(8)
  def `control keys interrupt a blocked command and end the shell`(): Unit =
    val shell = shellOf
    val scene = onFx(stageOf.getScene)
    // The strip is `Overview | windows… | new`, so the last child is `new` and the window it opens is the second to last.
    val beforeControl = tabs(shell).size
    click(tab(shell, beforeControl - 1))
    awaitTabs(shell, beforeControl + 1)
    val controlSession = sessionOf(shell, beforeControl - 1)
    awaitFocus(shell, controlSession)
    // The next command only runs if ^C actually reached the PTY: otherwise the shell is still inside the blocking command and echoes the line.
    typeInto(shell, controlSession, "printf 'STARTED_%s\n' ONE; sleep 100\r")
    assertTrue(await(waitTimeout)(frameHas(shell, controlSession, "STARTED_ONE")), "the shell started a command that then blocks")
    pressKey(scene, KeyCode.C, control = true, meta = false, shift = false)
    typeInto(shell, controlSession, "printf 'AFTER_%s\n' INT\r")
    assertTrue(
      await(waitTimeout)(frameHas(shell, controlSession, "AFTER_INT")),
      "^C interrupted the blocked command and the prompt took input again"
    )
    pressKey(scene, KeyCode.D, control = true, meta = false, shift = false)
    assertTrue(await(waitTimeout)(onFx(viewOf(shell, controlSession).exists(_.ended.isDefined))), "^D at the prompt ended the session")

  @Test
  @Order(9)
  def `the run still has exactly one window`(): Unit =
    assertEquals(1, showingWindows(), "nothing above opened a second window")

  // --- the window, on the FX thread --------------------------------------------------------------------------------------------------

  private def shellOf: ShellView = shell.get().getOrElse(fail("the shell is not up"))
  private def stageOf: Stage = stage.get().getOrElse(fail("no stage"))

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

  private def awaitShell(): ShellView =
    assertTrue(await(waitTimeout)(window.get().exists(_.shell.isDefined)), "the shell replaced the placeholder")
    val found = window.get().flatMap(_.shell).getOrElse(fail("no shell in the window"))
    shell.set(Some(found))
    found

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
        val first = view.frame.flatMap(frame => frame.rows.map(row => row.cells.map(_.text).mkString.trim).find(_.nonEmpty))
        s"${view.session.value} visible=${view.isVisible} size=${view.frame.map(_.size)} first=${first.getOrElse("<blank>")} error=${view.error}"
      }
      .mkString(" | ")

  private def awaitFocus(shell: ShellView, session: SessionId): Unit =
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

  private def currentTabIndex(shell: ShellView): Int = onFx(tabs(shell).indexWhere(_.getStyleClass.contains("current")))

  private def bellOn(shell: ShellView, index: Int): Boolean = onFx(tab(shell, index).lookup(".bell") != null)

  private def labelOf(shell: ShellView, index: Int): Option[String] =
    onFx(Option(tab(shell, index).lookup(".label")).collect { case label: Label => label.getText })

  private def terminalViews(shell: ShellView): Vector[TerminalView] =
    onFx {
      shell.delegate.lookup(".terminal-area") match
        case area: Parent => area.getChildrenUnmodifiable.toArray.toVector.collect { case view: TerminalView => view }
        case _            => Vector.empty
    }

  private def viewOf(shell: ShellView, session: SessionId): Option[TerminalView] = terminalViews(shell).find(_.session == session)

  private def sessionOf(shell: ShellView, index: Int): SessionId =
    onFx(shell.current.windows.lift(index - 1).flatMap(window => shell.current.session(window.id))).getOrElse(
      fail(s"no session for tab $index")
    )

  /** Types into a window's own terminal, through the same path a keystroke takes. */
  private def typeInto(shell: ShellView, session: SessionId, text: String): Unit =
    onFx(viewOf(shell, session)).foreach(_.typeText(text))

  /** Whether the session's screen has a line containing this text. */
  private def frameHas(shell: ShellView, session: SessionId, text: String): Boolean =
    onFx(
      viewOf(shell, session).flatMap(_.frame).exists(frame => frame.rows.exists(row => row.cells.map(_.text).mkString.contains(text)))
    )

  /** A key the way a person sends it: at the window's focus owner, so the scene's chord filter sees it first and then the terminal's own
    * key handler.
    */
  private def pressKey(scene: Scene, code: KeyCode, control: Boolean, meta: Boolean, shift: Boolean): Unit =
    val _ = onFx {
      val target = Option(scene.getFocusOwner).getOrElse(scene.getRoot)
      Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, meta))
    }

  // --- the events a person sends -----------------------------------------------------------------------------------------------------

  private def click(node: Node): Unit =
    fire(node, mouseEvent(MouseEvent.MOUSE_PRESSED, Some(node)))
    fire(node, mouseEvent(MouseEvent.MOUSE_RELEASED, Some(node)))
    fire(node, mouseEvent(MouseEvent.MOUSE_CLICKED, Some(node)))

  private def rightClick(node: Node): Unit =
    fire(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, new PickResult(node, 0, 0)))

  private def fire(node: Node, event: Event): Unit = onFx { val _ = Event.fireEvent(node, event) }

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
