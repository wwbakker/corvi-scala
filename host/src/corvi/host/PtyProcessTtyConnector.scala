package corvi.host

import com.pty4j.{PtyProcess, WinSize}
import com.techsenger.jeditermfx.core.ProcessTtyConnector
import com.techsenger.jeditermfx.core.util.TermSize
import java.nio.charset.Charset

/** JediTermFX's own connector for a pty4j process, vendored from the `jeditermfx-app` demo module, which is not published.
  *
  * `ProcessTtyConnector` already reads and writes the process streams; a PTY needs resizing on top, which the interface's default throws
  * at.
  */
private[host] final class PtyProcessTtyConnector(process: PtyProcess, charset: Charset) extends ProcessTtyConnector(process, charset):

  /** Without this the terminal never learns the panel's size: the interface's default throws. */
  override def resize(termSize: TermSize): Unit =
    if isConnected then process.setWinSize(new WinSize(termSize.getColumns, termSize.getRows))

  override def isConnected: Boolean = process.isAlive

  override def getName: String = "Local"
