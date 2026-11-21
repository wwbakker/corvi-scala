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

  def snapshot(
      id: SessionId,
      buffer: TerminalTextBuffer,
      display: RecordingDisplay,
      theme: TerminalTheme,
      linkUri: TextStyle => Option[String]
  ): Snapshot =
    buffer.lock()
    try
      val state: State = display.state
      val size = TermSize(buffer.getWidth, buffer.getHeight)
      Snapshot(
        id = id,
        size = size,
        rows = Vector.tabulate(size.rows)(y => row(buffer, y, linkUri)),
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
          title = state.title,
          focusReporting = state.focusReporting
        ),
        historyRows = buffer.getHistoryLinesCount,
        theme = theme
      )
    finally buffer.unlock()

  /** A snapshot and delta without rebuilding every row's cells. Rows whose text and resolved style digest matches the previous frame reuse
    * the previous `Row`; only changed rows are materialised. The digest is an exact text string plus a style hash, so a full Pi page still
    * sends every changed row, while an idle or cursor-only frame costs one line read per row instead of 15k cells.
    */
  def changes(
      previous: Snapshot,
      buffer: TerminalTextBuffer,
      display: RecordingDisplay,
      theme: TerminalTheme,
      linkUri: TextStyle => Option[String]
  ): (Snapshot, Damage) =
    buffer.lock()
    try
      val state: State = display.state
      val size = TermSize(buffer.getWidth, buffer.getHeight)
      val resized = previous.size != size
      val changed = Vector.newBuilder[RowDamage]
      val rows =
        if resized then
          Vector.tabulate(size.rows) { y =>
            val currentRow = row(buffer, y, linkUri)
            changed += RowDamage(y, currentRow)
            currentRow
          }
        else
          previous.rows.zipWithIndex.map { (old, y) =>
            val line = buffer.getLine(y)
            if digestOfRow(old) == digestOfLine(line, buffer.getWidth, linkUri) then old
            else
              val currentRow = row(buffer, y, linkUri)
              changed += RowDamage(y, currentRow)
              currentRow
          }
      val cursor = Cursor(
        x = state.cursorX,
        y = math.max(0, state.cursorY - 1),
        shape = state.cursorShape,
        blinking = state.cursorBlinking,
        visible = state.cursorVisible
      )
      val modes = Modes(
        alternateScreen = state.alternateScreen,
        mouseMode = state.mouseMode,
        mouseFormat = state.mouseFormat,
        bracketedPaste = state.bracketedPaste,
        title = state.title,
        focusReporting = state.focusReporting
      )
      val historyRows = buffer.getHistoryLinesCount
      val current = Snapshot(previous.id, size, rows, cursor, modes, historyRows, theme)
      val damage = Damage(
        rows = changed.result(),
        size = Option.when(resized)(size),
        cursor = Option.when(previous.cursor != cursor)(cursor),
        modes = Option.when(previous.modes != modes)(modes),
        historyRows = Option.when(previous.historyRows != historyRows)(historyRows)
      )
      (current, damage)
    finally buffer.unlock()

  /** The session's metrics, read under the buffer's lock like everything else that reads the buffer. */
  def info(
      id: SessionId,
      index: Int,
      buffer: TerminalTextBuffer,
      running: Boolean,
      shellPid: Long,
      ended: Option[Ended]
  ): SessionInfo =
    buffer.lock()
    try
      SessionInfo(
        id = id,
        index = index,
        size = TermSize(buffer.getWidth, buffer.getHeight),
        historyRows = buffer.getHistoryLinesCount,
        running = running,
        shellPid = shellPid,
        ended = ended
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
  def history(
      buffer: TerminalTextBuffer,
      from: Int,
      count: Int,
      linkUri: TextStyle => Option[String]
  ): Vector[Row] =
    buffer.lock()
    try
      val available = buffer.getHistoryLinesCount
      Vector.tabulate(math.max(0, math.min(count, available - from + 1)))(i => rowOf(buffer, buffer.getLine(-from - i), linkUri))
    finally buffer.unlock()

  private def row(buffer: TerminalTextBuffer, y: Int, linkUri: TextStyle => Option[String]): Row =
    rowOf(buffer, buffer.getLine(y), linkUri)

  private def rowOf(buffer: TerminalTextBuffer, line: TerminalLine, linkUri: TextStyle => Option[String]): Row =
    Row(Vector.tabulate(buffer.getWidth)(x => cell(line, x, linkUri)))

  /** One cell, line by line rather than through `getStyledCharAt`, which hands back a Kotlin `Pair` for two values the line already
    * carries. A cell with no style of its own carries none, and is drawn in the session's theme.
    *
    * The engine marks a double-width character by putting `CharUtils.DWC` in the cell after it, and stores a character outside the BMP as
    * its two surrogate halves in two cells; both become text plus a width here, so a client never has to know either convention.
    */
  private def cell(line: TerminalLine, x: Int, linkUri: TextStyle => Option[String]): Cell =
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
      inverse = style.hasOption(TextStyle.Option.INVERSE),
      link = linkUri(style)
    )

  private final case class RowDigest(text: String, style: Long) derives CanEqual

  private def digestOfRow(row: Row): RowDigest =
    val text = new java.lang.StringBuilder()
    var style = 0xcbf29ce484222325L
    row.cells.foreach { cell =>
      text.append(cell.text)
      style = mix(style, cell.width.ordinal.toLong)
      style = mix(style, cell.foreground.map(colour => (colour.red << 16) | (colour.green << 8) | colour.blue).getOrElse(-1).toLong)
      style = mix(style, cell.background.map(colour => (colour.red << 16) | (colour.green << 8) | colour.blue).getOrElse(-1).toLong)
      style = mix(style, if cell.bold then 1L else 0L)
      style = mix(style, if cell.italic then 2L else 0L)
      style = mix(style, if cell.underline then 4L else 0L)
      style = mix(style, if cell.inverse then 8L else 0L)
      style = mix(style, cell.link.hashCode.toLong)
    }
    RowDigest(text.toString, style)

  private def digestOfLine(line: TerminalLine, width: Int, linkUri: TextStyle => Option[String]): RowDigest =
    val chars = line.getText
    val text = new java.lang.StringBuilder()
    var style = 0xcbf29ce484222325L
    for x <- 0 until width do
      val raw = if x < chars.length then chars.charAt(x) else ' '
      val next = if x + 1 < chars.length then chars.charAt(x + 1) else ' '
      val (cellText, cellWidth) =
        if raw == CharUtils.DWC || Character.isLowSurrogate(raw) then ("", CellWidth.Continuation)
        else if Character.isHighSurrogate(raw) && Character.isLowSurrogate(next) then (String(Array(raw, next)), CellWidth.Wide)
        else if next == CharUtils.DWC then (raw.toString, CellWidth.Wide)
        else (raw.toString, CellWidth.Narrow)
      text.append(cellText)
      style = mix(style, cellWidth.ordinal.toLong)
      val current = Option(line.getStyleAt(x)).getOrElse(TextStyle.EMPTY)
      style = mix(
        style,
        Option(current.getForeground)
          .map(colour => rgb(colour))
          .map(colour => (colour.red << 16) | (colour.green << 8) | colour.blue)
          .getOrElse(-1)
          .toLong
      )
      style = mix(
        style,
        Option(current.getBackground)
          .map(colour => rgb(colour))
          .map(colour => (colour.red << 16) | (colour.green << 8) | colour.blue)
          .getOrElse(-1)
          .toLong
      )
      style = mix(style, if current.hasOption(TextStyle.Option.BOLD) then 1L else 0L)
      style = mix(style, if current.hasOption(TextStyle.Option.ITALIC) then 2L else 0L)
      style = mix(style, if current.hasOption(TextStyle.Option.UNDERLINED) then 4L else 0L)
      style = mix(style, if current.hasOption(TextStyle.Option.INVERSE) then 8L else 0L)
      style = mix(style, linkUri(current).hashCode.toLong)
    RowDigest(text.toString, style)

  private def mix(hash: Long, value: Long): Long = (hash ^ value) * 1099511628211L

  /** An indexed colour is resolved through the palette; an RGB one comes back unchanged. */
  private def rgb(color: TerminalColor): Rgb =
    val resolved = palette.getForeground(color)
    Rgb(resolved.getRed, resolved.getGreen, resolved.getBlue)
