package corvi.host

import com.techsenger.jeditermfx.core.emulator.ColorPaletteImpl
import com.techsenger.jeditermfx.core.model.TerminalLine
import com.techsenger.jeditermfx.core.model.TerminalTextBuffer
import com.techsenger.jeditermfx.core.util.CharUtils
import com.techsenger.jeditermfx.core.{TerminalColor, TextStyle}
import corvi.contract.*

/** Reads a `TerminalTextBuffer` into the contract's snapshot, and diffs two snapshots into damage.
  *
  * The buffer is written by the emulator's fiber while this runs, so every read happens under its lock — and so does the read of the
  * display's state, because the emulator updates the display while holding that same lock. Reading both under it is what makes a snapshot
  * one instant of the terminal rather than the grid from one moment and the cursor from another.
  */
private[host] object ScreenReader:
  import RecordingDisplay.State

  /** The palette the core's own settings provider defaults to, so indexed colours resolve the way the old UI resolved them.
    */
  private val palette = ColorPaletteImpl.XTERM_PALETTE

  def snapshot(id: SessionId, buffer: TerminalTextBuffer, display: RecordingDisplay, theme: TerminalTheme): Snapshot =
    buffer.lock()
    try
      val state: State = display.state
      val size = TermSize(buffer.getWidth, buffer.getHeight)
      Snapshot(
        id = id,
        size = size,
        rows = Vector.tabulate(size.rows)(y => row(buffer, y)),
        cursor = Cursor(
          x = state.cursorX,
          // The engine's column is 0-based and its row is 1-based (`JediTerminal.cursorPosition` subtracts one from the
          // column and none from the row); the contract indexes rows the way the grid does, from zero.
          y = math.max(0, state.cursorY - 1),
          shape = state.cursorShape,
          blinking = state.cursorBlinking,
          visible = state.cursorVisible
        ),
        modes = Modes(
          alternateScreen = state.alternateScreen,
          mouseMode = state.mouseMode,
          mouseFormat = state.mouseFormat,
          bracketedPaste = state.bracketedPaste,
          title = state.title
        ),
        historyRows = buffer.getHistoryLinesCount,
        theme = theme
      )
    finally buffer.unlock()

  /** The session's metrics, read under the buffer's lock like everything else that reads the buffer. */
  def info(id: SessionId, index: Int, buffer: TerminalTextBuffer, running: Boolean, shellPid: Long): SessionInfo =
    buffer.lock()
    try
      SessionInfo(
        id = id,
        index = index,
        size = TermSize(buffer.getWidth, buffer.getHeight),
        historyRows = buffer.getHistoryLinesCount,
        running = running,
        shellPid = shellPid
      )
    finally buffer.unlock()

  /** What changed between `previous` and `current`: the rows whose cells differ, by index (a row that did not exist before counts as
    * changed), plus whatever changed about the frame that is not a row.
    *
    * A size change carries *every* row. The rows have been re-indexed, so a row that happens to have the same contents at the same index is
    * still drawn by the client at a place that means something else now, and a same-width height change would otherwise blank the rows that
    * survived. The delta stays absolute and idempotent, which is what lets a client apply what arrives without asking again.
    */
  def damage(previous: Snapshot, current: Snapshot): Damage =
    val resized = previous.size != current.size
    Damage(
      rows = current.rows.zipWithIndex.collect {
        case (row, index) if resized || previous.rows.lift(index).forall(_ != row) => RowDamage(index, row)
      },
      size = Option.when(previous.size != current.size)(current.size),
      cursor = Option.when(previous.cursor != current.cursor)(current.cursor),
      modes = Option.when(previous.modes != current.modes)(current.modes),
      historyRows = Option.when(previous.historyRows != current.historyRows)(current.historyRows)
    )

  /** Rows from the scrollback, newest first: `from` counts back from the most recent history line (1-based), the same indexing
    * `TerminalTextBuffer.getLine` uses for negative indexes.
    */
  def history(buffer: TerminalTextBuffer, from: Int, count: Int): Vector[Row] =
    buffer.lock()
    try
      val available = buffer.getHistoryLinesCount
      Vector.tabulate(math.max(0, math.min(count, available - from + 1)))(i => rowOf(buffer, buffer.getLine(-from - i)))
    finally buffer.unlock()

  private def row(buffer: TerminalTextBuffer, y: Int): Row = rowOf(buffer, buffer.getLine(y))

  private def rowOf(buffer: TerminalTextBuffer, line: TerminalLine): Row =
    Row(Vector.tabulate(buffer.getWidth)(x => cell(line, x)))

  /** One cell, line by line rather than through `getStyledCharAt`, which hands back a Kotlin `Pair` for two values the line already
    * carries. A cell with no style of its own carries none, and is drawn in the session's theme.
    *
    * The engine marks a double-width character by putting `CharUtils.DWC` in the cell after it, and stores a character outside the BMP as
    * its two surrogate halves in two cells; both become text plus a width here, so a client never has to know either convention.
    */
  private def cell(line: TerminalLine, x: Int): Cell =
    val style: TextStyle = Option(line.getStyleAt(x)).getOrElse(TextStyle.EMPTY)
    val raw = line.charAt(x)
    val next = line.charAt(x + 1)
    val (text, width) =
      if raw == CharUtils.DWC then ("", CellWidth.Continuation)
      else if Character.isLowSurrogate(raw) then ("", CellWidth.Continuation)
      else if Character.isHighSurrogate(raw) && Character.isLowSurrogate(next) then (String(Array(raw, next)), CellWidth.Wide)
      else if next == CharUtils.DWC then (raw.toString, CellWidth.Wide)
      else (raw.toString, CellWidth.Narrow)
    Cell(
      text = text,
      width = width,
      foreground = Option(style.getForeground).map(rgb),
      background = Option(style.getBackground).map(rgb),
      bold = style.hasOption(TextStyle.Option.BOLD),
      italic = style.hasOption(TextStyle.Option.ITALIC),
      underline = style.hasOption(TextStyle.Option.UNDERLINED),
      inverse = style.hasOption(TextStyle.Option.INVERSE)
    )

  /** An indexed colour is resolved through the palette; an RGB one comes back unchanged. */
  private def rgb(color: TerminalColor): Rgb =
    val resolved = palette.getForeground(color)
    Rgb(resolved.getRed, resolved.getGreen, resolved.getBlue)
