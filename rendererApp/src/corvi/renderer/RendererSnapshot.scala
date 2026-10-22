package corvi.renderer

import corvi.host.HostSession
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import javafx.animation.AnimationTimer
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.stage.Stage
import javax.imageio.ImageIO
import kyo.Duration
import scala.concurrent.duration.*

/** Renders the real terminal view into `out/corvi-renderer.png` and exits.
  *
  * ./mill -i rendererApp.runMain corvi.renderer.RendererSnapshot
  *
  * This machine has no Screen Recording permission, so a harness that renders the scene is how the UI is looked at. The capture waits for
  * the screen to stop changing: the shell redraws its prompt once the PTY has the real size, and a capture taken before that depends on
  * where the resize landed.
  */
object RendererSnapshot extends scalafx.application.JFXApp3:
  private val cols = 100
  private val rows = 28
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(16.millis)

  /** About five seconds of pulses, then whatever is on screen; the blank check behind it decides whether the PNG is worth writing. */
  private val timeoutFrames: Int = 300
  private val settleFrames: Int = 60

  override def start(): Unit =
    val metrics = TerminalMetrics.monospaced(13)
    val running = Harness.start(cols, rows, pushInterval, HostSession.loginShell)
    val view = TerminalView(running.service, running.session, metrics)
    running.sink.plug(view)
    view.setPrefSize(metrics.layout.width(cols), metrics.layout.height(rows))

    val scene = Scene(view, metrics.layout.width(cols) + 16, metrics.layout.height(rows))
    val window = Stage()
    window.setScene(scene)
    window.show()
    view.setCursorBlinking(false)
    view.requestFocus()
    // The timer starts here rather than from `onShown`: a window on a machine without an active display may never report being shown, and a
    // harness that waits for that writes nothing at all.
    captureWhenStill(running, scene, view)

  private def captureWhenStill(running: Harness.Running, scene: Scene, view: TerminalView): Unit =
    var frames = 0
    var settled = 0
    var previous = ""
    val timer = new AnimationTimer:
      override def handle(now: Long): Unit =
        frames += 1
        val screen = view.screen.map(model => (0 until model.rows).map(model.rowText).mkString("\n")).getOrElse("")
        settled = if screen.nonEmpty && screen == previous then settled + 1 else 0
        previous = screen
        if settled >= settleFrames || frames >= timeoutFrames then
          stop()
          write(running, scene, view)
    timer.start()

  /** Two captures: the terminal with the keyboard, and without it, so the hollow cursor is visible to a reviewer as well as to the probe.
    */
  private def write(running: Harness.Running, scene: Scene, view: TerminalView): Unit =
    val target = Paths.get("out", "corvi-renderer.png")
    view.showFocus(true)
    val image = toBufferedImage(scene.snapshot(null))
    view.showFocus(false)
    val unfocused = toBufferedImage(scene.snapshot(null))
    running.stop()
    if isBlank(image) then
      java.lang.System.err.println("RendererSnapshot: the render is blank, so nothing was written")
      java.lang.System.exit(1)
    else
      val _ = Files.createDirectories(target.getParent)
      ImageIO.write(image, "png", target.toFile)
      val unfocusedTarget = Paths.get("out", "corvi-renderer-unfocused.png")
      ImageIO.write(unfocused, "png", unfocusedTarget.toFile)
      println(s"wrote ${image.getWidth}×${image.getHeight} to ${target.toAbsolutePath} (focused)")
      println(s"wrote ${unfocused.getWidth}×${unfocused.getHeight} to ${unfocusedTarget.toAbsolutePath} (unfocused)")
      java.lang.System.exit(0)

  private def toBufferedImage(image: WritableImage): BufferedImage =
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = image.getPixelReader
    for x <- 0 until width; y <- 0 until height do buffered.setRGB(x, y, pixels.getArgb(x, y))
    buffered

  private def isBlank(image: BufferedImage): Boolean =
    if image.getWidth == 0 || image.getHeight == 0 then true
    else
      val first = image.getRGB(0, 0)
      val pixels =
        (0 until image.getWidth).iterator.flatMap(x => (0 until image.getHeight).iterator.map(y => image.getRGB(x, y)))
      pixels.forall(_ == first)
