package corvi

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import javafx.application.Platform
import javafx.scene.image.WritableImage
import javax.imageio.ImageIO
import scalafx.Includes.*
import scalafx.application.JFXApp3
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

  override def start(): Unit =
    val shell = new CorviShell
    val window = AppWindow.scene(shell)
    stage = AppWindow.stage(window, () => shell.shutdown())
    // `onShown` fires once the window is on screen and its scene laid out; each `runLater` hop is
    // a pulse boundary, so by the second one the scene has been painted. No timer, no sleep.
    stage.onShown = () => Platform.runLater(() => Platform.runLater(() => write(window)))

  private def write(window: Scene): Unit =
    val buffered = toBufferedImage(window.snapshot(null))
    if isBlank(buffered) then
      Console.err.println("SnapshotMain: the render is blank, so nothing was written")
      System.exit(1)
    else
      val _ = Files.createDirectories(snapshot.getParent)
      ImageIO.write(buffered, "png", snapshot.toFile)
      println(s"wrote ${buffered.getWidth}×${buffered.getHeight} to ${snapshot.toAbsolutePath}")
      Platform.exit()

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
