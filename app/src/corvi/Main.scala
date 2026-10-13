package corvi

import org.slf4j.LoggerFactory

/** Entry point placeholder. The JavaFX shell (`JFXApp3`) replacing this body lands with the terminal-tabs work. Until then it doubles as a
  * runtime health check: it loads a JavaFX class and reports the jar it came from, so an empty `org.openjfx` stub (302 bytes) or a missing
  * platform-classifier jar fails loudly here instead of surfacing later as a JediTermFX error. The `logger.info` line proves the slf4j
  * backend (logback) the terminal stack logs through.
  */
object Main:
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val platform = Class.forName("javafx.application.Platform")
    val codeSource = Option(platform.getProtectionDomain)
      .flatMap(pd => Option(pd.getCodeSource))
      .map(_.getLocation.toString)
      .getOrElse("<unknown>")
    println(s"corvi-scalafx ${args.mkString(" ")}")
    logger.info(s"loaded ${platform.getName} from $codeSource")
