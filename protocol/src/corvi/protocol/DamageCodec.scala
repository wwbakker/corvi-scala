package corvi.protocol

import corvi.contract.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.{Deflater, DeflaterOutputStream, InflaterInputStream}

/** The compact wire form of a frame delta.
  *
  * The JSON form describes every cell as a structured object; a full Pi screen is about 760 KB on the wire. This codec keeps the same
  * absolute-damage semantics but writes cells as packed binary, folds equal cells into runs, and deflates the result before putting it in
  * the JSON envelope. The control protocol remains JSON-RPC; only the large push payload gets the binary form.
  */
object DamageCodec:
  private val Magic: Int = 0x434f5244 // "CORD"
  private val Version: Int = 1

  def encode(damage: Damage): String =
    val raw = new ByteArrayOutputStream()
    val compressed = new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_SPEED))
    val out = new DataOutputStream(compressed)
    try
      out.writeInt(Magic)
      out.writeByte(Version)
      writeSize(out, damage.size)
      out.writeInt(damage.rows.size)
      damage.rows.foreach { change =>
        out.writeInt(change.row)
        writeRuns(out, change.cells.cells)
      }
      writeCursor(out, damage.cursor)
      writeModes(out, damage.modes)
      damage.historyRows match
        case Some(rows) => out.writeBoolean(true); out.writeInt(rows)
        case None       => out.writeBoolean(false)
      out.flush()
    finally out.close()
    Base64.getEncoder.encodeToString(raw.toByteArray)

  def decode(payload: String): Damage =
    val raw = Base64.getDecoder.decode(payload)
    val in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(raw)))
    try
      if in.readInt() != Magic then throw new IllegalArgumentException("not a Corvi damage payload")
      if in.readByte() != Version then throw new IllegalArgumentException("unsupported Corvi damage version")
      val size = readSize(in)
      val rowCount = in.readInt()
      val rows = Vector.fill(rowCount)(RowDamage(in.readInt(), Row(readCells(in))))
      val cursor = readCursor(in)
      val modes = readModes(in)
      val historyRows = if in.readBoolean() then Some(in.readInt()) else None
      Damage(rows, size, cursor, modes, historyRows)
    finally in.close()

  private def writeRuns(out: DataOutputStream, cells: Vector[Cell]): Unit =
    val runs = Vector.newBuilder[(Int, Cell)]
    var current: Option[(Int, Cell)] = None
    cells.foreach { cell =>
      current match
        case Some((count, previous)) if previous == cell => current = Some((count + 1, previous))
        case _                                           =>
          current.foreach(runs += _)
          current = Some((1, cell))
    }
    current.foreach(runs += _)
    val encoded = runs.result()
    out.writeInt(encoded.size)
    encoded.foreach { (count, cell) =>
      writeVarInt(out, count)
      writeCell(out, cell)
    }

  private def readCells(in: DataInputStream): Vector[Cell] =
    val runCount = in.readInt()
    val cells = Vector.newBuilder[Cell]
    for _ <- 0 until runCount do
      val count = readVarInt(in)
      val cell = readCell(in)
      for _ <- 0 until count do cells += cell
    cells.result()

  private def writeCell(out: DataOutputStream, cell: Cell): Unit =
    writeString(out, cell.text)
    out.writeByte(cell.width.ordinal)
    var flags = 0
    if cell.foreground.isDefined then flags |= 1
    if cell.background.isDefined then flags |= 2
    if cell.bold then flags |= 4
    if cell.italic then flags |= 8
    if cell.underline then flags |= 16
    if cell.inverse then flags |= 32
    if cell.link.isDefined then flags |= 64
    out.writeByte(flags)
    cell.foreground.foreach(rgb => writeRgb(out, rgb))
    cell.background.foreach(rgb => writeRgb(out, rgb))
    cell.link.foreach(link => writeString(out, link))

  private def readCell(in: DataInputStream): Cell =
    val text = readString(in)
    val width = CellWidth.fromOrdinal(in.readUnsignedByte())
    val flags = in.readUnsignedByte()
    val foreground = if (flags & 1) != 0 then Some(readRgb(in)) else None
    val background = if (flags & 2) != 0 then Some(readRgb(in)) else None
    val link = if (flags & 64) != 0 then Some(readString(in)) else None
    Cell(text, width, foreground, background, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0, link)

  private def writeRgb(out: DataOutputStream, rgb: Rgb): Unit =
    out.writeByte(rgb.red)
    out.writeByte(rgb.green)
    out.writeByte(rgb.blue)

  private def readRgb(in: DataInputStream): Rgb =
    Rgb(in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte())

  private def writeSize(out: DataOutputStream, size: Option[TermSize]): Unit =
    size match
      case Some(value) =>
        out.writeBoolean(true)
        out.writeInt(value.cols)
        out.writeInt(value.rows)
      case None => out.writeBoolean(false)

  private def readSize(in: DataInputStream): Option[TermSize] =
    if in.readBoolean() then Some(TermSize(in.readInt(), in.readInt())) else None

  private def writeCursor(out: DataOutputStream, cursor: Option[Cursor]): Unit =
    cursor match
      case Some(value) =>
        out.writeBoolean(true)
        out.writeInt(value.x)
        out.writeInt(value.y)
        value.shape match
          case Some(shape) => out.writeBoolean(true); out.writeByte(shape.ordinal)
          case None        => out.writeBoolean(false)
        out.writeBoolean(value.blinking)
        out.writeBoolean(value.visible)
      case None => out.writeBoolean(false)

  private def readCursor(in: DataInputStream): Option[Cursor] =
    if !in.readBoolean() then None
    else
      val x = in.readInt()
      val y = in.readInt()
      val shape = if in.readBoolean() then Some(CursorShape.fromOrdinal(in.readUnsignedByte())) else None
      Some(Cursor(x, y, shape, in.readBoolean(), in.readBoolean()))

  private def writeModes(out: DataOutputStream, modes: Option[Modes]): Unit =
    modes match
      case Some(value) =>
        out.writeBoolean(true)
        out.writeBoolean(value.alternateScreen)
        out.writeByte(value.mouseMode.ordinal)
        out.writeByte(value.mouseFormat.ordinal)
        out.writeBoolean(value.bracketedPaste)
        writeString(out, value.title)
        out.writeBoolean(value.focusReporting)
      case None => out.writeBoolean(false)

  private def readModes(in: DataInputStream): Option[Modes] =
    if !in.readBoolean() then None
    else
      Some(
        Modes(
          in.readBoolean(),
          MouseMode.fromOrdinal(in.readUnsignedByte()),
          MouseFormat.fromOrdinal(in.readUnsignedByte()),
          in.readBoolean(),
          readString(in),
          in.readBoolean()
        )
      )

  private def writeString(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    writeVarInt(out, bytes.length)
    out.write(bytes)

  private def readString(in: DataInputStream): String =
    val length = readVarInt(in)
    val bytes = new Array[Byte](length)
    in.readFully(bytes)
    new String(bytes, StandardCharsets.UTF_8)

  private def writeVarInt(out: DataOutputStream, value: Int): Unit =
    if value < 0 then throw new IllegalArgumentException(s"negative varint: $value")
    var remaining = value
    while (remaining & ~0x7f) != 0 do
      out.writeByte((remaining & 0x7f) | 0x80)
      remaining >>>= 7
    out.writeByte(remaining)

  private def readVarInt(in: DataInputStream): Int =
    var result = 0
    var shift = 0
    var more = true
    while more do
      val byte = in.readUnsignedByte()
      result |= (byte & 0x7f) << shift
      more = (byte & 0x80) != 0
      shift += 7
      if shift > 28 then throw new IllegalArgumentException("varint is too long")
    result
