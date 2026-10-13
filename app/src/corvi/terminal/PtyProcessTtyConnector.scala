package corvi.terminal

import com.pty4j.{PtyProcess, WinSize}
import com.techsenger.jeditermfx.core.ProcessTtyConnector
import com.techsenger.jeditermfx.core.util.TermSize
import java.nio.charset.Charset

/** JediTermFX's own connector for a pty4j process, vendored from the `jeditermfx-app` demo module, which is not published (404 on Maven
  * Central).
  *
  * `ProcessTtyConnector` already reads and writes the process streams; a PTY needs two things on top of that, both here: resizing the
  * terminal (`TtyConnector.resize` throws by default), and a signal on every read so the shell can record that output arrived in a window
  * you are not looking at.
  */
private[corvi] final class PtyProcessTtyConnector(process: PtyProcess, charset: Charset, onOutput: () => Unit)
    extends ProcessTtyConnector(process, charset):

  override def read(buf: Array[Char], offset: Int, length: Int): Int =
    val read = super.read(buf, offset, length)
    if read > 0 then onOutput()
    read

  /** Without this the terminal never learns the panel's size: the interface's default throws. */
  override def resize(termSize: TermSize): Unit =
    if isConnected then process.setWinSize(new WinSize(termSize.getColumns, termSize.getRows))

  override def isConnected: Boolean = process.isAlive

  override def getName: String = "Local"
