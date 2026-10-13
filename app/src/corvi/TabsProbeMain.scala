package corvi

import corvi.terminal.TerminalSession
import java.io.IOException
import javafx.application.Platform
import javafx.scene.{Node, Parent}
import javafx.scene.control.{ContextMenu, Label}
import javafx.scene.input.{ContextMenuEvent, MouseButton, MouseEvent, PickResult}
import javafx.scene.paint.{Color, Paint}
import javafx.scene.shape.SVGPath
import javafx.stage.Window
import scala.jdk.CollectionConverters.*
import scalafx.application.JFXApp3
import scalafx.stage.Stage

/** Drives `CorviShell` through the tab behaviours and reports both what the state says and what the window shows — opening, selecting,
  * closing, the bell, and the overview:
  *
  * ./mill app.runMain corvi.TabsProbeMain
  *
  * A screenshot cannot prove a transition, so every check reads the state *and* the rendered strip: a state that is right but never drawn
  * fails, and so does a strip that draws something the state does not say. A tool entry point, not the app.
  */
private[corvi] object TabsProbeMain extends JFXApp3:
  /** How long a shell may take to open and paint, and how long a state change may take to land. */
  private val openSeconds: Long = 20

  /** The probe's window and its scene root. */
  private final case class ProbeWindow(stage: Stage, root: Parent)

  /** What `corvi.css` resolves those tokens to, for the checks that read the paint a tab actually has rather than the classes it carries.
    */
  private val faint: String = Color.web("#565f6c").toString
  private val error: String = Color.web("#f85149").toString
  private val onAccent: String = Color.web("#ffffff").toString

  override def start(): Unit =
    JFXApp3.AutoShow = false
    Platform.setImplicitExit(false)
    val _ = Thread.ofPlatform().name("corvi-tabs-probe").start(() => run())

  private def run(): Unit =
    var failures = 0
    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then failures += 1
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(6).foreach(line => println(s"      | $line"))

    val shell = FxProbe.onFx(new CorviShell)
    val window = FxProbe.onFx(openWindow(shell))
    try
      // The window opens with one terminal of its own, so there is a prompt to type at.
      val prompt = FxProbe.awaitTrue(shellHasOutput(shell), openSeconds)
      report("the shell opens with a terminal that has painted its prompt", prompt, summary(state(shell)))

      val one = state(shell)
      report(
        "one window exists, it is the selected one, and its shell is quiet",
        one.windows.map(_.id) == Vector(0) && one.selection == Selection.Window(0) &&
          one.windows.head.state == TerminalWindow.State.Idle && one.windows.head.activity == TerminalWindow.Activity.Quiet,
        summary(one)
      )
      report(
        "the strip is Overview | that window | new, and the window is current",
        tabLabels(window) == Seq("Overview", "terminal 1", "new") && tabClasses(window)(1).contains("current") && bellCount(window) == 0,
        tabLabels(window).mkString(" | ")
      )
      report(
        "its pane is mounted in the well, visible, and holds the keyboard",
        paneMounted(shell, 0) && paneVisible(shell, 0).contains(true) && keyboardIn(window, session(shell, 0).get),
        paneState(shell, window)
      )

      // Opening a second one by clicking the `new` end, the way a user does: it becomes the window on
      // screen, and the first stays mounted.
      val (newClicked, newFocused) = clickTabAndFocus(window, index = 2, shell)
      val prompt2 = FxProbe.awaitTrue(shellHasOutput(shell), openSeconds)
      val two = state(shell)
      report(
        "clicking new opens a second terminal and selects it",
        newClicked && two.windows.map(_.id) == Vector(0, 1) && two.selection == Selection.Window(1) && prompt2,
        summary(two)
      )
      report(
        "the strip shows both windows, the second current",
        tabLabels(window) == Seq("Overview", "terminal 1", "terminal 2", "new") &&
          !tabClasses(window)(1).contains("current") && tabClasses(window)(2).contains("current"),
        tabLabels(window).mkString(" | ")
      )
      report(
        "both panes stay mounted, and only the selected one is visible",
        paneMounted(shell, 0) && paneMounted(shell, 1) && paneVisible(shell, 0).contains(false) && paneVisible(shell, 1).contains(true),
        paneState(shell, window)
      )
      report(
        "the new terminal has the keyboard without being clicked",
        newFocused && keyboardIn(window, session(shell, 1).get),
        paneState(shell, window)
      )

      // Behaviour 3: the first shell keeps running while you look at the second.
      typeOn(shell, 0, "echo CORVI_BG_$((6*7))_OK")
      val background = FxProbe.awaitTrue(screenOf(shell, 0).contains("CORVI_BG_42_OK"), openSeconds)
      report("the shell in the unselected window keeps running", background, lastLines(screenOf(shell, 0)))

      // Behaviour 7: its output raises the bell, and only its tab has one.
      val rung = FxProbe.awaitTrue(
        state(shell).window(0).exists(_.activity == TerminalWindow.Activity.Unseen),
        openSeconds
      )
      report(
        "output in the unselected window raises its bell",
        rung && bellCount(window) == 1 && tabClasses(window)(1).contains("window-tab"),
        s"bell regions: ${bellCount(window)}"
      )

      // Behaviour 3: clicking that window's tab shows it, clears its bell and takes the keyboard.
      val tabClicked = clickTab(window, index = 1)
      val selected = state(shell)
      report(
        "clicking its tab clears the bell and shows that window",
        tabClicked && selected.selection == Selection.Window(0) && selected.window(0).exists(_.activity == TerminalWindow.Activity.Quiet) &&
          paneVisible(shell, 0).contains(true) && paneVisible(shell, 1).contains(false) && bellCount(window) == 0,
        summary(selected)
      )
      report(
        "and the keyboard is back in its terminal",
        keyboardIn(window, session(shell, 0).get),
        paneState(shell, window)
      )

      // Behaviour 10: the Overview tab is the way out of a terminal, and back into one.
      val overviewClicked = clickTab(window, index = 0)
      val onOverview = state(shell)
      report(
        "clicking Overview with windows open shows the placeholder and hides every terminal",
        overviewClicked && onOverview.selection == Selection.Overview && overviewShown(window) &&
          onOverview.windows.forall(each => paneVisible(shell, each.id).contains(false)),
        summary(onOverview)
      )
      val backClicked = clickTab(window, index = 1)
      report(
        "clicking a window's tab brings that terminal back",
        backClicked && state(shell).selection == Selection.Window(0) && paneVisible(shell, 0).contains(true) && !overviewShown(window),
        summary(state(shell))
      )

      // Output in the window you *are* looking at changes nothing: no bell, and the state comes back
      // as the very same value, so the strip is not re-rendered either.
      val stripBefore = stripNode(window)
      val stateBefore = state(shell)
      typeOn(shell, 0, "echo CORVI_SEEN_$((6*7))_OK")
      val seen = FxProbe.awaitTrue(screenOf(shell, 0).contains("CORVI_SEEN_42_OK"), openSeconds)
      report(
        "output in the selected window raises no bell and changes nothing",
        seen && (state(shell) eq stateBefore) && bellCount(window) == 0 && (stripNode(window) == stripBefore),
        summary(state(shell))
      )

      // Behaviour 6: a shell that exits marks its own window, and nothing else.
      typeOn(shell, 1, "exit")
      val ended = FxProbe.awaitTrue(
        state(shell).window(1).exists(_.state == TerminalWindow.State.Ended),
        openSeconds
      )
      report(
        "the shell that exits marks its window ended",
        ended && !session(shell, 1).exists(_.isAlive) && state(shell).window(0).exists(_.state == TerminalWindow.State.Idle),
        summary(state(shell))
      )
      report("the ended window's tab says so", tabClasses(window)(2).contains("ended"), tabClasses(window)(2).mkString(" "))
      report(
        "an ended tab that is not current paints a red icon and a quiet label",
        FxProbe.awaitTrue(tabLook(window, 2) == (faint, error), openSeconds),
        s"label ${tabLook(window, 2)._1}, icon ${tabLook(window, 2)._2}"
      )
      val endedClicked = clickTab(window, index = 2)
      report(
        "and the current ended tab keeps the current label colour, with its icon still red",
        endedClicked && FxProbe.awaitTrue(tabLook(window, 2) == (onAccent, error), openSeconds),
        s"label ${tabLook(window, 2)._1}, icon ${tabLook(window, 2)._2}"
      )

      // The transitions that are meant to change nothing come back as the same value — which is what
      // keeps JavaFX from re-rendering, and what a missing id must not do to the well.
      FxProbe.onFx(shell.select(Selection.Window(0)))
      val pure = state(shell)
      val endedId = pure.windows.last.id
      val selectedId = pure.selection match
        case Selection.Window(id) => id
        case Selection.Overview   => -1
      val noOps = List(
        "activity in the window on screen" -> (pure.activity(selectedId) eq pure),
        "activity in an ended window" -> (pure.activity(endedId) eq pure),
        "activity in a window that is not there" -> (pure.activity(99) eq pure),
        "closing a window that is not there" -> (pure.closed(99) eq pure),
        "selecting a window that is not there" -> (pure.selected(Selection.Window(99)) eq pure),
        "selecting what is already on screen" -> (pure.selected(pure.selection) eq pure)
      )
      report(
        "the no-op transitions come back as the same state",
        noOps.forall(_._2),
        noOps.filterNot(_._2).map(_._1).mkString(", ")
      )

      // Closing: the shell goes, the pane goes, the neighbour stays.
      val closedPane = FxProbe.onFx(shell.shellState.session(1).map(_.pane.delegate))
      val menuClosed = closeTabFromMenu(window, index = 2)
      val unmounted = FxProbe.awaitTrue(closedPane.exists(_.getParent == null), 10)
      val closed = unmounted && state(shell).windows.map(_.id) == Vector(0)
      report(
        "the tab's Close tab kills its shell, unmounts its pane and leaves the other alone",
        menuClosed && closed && !session(shell, 1).isDefined && session(shell, 0).exists(_.isAlive) &&
          state(shell).selection == Selection.Window(0),
        summary(state(shell))
      )
      report(
        "the strip drops its tab",
        tabLabels(window) == Seq("Overview", "terminal 1", "new"),
        tabLabels(window).mkString(" | ")
      )

      // Behaviour 10: the way back to the overview.
      val lastClosed = closeTabFromMenu(window, index = 1)
      val empty = state(shell)
      report(
        "closing the last window returns to the overview",
        lastClosed && empty.windows.isEmpty && empty.selection == Selection.Overview && overviewShown(window),
        summary(empty)
      )

      // Where the selection goes when windows close, one case per position: the window that takes the
      // closed one's place, the one before it when the last goes, and no change at all for a window
      // you were not looking at.
      val w1 = openWindowId(shell)
      val w2 = openWindowId(shell)
      val w3 = openWindowId(shell)
      val w4 = openWindowId(shell)
      FxProbe.onFx(shell.select(Selection.Window(w2)))
      FxProbe.onFx(shell.closeTerminal(w2))
      report(
        "closing the selected middle selects the window that takes its place",
        state(shell).selection == Selection.Window(w3),
        summary(state(shell))
      )
      FxProbe.onFx(shell.select(Selection.Window(w1)))
      FxProbe.onFx(shell.closeTerminal(w1))
      report("closing the selected first does the same", state(shell).selection == Selection.Window(w3), summary(state(shell)))
      FxProbe.onFx(shell.select(Selection.Window(w4)))
      FxProbe.onFx(shell.closeTerminal(w4))
      report(
        "closing the selected last falls back to the window before it",
        state(shell).selection == Selection.Window(w3),
        summary(state(shell))
      )
      val w5 = openWindowId(shell)
      FxProbe.onFx(shell.closeTerminal(w3))
      report(
        "closing a window you are not looking at leaves the selection alone",
        state(shell).selection == Selection.Window(w5),
        summary(state(shell))
      )
      FxProbe.onFx(shell.closeTerminal(w5))
      report("and closing the last one of all returns to the overview", state(shell).selection == Selection.Overview, summary(state(shell)))

      // Ending the session's own life: `shutdown` takes every shell and every emulator thread with it.
      FxProbe.onFx(shell.openTerminal())
      FxProbe.onFx(shell.openTerminal())
      val reopened = FxProbe.awaitTrue(LiveProcesses.childShells.size == 2, openSeconds)
      report("the shell opens terminals again after the well is empty", reopened, s"child shells: ${LiveProcesses.childShells.size}")
      FxProbe.onFx(shell.shutdown())
      val shellsGone = FxProbe.awaitTrue(LiveProcesses.childShells.isEmpty, 10)
      report("shutdown ends every session's shell", shellsGone, LiveProcesses.childShells.map(_.pid.toString).mkString(", "))
      val threadsGone = FxProbe.awaitTrue(LiveProcesses.sessionThreads.isEmpty, 10)
      report(
        "and leaves no TerminalEmulator/JediTermFX thread behind",
        threadsGone,
        if threadsGone then "none" else LiveProcesses.sessionThreads.mkString(", ")
      )
    catch
      case failure: Throwable =>
        failures += 1
        println(s"FAIL  the probe threw: $failure")
        failure.getStackTrace.take(8).foreach(frame => println(s"      | $frame"))
    finally
      FxProbe.onFx(window.stage.close())
      FxProbe.onFx(shell.shutdown())

    println(if failures == 0 then "all tab checks passed" else s"$failures tab check(s) failed")
    if failures == 0 then Platform.exit() else System.exit(1)

  /** A window around the shell, wired the way `Main` wires it. */
  private def openWindow(shell: CorviShell): ProbeWindow =
    val stage = AppWindow.stage(AppWindow.scene(shell), () => shell.shutdown())
    stage.show()
    ProbeWindow(stage, stage.delegate.getScene.getRoot)

  /** Opens a terminal and answers with the id it was given. */
  private def openWindowId(shell: CorviShell): Int =
    FxProbe.onFx(shell.openTerminal())
    state(shell).windows.last.id

  /** The shell's state, read on the FX thread. */
  private def state(shell: CorviShell): ShellState = FxProbe.onFx(shell.shellState)

  private def session(shell: CorviShell, id: Int): Option[TerminalSession] = FxProbe.onFx(shell.shellState.session(id))

  private def shellHasOutput(shell: CorviShell): Boolean = FxProbe.onFx(shell.terminalHasOutput)

  // These read `shellState` directly inside the FX block: the helpers that wrap `onFx` must stay one
  // hop, or the FX thread waits for itself.
  private def screenOf(shell: CorviShell, id: Int): String =
    FxProbe.onFx(shell.shellState.session(id).map(_.screenText).getOrElse(""))

  private def paneMounted(shell: CorviShell, id: Int): Boolean =
    FxProbe.onFx(shell.shellState.session(id).exists(_.pane.delegate.getParent != null))

  private def paneVisible(shell: CorviShell, id: Int): Option[Boolean] =
    FxProbe.onFx(shell.shellState.session(id).map(_.pane.visible.value))

  private def overviewShown(window: ProbeWindow): Boolean =
    FxProbe.onFx(descendants(window.root).exists(node => node.getStyleClass.contains("overview-placeholder") && node.isVisible))

  /** Whether the keyboard is inside that terminal's node. */
  private def keyboardIn(window: ProbeWindow, session: TerminalSession): Boolean =
    val owner = window.stage.delegate.getScene.getFocusOwner
    owner != null && ancestors(owner).exists(_ eq session.pane.delegate)

  private def typeOn(shell: CorviShell, id: Int, command: String): Unit =
    try FxProbe.onFx(shell.shellState.session(id).foreach(_.tty.write(command + "\n")))
    catch case e: IOException => throw new IllegalStateException(s"writing [$command] to window $id failed", e)

  private def summary(current: ShellState): String =
    val windows = current.windows.map(window => s"${window.id}:${window.label}:${window.state}:${window.activity}").mkString(", ")
    s"windows [$windows], selection ${current.selection}"

  private def paneState(shell: CorviShell, window: ProbeWindow): String =
    FxProbe.onFx {
      val current = shell.shellState
      val panes = current.windows.map { each =>
        val pane = current.session(each.id).map(_.pane.delegate)
        s"${each.id}: mounted ${pane.exists(_.getParent != null)}, visible ${pane.exists(_.isVisible)}"
      }
      val owner = window.stage.delegate.getScene.getFocusOwner
      (panes :+ s"focus: ${if owner == null then "none" else owner.getClass.getSimpleName}").mkString("; ")
    }

  /** Clicks a tab the way a user does: a real mouse event at the node, so the strip's own wiring runs rather than the shell's method.
    */
  private def clickTab(window: ProbeWindow, index: Int): Boolean = FxProbe.onFx(fireClick(window, index))

  /** Clicks a tab and answers whether the window it selected holds the keyboard by the time the handler returns — inside the same FX turn,
    * before JavaFX's own focus resolution on the next pulse can be credited for it. Hiding the pane that had the keyboard is enough for
    * JavaFX to move focus to the one node still visible, so a later read would pass with or without the shell asking.
    */
  private def clickTabAndFocus(window: ProbeWindow, index: Int, shell: CorviShell): (Boolean, Boolean) =
    FxProbe.onFx {
      val clicked = fireClick(window, index)
      val focused = shell.shellState.selectedSession.exists(session => keyboardIn(window, session))
      (clicked, focused)
    }

  /** The click itself; the caller is already on the FX thread. */
  private def fireClick(window: ProbeWindow, index: Int): Boolean =
    tabNodes(window).lift(index) match
      case Some(tab) =>
        val _ = tab.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, tab))
        true
      case None => false

  /** Right-clicks a tab and takes "Close tab" from the menu that appears. */
  private def closeTabFromMenu(window: ProbeWindow, index: Int): Boolean =
    val requested = FxProbe.onFx {
      tabNodes(window).lift(index) match
        case Some(tab) =>
          val event = new ContextMenuEvent(
            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
            0,
            0,
            0,
            0,
            false,
            new PickResult(tab, 0, 0)
          )
          val _ = tab.fireEvent(event)
          true
        case None => false
    }
    requested && FxProbe.onFx {
      Window.getWindows.asScala.collectFirst { case menu: ContextMenu => menu } match
        case Some(menu) =>
          menu.getItems.get(0).fire()
          menu.hide()
          true
        case None => false
    }

  private def mouseEvent(kind: javafx.event.EventType[MouseEvent], button: MouseButton, target: Node): MouseEvent =
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
      button == MouseButton.SECONDARY,
      false,
      false,
      true,
      new PickResult(target, 0, 0)
    )

  /** The paint a tab's label and icon have resolved to, or `unpainted` before CSS has landed. */
  private def tabLook(window: ProbeWindow, index: Int): (String, String) =
    FxProbe.onFx {
      tabNodes(window).lift(index) match
        case Some(tab) =>
          val label = descendants(tab).collectFirst { case label: Label => label }
          val icon = descendants(tab).collectFirst { case path: SVGPath => path }
          (paint(label.map(_.getTextFill)), paint(icon.map(_.getStroke)))
        case None => ("missing", "missing")
    }

  /** A node that has not been styled yet answers `null` for its paint, which is not a colour. */
  private def paint(colour: Option[Paint]): String = colour.flatMap(value => Option(value)).map(_.toString).getOrElse("unpainted")

  /** The strip node itself, whose identity says whether the state change re-rendered it. */
  private def stripNode(window: ProbeWindow): Option[Node] =
    descendants(window.root).find(_.getStyleClass.contains("window-tabs"))

  private def tabNodes(window: ProbeWindow): Seq[Node] =
    descendants(window.root).filter(_.getStyleClass.contains("window-tab"))

  private def tabLabels(window: ProbeWindow): Seq[String] =
    tabNodes(window).map(tab => descendants(tab).collectFirst { case label: Label => label.getText }.getOrElse(""))

  private def tabClasses(window: ProbeWindow): Seq[Seq[String]] =
    tabNodes(window).map(_.getStyleClass.asScala.toSeq)

  private def bellCount(window: ProbeWindow): Int =
    descendants(window.root).count(_.getStyleClass.contains("bell"))

  private def lastLines(screen: String): String =
    screen.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(4).mkString("\n")

  private def descendants(node: Node): Seq[Node] =
    node match
      case parent: Parent => node +: parent.getChildrenUnmodifiable.asScala.toSeq.flatMap(descendants)
      case _              => Seq(node)

  private def ancestors(node: Node): Seq[Node] =
    if node == null then Seq.empty else node +: ancestors(node.getParent)
