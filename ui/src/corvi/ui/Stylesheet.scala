package corvi.ui

import javafx.scene.Scene

/** `corvi.css` is this module's resource, not the application's: the shell owns its own dress, so any window that shows it gets the theme
  * without copying the file. A window that cannot find it would still run, in an unthemed toolkit palette that looks nothing like Corvi, so
  * the missing resource is an error rather than a silent fallback.
  */
private[corvi] object Stylesheet:
  private val resource: String = "/corvi.css"

  def attachTo(scene: Scene): Unit =
    val url = Option(getClass.getResource(resource)) match
      case Some(found) => found.toExternalForm
      case None        => throw new IllegalStateException(s"corvi ui: the stylesheet $resource is not on the classpath")
    val _ = scene.getStylesheets.add(url)
