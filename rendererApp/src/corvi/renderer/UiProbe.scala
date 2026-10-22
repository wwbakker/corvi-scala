package corvi.renderer

import corvi.contract.*
import corvi.host.HostSession
import corvi.protocol.TerminalError
import corvi.ui.{Ending, Selection as TabSelection, ShellView, Stylesheet, TerminalWindow, Theme, WindowId}
import kyo.Frame as KyoFrame
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node as JfxNode
import javafx.scene.Scene
import javafx.scene.control.{ContextMenu, Label, MenuItem}
import javafx.scene.image.WritableImage
import javafx.scene.input.{ContextMenuEvent, KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import javafx.scene.layout.{BackgroundFill, FlowPane, Region}
import javafx.stage.{Stage, Window}
import javax.imageio.ImageIO
import kyo.*
import scala.concurrent.duration.*

/** The shell's regression suite: a real host, the real renderer views, and the real JavaFX events a person would send — clicks on tabs, the
  * chords, a right-click and its menu, a drag, and a shell exiting under a background tab.
  *
  * ./mill -i rendererApp.runMain corvi.renderer.UiProbe
  */
object UiProbe:
  private val waitTimeout: scala.concurrent.duration.Duration = 30.seconds
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(16.millis)

  def main(args: Array[String]): Unit =
    val finished = CountDownLatch(1)
    val failures = AtomicInteger(0)
    Platform.startup(() => ())
    val thread = Thread.ofPlatform().name("corvi-ui-probe").daemon(true)
    val _ = thread.start { () =>
      failures.set(run())
      finished.countDown()
    }
    finished.await()
    val failed = failures.get()
    println(if failed == 0 then "all ui checks passed" else s"$failed ui check(s) failed")
    Platform.exit()
    java.lang.System.exit(if failed == 0 then 0 else 1)

  private def run(): Int =
    val reporter = Reporter()
    val running = Harness.start(100, 28, pushInterval, HostSession.loginShell)
    var stage: Option[Stage] = None
    try
      val sessions = listSessions(running).getOrElse(fail("the harness host answered no list"))
      val metrics = onFx(TerminalMetrics.monospaced(13))
      // The client, not a service and a sink picked separately: a view only hears its session's pushes through the sink the host was wired
      // with, so the factory takes the pair (`Harness` is where it is made).
      val client = running.client
      val sink = client.sink
      val factory = RendererViewFactory(client, metrics, java.lang.System.getProperty("user.dir"))
      val shell = onFx {
        val built = ShellView(factory)
        sessions.headOption.foreach(built.open)
        built
      }
      val scene = onFx {
        val created = Scene(shell, 1180, 740)
        Stylesheet.attachTo(created)
        shell.installChords(created)
        created
      }
      stage = Some(onFx {
        val window = Stage()
        window.setScene(scene)
        window.show()
        window
      })

      // One window, selected, with a prompt painted by the real renderer.
      val painted = poll(waitTimeout)(
        onFx(sink.views.exists(_.frame.exists(frame => frame.rows.exists(row => row.cells.exists(_.text.nonEmpty)))))
      )
      reporter.report(
        "the shell shows the session it reattached to",
        painted && onFx(shell.current.windows.size) == 1,
        s"${onFx(shell.current.windows.size)} windows"
      )
      reporter.report(
        "the ui module's stylesheet dresses the shell",
        onFx(styled(scene, shell)),
        s"${onFx(scene.getStylesheets.toArray.toVector)} sidebar ${onFx(sidebarWidth(shell))}"
      )

      // The `new` tab, clicked: the tab appears when the host answers, and the keyboard is in it already.
      onFx(click(newTab(shell)))
      val twoWindows = poll(waitTimeout)(onFx(shell.current.windows.size) == 2)
      val fresh = onFx(shell.current.windows.last.id)
      reporter.report("clicking the new tab opens a window", twoWindows, s"${onFx(shell.current.windows.size)} windows")
      reporter.report("and the new tab takes the keyboard", onFx(focusHolds(sink, shell, scene, fresh)), focusDetail(scene))

      // Selecting by click, and with the prefix and a digit.
      onFx(click(windowTab(shell, 0)))
      val first = onFx(shell.current.windows.head.id)
      reporter.report("clicking a tab selects it", onFx(shell.current.isSelected(first)), "")
      reporter.report("and moves the keyboard into it", onFx(focusHolds(sink, shell, scene, first)), focusDetail(scene))
      onFx(press(scene, KeyCode.B, control = true, shift = false, alt = false, meta = false))
      onFx(press(scene, KeyCode.DIGIT1, control = false, shift = false, alt = false, meta = false))
      val secondSelected = poll(scala.concurrent.duration.Duration(5, "s"))(
        onFx(shell.current.selection) != TabSelection.Overview && onFx(
          shell.current.windows.lift(1).exists(w => onFx(shell.current.isSelected(w.id)))
        )
      )
      reporter.report("ctrl-b 1 selects the second window (0-based)", secondSelected, "")

      // The prefix and `c` opens a window.
      onFx(press(scene, KeyCode.B, control = true, shift = false, alt = false, meta = false))
      onFx(press(scene, KeyCode.C, control = false, shift = false, alt = false, meta = false))
      val threeWindows = poll(waitTimeout)(onFx(shell.current.windows.size) == 3)
      reporter.report("ctrl-b c opens a window", threeWindows, s"${onFx(shell.current.windows.size)} windows")

      // A digit outside the strip's range does nothing: no crash, no extra window, no moved selection.
      val beforeNine = onFx(shell.current.selection)
      onFx(press(scene, KeyCode.B, control = true, shift = false, alt = false, meta = false))
      onFx(press(scene, KeyCode.DIGIT9, control = false, shift = false, alt = false, meta = false))
      val ignored = poll(scala.concurrent.duration.Duration(1, "s"))(
        onFx(shell.current.selection) == beforeNine && onFx(shell.current.windows.size) == 3
      )
      reporter.report("ctrl-b 9 selects nothing", ignored, s"${onFx(shell.current.selection)}, ${onFx(shell.current.windows.size)} windows")

      // Output in a background tab bells it, and never the selected one.
      onFx(click(windowTab(shell, 0)))
      val background = onFx(shell.current.windows.lift(1).map(_.id))
      background.foreach { id =>
        typeInto(sink, shell, id, "printf 'BELL_%s\n' RING\r")
      }
      val belled = poll(waitTimeout)(onFx(shell.current.windows.lift(1).exists(_.activity == TerminalWindow.Activity.Unseen)))
      reporter.report("output in a background tab raises its bell", belled, s"$belled")
      reporter.report(
        "and the selected tab stays quiet",
        onFx(shell.current.windows.head.activity) == TerminalWindow.Activity.Quiet,
        ""
      )

      // A shell that exits marks its tab ended, and the tab carries how it ended.
      val ending = onFx(shell.current.windows.lift(1).map(_.id)).getOrElse(fail("no second window"))
      typeInto(sink, shell, ending, "exit\r")
      val ended = poll(waitTimeout)(onFx(shell.current.windows.lift(1).exists(_.state == TerminalWindow.State.Ended)))
      reporter.report("a shell that exits marks its tab ended", ended, s"$ended")
      val endingOf = onFx(shell.current.windows.lift(1).flatMap(_.ending))
      reporter.report(
        "a natural exit is recorded with its code",
        endingOf.exists {
          case Ending.Exited(Some(_)) => true
          case _                      => false
        },
        s"$endingOf"
      )
      reporter.report(
        "and the tab shows the code",
        onFx(tabLabelText(shell, 1)).exists(_.contains("(exit ")),
        s"${onFx(tabLabelText(shell, 1))}"
      )

      // The label cascade: an ended tab is faint while you are not looking at it, and keeps its current colour when you are.
      reporter.report(
        "an ended tab in the background is quiet",
        onFx(tabLabelFill(shell, 1)).exists(_.equals(javafx.scene.paint.Color.web("#565f6c"))),
        s"${onFx(tabLabelFill(shell, 1))}"
      )
      onFx(click(windowTab(shell, 1)))
      reporter.report(
        "while a current ended tab keeps its current colour",
        onFx(shell.current.isSelected(ending)) && onFx(tabLabelFill(shell, 1)).exists(_.equals(javafx.scene.paint.Color.web("#ffffff"))),
        s"${onFx(tabLabelFill(shell, 1))}"
      )
      onFx(click(windowTab(shell, 0)))

      // The strip's context menu closes a tab, which ends the session behind it.
      val closing = onFx(shell.current.windows.lift(2).map(_.id)).getOrElse(fail("no third window"))
      val closingSession = sessionOf(shell, closing)
      onFx(rightClick(windowTab(shell, 2)))
      val menuFired = onFx(fireCloseMenuItem())
      val closed = poll(waitTimeout)(onFx(shell.current.windows.size) == 2)
      val sessionsAfter = listSessions(running).getOrElse(Vector.empty)
      reporter.report(
        "the context menu's Close tab closes the window and ends the session",
        menuFired && closed && !sessionsAfter.exists(_.id == closingSession),
        s"fired $menuFired, windows ${onFx(shell.current.windows.size)}"
      )
      reporter.report(
        "and the closed tab leaves the client's sink",
        onFx(sink.views.size) == onFx(shell.current.windows.size),
        s"${onFx(sink.views.size)} views, ${onFx(shell.current.windows.size)} windows"
      )

      // The Overview round trip.
      onFx(click(overviewTab(shell)))
      val overview = poll(scala.concurrent.duration.Duration(5, "s"))(onFx(shell.current.selection) == TabSelection.Overview)
      onFx(click(windowTab(shell, 0)))
      val back = poll(scala.concurrent.duration.Duration(5, "s"))(onFx(shell.current.selection) != TabSelection.Overview)
      reporter.report("the Overview tab is a round trip", overview && back, s"overview $overview, back $back")

      // A window opened while another is ended: the new one is current and the ended tab does not ring for it.
      onFx(click(newTab(shell)))
      val reopened = poll(waitTimeout)(onFx(shell.current.windows.size) == 3)
      val openedId = onFx(shell.current.windows.last.id)
      reporter.report(
        "opening a window while one is ended selects it and leaves the ended one quiet",
        reopened && onFx(shell.current.isSelected(openedId)) && onFx(
          shell.current.windows.lift(1).exists(_.activity == TerminalWindow.Activity.Quiet)
        ),
        s"${onFx(shell.current.windows.size)} windows"
      )

      // A bell on the tab whose menu is open: the strip updates tabs in place, so the menu must still be there to fire. This is the check
      // that fails if a render rebuilds the strip instead.
      onFx(click(windowTab(shell, 0)))
      val belling = onFx(shell.current.windows.lift(2).map(_.id)).getOrElse(fail("no third window"))
      onFx(rightClick(windowTab(shell, 2)))
      val openBefore = onFx(menusShowing().size)
      typeInto(sink, shell, belling, "printf 'BELL_%s\n' AGAIN\r")
      val rang = poll(waitTimeout)(onFx(shell.current.windows.lift(2).exists(_.activity == TerminalWindow.Activity.Unseen)))
      val stillOpen = onFx(menusShowing().size) == openBefore && openBefore == 1
      val fired = onFx(fireCloseMenuItem())
      val shut = poll(waitTimeout)(onFx(shell.current.windows.size) == 2)
      reporter.report(
        "a bell in another tab does not close the open tab menu",
        openBefore == 1 && rang && stillOpen && fired && shut,
        s"menus $openBefore -> ${onFx(menusShowing().size)}, rang $rang, fired $fired, windows ${onFx(shell.current.windows.size)}"
      )

      // Drag-reorder with real mouse events and real pick results.
      val before = onFx(shell.windowsInOrder.map(_.value))
      val fromTab = windowTab(shell, 0)
      val toTab = windowTab(shell, 1)
      onFx {
        fire(fromTab, mouseEvent(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, Some(fromTab)))
        fire(fromTab, mouseEvent(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, Some(toTab)))
        fire(fromTab, mouseEvent(MouseEvent.MOUSE_RELEASED, MouseButton.PRIMARY, Some(toTab)))
      }
      val reordered = poll(scala.concurrent.duration.Duration(5, "s"))(onFx(shell.windowsInOrder.map(_.value)) != before)
      reporter.report("a drag carries a tab to another position", reordered, s"$before -> ${onFx(shell.windowsInOrder.map(_.value))}")

      // A prompt in the selected window, then the screenshot of the whole window.
      val selected = onFx(shell.current.selection) match
        case TabSelection.Window(id) => id
        case TabSelection.Overview   => onFx(shell.current.windows.head.id)
      typeInto(sink, shell, selected, "printf 'UI_%s\n' PROBE\r")
      val prompt = poll(waitTimeout)(
        onFx(sink.views.exists(view => view.frame.exists(frame => lines(frame).contains("UI_PROBE"))))
      )
      reporter.report("the selected terminal runs a command", prompt, "")
      val image = capture(scene)
      val target = Paths.get("out", "corvi-ui.png")
      val _ = Files.createDirectories(target.getParent)
      ImageIO.write(image, "png", target.toFile)
      reporter.report(
        "the window captures to out/corvi-ui.png",
        image.getWidth > 400 && image.getHeight > 200,
        s"${image.getWidth}×${image.getHeight}"
      )

      // Closing the selected tab: the middle, then the first, then the last, and the selection goes where the strip says it should.
      onFx(click(newTab(shell)))
      val beforeClosing = poll(waitTimeout)(onFx(shell.current.windows.size) == 3)
      onFx(click(windowTab(shell, 1)))
      val middle = onFx(shell.current.windows.lift(1).map(_.id)).getOrElse(fail("no middle window"))
      onFx(shell.handle(corvi.ui.WindowIntent.CloseWindow(middle)))
      val middleClosed = poll(waitTimeout)(onFx(shell.current.windows.size) == 2)
      val afterMiddle = onFx(shell.current.selection)
      reporter.report(
        "closing the selected middle tab selects the one that takes its place",
        beforeClosing && middleClosed && afterMiddle == TabSelection.Window(
          onFx(shell.current.windows.lift(1).map(_.id)).getOrElse(middle)
        ),
        s"$afterMiddle"
      )
      onFx(click(windowTab(shell, 0)))
      val firstClosing = onFx(shell.current.windows.head.id)
      onFx(shell.handle(corvi.ui.WindowIntent.CloseWindow(firstClosing)))
      val firstClosed = poll(waitTimeout)(onFx(shell.current.windows.size) == 1)
      val afterFirst = onFx(shell.current.selection)
      reporter.report(
        "closing the selected first tab selects the first remaining one",
        firstClosed && afterFirst == TabSelection.Window(onFx(shell.current.windows.head.id)),
        s"$afterFirst"
      )
      val lastClosing = onFx(shell.current.windows.head.id)
      onFx(shell.handle(corvi.ui.WindowIntent.CloseWindow(lastClosing)))
      val lastClosed = poll(waitTimeout)(onFx(shell.current.windows.isEmpty))
      reporter.report(
        "closing the selected last tab falls back to the Overview",
        lastClosed && onFx(shell.current.selection) == TabSelection.Overview,
        s"${onFx(shell.current.selection)}"
      )
      reporter.report("closing every tab leaves no view in the sink", onFx(sink.views.isEmpty), s"${onFx(sink.views.size)} views")

      onFx(shell.dispose())
    finally
      stage.foreach(window => onFx(window.close()))
      running.client.stop()
      running.stop()
    reporter.failures

  // --- the UI, driven by events ------------------------------------------------------------------------------------------------------

  /** The strip's children in screen order: Overview, the windows, and `new`. */
  private def tabs(shell: ShellView): Vector[JfxNode] =
    // The JavaFX node, not ScalaFX's wrapper: `lookup` on the wrapper returns wrappers, and the strip's children are the strip's own.
    val strip = shell.delegate.lookup(".window-tabs").asInstanceOf[FlowPane]
    strip.getChildren.toArray.toVector.collect { case node: JfxNode => node }

  /** Scene-graph reads go through the FX thread: the strip swaps its children there, and half of a swap is not a state to assert on. */
  private def windowTab(shell: ShellView, index: Int): JfxNode =
    onFx(tabs(shell).lift(index + 1).getOrElse(fail(s"no window tab $index")))

  private def overviewTab(shell: ShellView): JfxNode = onFx(tabs(shell).headOption.getOrElse(fail("no overview tab")))

  private def newTab(shell: ShellView): JfxNode = onFx(tabs(shell).lastOption.getOrElse(fail("no new tab")))

  private def fire(node: JfxNode, event: Event): Unit =
    val _ = Event.fireEvent(node, event)

  private def click(node: JfxNode): Unit =
    fire(node, mouseEvent(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, Some(node)))

  private def rightClick(node: JfxNode): Unit =
    fire(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, new PickResult(node, 0, 0)))

  /** The context menus on screen, in the order the toolkit opened them. */
  private def menusShowing(): Vector[ContextMenu] =
    Window.getWindows.toArray.toVector.collect { case menu: ContextMenu if menu.isShowing => menu }

  /** Fires the first item of the context menu the right-click put on screen. */
  private def fireCloseMenuItem(): Boolean =
    menusShowing().lastOption.exists(menu =>
      menu.getItems.toArray.toVector.collectFirst { case item: MenuItem => item }.exists { item =>
        item.fire()
        true
      }
    )

  private def mouseEvent(kind: javafx.event.EventType[MouseEvent], button: MouseButton, target: Option[JfxNode]): MouseEvent =
    new MouseEvent(
      kind,
      0,
      0,
      0,
      0,
      button,
      1,
      false,
      false,
      false,
      false,
      button == MouseButton.PRIMARY,
      false,
      false,
      false,
      false,
      false,
      target.map(node => new PickResult(node, 0, 0)).orNull
    )

  private def press(scene: Scene, key: KeyCode, control: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Unit =
    val _ = Event.fireEvent(
      scene,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, shift, control, alt, meta)
    )

  // --- host and FX helpers -----------------------------------------------------------------------------------------------------------

  private def sessionOf(shell: ShellView, id: WindowId): SessionId =
    onFx(shell.current.session(id)).getOrElse(fail(s"window ${id.value} has no session"))

  /** Types into a window's own view, which is the client that is attached to it: the same path a keystroke takes. */
  private def typeInto(sink: ViewSink, shell: ShellView, id: WindowId, text: String): Unit =
    val session = sessionOf(shell, id)
    onFx(sink.views.find(_.session == session)).foreach(_.typeText(text))
    ()

  /** Whether the keyboard is in that window's terminal. Selecting a window focuses its view, and the view's own focus is what draws a
    * filled cursor; a canvas never takes the keyboard itself.
    */
  private def focusHolds(sink: ViewSink, shell: ShellView, scene: Scene, id: WindowId): Boolean =
    val session = sessionOf(shell, id)
    val view = onFx(sink.views.find(_.session == session))
    val owner = Option(onFx(scene.getFocusOwner))
    view.exists(target => owner.exists(node => isInside(node, target)))

  private def focusDetail(scene: Scene): String =
    val owner = Option(onFx(scene.getFocusOwner))
    s"focus owner ${owner.map(node => node.getClass.getSimpleName).getOrElse("none")}"

  private def isInside(node: JfxNode, ancestor: JfxNode): Boolean =
    (node ne null) && ((node eq ancestor) || isInside(node.getParent, ancestor))

  /** The tab's label, and the colour the stylesheet gave it: the cascade is what tells a quiet tab from a current one. */
  private def tabLabelText(shell: ShellView, index: Int): Option[String] =
    onFx(Option(windowTab(shell, index).lookup(".label")).collect { case label: Label => label.getText })

  private def tabLabelFill(shell: ShellView, index: Int): Option[javafx.scene.paint.Paint] =
    onFx {
      // CSS lands on the next pulse: apply it now so the check sees what a user would, not what the previous pulse left.
      shell.applyCss()
      Option(windowTab(shell, index).lookup(".label")).map(_.asInstanceOf[Label].getTextFill)
    }

  private def listSessions(running: Harness.Running): Option[Vector[SessionInfo]] =
    runKyo(running.client.service.list.map(_.toVector))

  private def runKyo[A](call: A < (Async & Abort[TerminalError | Closed]))(using KyoFrame): Option[A] =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(kyo.Duration.fromScala(60.seconds))(call) match
      case Result.Success(value) => Some(value)
      case Result.Failure(error) =>
        java.lang.System.err.println(s"corvi ui probe: the host refused: $error")
        None
      case Result.Panic(error) =>
        java.lang.System.err.println(s"corvi ui probe: the host call panicked: $error")
        None

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

  private def poll(timeout: scala.concurrent.duration.Duration)(condition: => Boolean): Boolean =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && java.lang.System.nanoTime() < deadline do
      Thread.sleep(20)
      satisfied = condition
    satisfied

  private def capture(scene: Scene): BufferedImage =
    val image: WritableImage = onFx(scene.snapshot(null))
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = image.getPixelReader
    for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
    buffered

  private def lines(frame: corvi.contract.Frame): Vector[String] = frame.rows.map(row => row.cells.map(_.text).mkString.trim)

  /** The stylesheet ships with the `ui` module, so what to check is that the scene carries it *and* that the sidebar resolved its rules:
    * `--surface` (#1c1f25) as its fill, `--text` (#dfe3ea) as its header label's, and the width `Theme` asks for. A sheet that failed to
    * parse, or one loaded from somewhere else, leaves the toolkit's palette in place.
    */
  private def styled(scene: Scene, shell: ShellView): Boolean =
    shell.applyCss()
    val attached = scene.getStylesheets.toArray.toVector.exists(_.toString.endsWith("/corvi.css"))
    val sidebar = Option(shell.delegate.lookup(".sidebar")).collect { case region: Region => region }
    val background = sidebar
      .flatMap(region => Option(region.getBackground))
      .flatMap(fills => fills.getFills.toArray.toVector.collectFirst { case fill: BackgroundFill => fill.getFill })
    val header = sidebar.flatMap(region => Option(region.lookup(".sidebar-header .label"))).collect { case text: Label => text }
    attached &&
    background.exists(_.equals(javafx.scene.paint.Color.web("#1c1f25"))) &&
    header.exists(_.getTextFill.equals(javafx.scene.paint.Color.web("#dfe3ea"))) &&
    sidebar.exists(_.getWidth >= Theme.sidebarWidth)

  private def sidebarWidth(shell: ShellView): Double =
    Option(shell.delegate.lookup(".sidebar")).map(_.getBoundsInParent.getWidth).getOrElse(0.0)

  private def fail(message: String): Nothing = throw new IllegalStateException(message)

  private final class Reporter:
    private val failed = AtomicInteger(0)

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then
        val _ = failed.incrementAndGet()
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(2).foreach(line => println(s"      | $line"))

    def failures: Int = failed.get()
