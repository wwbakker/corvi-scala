package corvi

import corvi.terminal.SessionForker
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.animation.AnimationTimer
import javafx.scene.image.WritableImage
import javax.imageio.ImageIO
import scalafx.Includes.*
import scalafx.application.JFXApp3
import ox.*
import scalafx.scene.Scene

/** Renders the real application window — `AppWindow`/`CorviShell`, the same scene `Main` shows — into `out/corvi-shell.png`, then exits:
  *
  * ./mill app.runMain corvi.SnapshotMain
  *
  * This machine has no Screen Recording permission, so `screencapture` fails and `osascript` times out; this harness is how the UI is
  * looked at. It snapshots after the window is shown and the next pulse has painted it, and refuses to write a blank image rather than
  * leave a stale-looking PNG behind. Deliberately not `mainClass`: `corvi.Main` is the application. The PNG is encoded from the raw pixels
  * rather than through JavaFX's swing bridge, so the app needs no `javafx.swing` at runtime.
  */
private[corvi] object SnapshotMain extends JFXApp3:
  private val snapshot: Path = Paths.get("out", "corvi-shell.png")

  /** About five seconds of pulses to wait for a shell to write its prompt before showing what there is; the blank check behind it is what
    * decides whether the PNG is worth writing.
    */
  private val timeoutFrames: Int = 300

  /** How many consecutive pulses the terminal's screen has to stop changing before the capture. The shell redraws its prompt once the
    * widget has told the PTY the real window size — half a second in — and the canvas repaints on its own schedule, so "the first output
    * has arrived" is too early: the PNG then depends on where the resize landed and differs from run to run. A second of stillness covers
    * both.
    */
  private val settleFrames: Int = 60

  /** The scope that watches this harness's session. A `JFXApp3` does its work on the FX thread, which cannot fork, so the scope is opened
    * on a thread of its own and the shell is handed the bridge to it — the shape `Main` uses, minus the scope owning the app's own body.
    * `never` keeps that thread (and the scope) alive until the process exits; the harness shuts the session down itself, so nothing is left
    * to clean up.
    */
  private val forker: java.util.concurrent.atomic.AtomicReference[SessionForker | Null] =
    new java.util.concurrent.atomic.AtomicReference(null)
  private val forkerReady: CountDownLatch = new CountDownLatch(1)
  private val _ = Thread
    .ofPlatform()
    .name("corvi-snapshot-scope")
    .start(() =>
      supervised {
        forker.set(SessionForker.inScope)
        forkerReady.countDown()
        never
      }
    )

  /** Waits for the scope's bridge, and says so loudly if the scope never came up: a silent `null` here would surface much later as a
    * missing terminal.
    */
  private def awaitForker(): SessionForker =
    if !forkerReady.await(10, TimeUnit.SECONDS) then throw new IllegalStateException("the snapshot's scope did not start")
    Option(forker.get).getOrElse(throw new IllegalStateException("the snapshot's scope started without a forker"))

  override def start(): Unit =
    val shell = CorviShell.open(awaitForker())
    val window = AppWindow.scene(shell)
    stage = AppWindow.stage(window, () => shell.shutdown())
    stage.onShown = () => render(shell, window)

  /** The window is up. Wait for the terminal's shell to have written its prompt, give its canvas the pulses to paint it, then capture:
    * polled rather than slept, so the PTY takes as long as it takes.
    */
  private def render(shell: CorviShell, window: Scene): Unit =
    var frames = 0
    var settled = 0
    var previous = ""
    val timer = new AnimationTimer:
      override def handle(now: Long): Unit =
        frames += 1
        val screen = shell.selectedScreen
        settled = if screen.nonEmpty && screen == previous then settled + 1 else 0
        previous = screen
        if settled >= settleFrames || frames >= timeoutFrames then
          stop()
          write(shell, window)
    timer.start()

  private def write(shell: CorviShell, window: Scene): Unit =
    val focusOwner = Option(window.getFocusOwner).map(owner => owner.getClass.getSimpleName).getOrElse("none")
    println(s"the keyboard is in: $focusOwner")
    val buffered = toBufferedImage(window.snapshot(null))
    // Same order the app quits in: the shell is stopped before the process goes, so a harness that
    // exits with System.exit cannot leave one behind either.
    shell.shutdown()
    if isBlank(buffered) then
      Console.err.println("SnapshotMain: the render is blank, so nothing was written")
      System.exit(1)
    else
      val _ = Files.createDirectories(snapshot.getParent)
      ImageIO.write(buffered, "png", snapshot.toFile)
      println(s"wrote ${buffered.getWidth}×${buffered.getHeight} to ${snapshot.toAbsolutePath}")
      // The terminal's emulator threads outlive the JavaFX toolkit, so a tool that renders once
      // stops the process rather than waiting for a window nobody is watching.
      System.exit(0)

  private def toBufferedImage(image: WritableImage): BufferedImage =
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = image.getPixelReader
    for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
    buffered

  /** One colour everywhere is what a snapshot taken before the first paint looks like — and what a scene whose CSS never loaded looks like,
    * too. Sizes are checked first: a scene that never got laid out snapshots to nothing rather than to pixels.
    */
  private def isBlank(image: BufferedImage): Boolean =
    if image.getWidth == 0 || image.getHeight == 0 then true
    else
      val first = image.getRGB(0, 0)
      val pixels =
        (0 until image.getWidth).iterator.flatMap(x => (0 until image.getHeight).iterator.map(y => image.getRGB(x, y)))
      pixels.forall(_ == first)
