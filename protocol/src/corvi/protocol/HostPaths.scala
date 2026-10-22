package corvi.protocol

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.security.SecureRandom

/** Where the daemon's socket and its token live.
  *
  * Everything is namespaced: `$XDG_RUNTIME_DIR/corvi-scalafx` if that directory is set, else `~/.local/state/corvi-scalafx`. The name
  * matters as much as the permissions — the installed Corvi app keeps its own state in `.../corvi`, and a spike that bound or replaced
  * *that* socket would be fighting the production app for a lock it owns.
  *
  * The directory is 0700 and both files are 0600: the socket is a door only this user can knock on, and the token is the password behind
  * it. The token persists across daemon restarts, so a UI that reattaches reads the same file the daemon did. `CORVI_HOST_SOCKET` overrides
  * the socket path (the token sits beside it), which is how a probe runs a host without touching the user's real one.
  */
final case class HostPaths(socket: Path, tokenFile: Path):

  /** The socket's directory; for a bare relative socket path that is the working directory, not a null parent. */
  def directory: Path = Option(socket.getParent).getOrElse(Paths.get("."))

  /** The file that says which process owns the socket. Written next to the socket, so a daemon never removes a socket it did not create. */
  def ownerFile: Path = socket.resolveSibling(socket.getFileName.toString + ".owner")

  /** Reads the token, creating a random one if this is the first run. */
  def token(): String =
    if Files.exists(tokenFile) then Files.readString(tokenFile, StandardCharsets.UTF_8).trim
    else createToken()

  /** Creates the directory (0700 even if it already existed) and the token, and restricts the files. */
  def prepare(): Unit =
    val owner = PosixFilePermissions.fromString("rwx------")
    val _ = Files.createDirectories(directory)
    val _ = Files.setPosixFilePermissions(directory, owner)
    val _ = token()

  def restrictSocket(): Unit =
    val _ = Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
    val _ = Files.setPosixFilePermissions(ownerFile, PosixFilePermissions.fromString("rw-------"))

  /** Records the owning process, which is what makes a later start able to tell "our dead daemon" from "somebody else's socket". */
  def claim(pid: Long): Unit =
    val _ = Files.writeString(ownerFile, pid.toString, StandardCharsets.UTF_8)

  def owner(): Option[Long] =
    if !Files.exists(ownerFile) then None
    else Files.readString(ownerFile, StandardCharsets.UTF_8).trim.toLongOption

  /** Two spawners racing for the same socket: the first to create the file wins, and the loser reads what the winner wrote. The token is
    * written *after* an atomic create with 0600 from the start, so it is never readable by anyone else, not even for a moment.
    */
  private def createToken(): String =
    val generated = HostPaths.randomToken()
    val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    try
      val created = Files.createFile(tokenFile, permissions)
      Files.writeString(created, generated, StandardCharsets.UTF_8)
      generated
    catch case _: java.nio.file.FileAlreadyExistsException => readTokenWhenWritten()

  /** The winner may still be writing; wait for the file to have contents rather than reading an empty secret. */
  private def readTokenWhenWritten(): String =
    var attempts = 0
    var read = ""
    while read.isEmpty && attempts < 200 do
      read =
        try Files.readString(tokenFile, StandardCharsets.UTF_8).trim
        catch case _: java.io.IOException => ""
      if read.isEmpty then
        attempts += 1
        Thread.sleep(10)
    if read.isEmpty then throw new IllegalStateException(s"the token file $tokenFile stayed empty") else read

object HostPaths:

  /** The spike's own directories. Never the installed app's, which owns `.../corvi` and its `host.sock`. */
  def defaultPaths(): HostPaths =
    Option(java.lang.System.getenv("CORVI_HOST_SOCKET")).filter(_.nonEmpty) match
      case Some(overridden) =>
        val socket = Paths.get(overridden)
        HostPaths(socket, socket.resolveSibling("token"))
      case None =>
        val root = Option(java.lang.System.getenv("XDG_RUNTIME_DIR"))
          .filter(_.nonEmpty)
          .map(value => Paths.get(value).resolve("corvi-scalafx"))
          .getOrElse(Paths.get(java.lang.System.getProperty("user.home"), ".local", "state", "corvi-scalafx"))
        HostPaths(root.resolve("host.sock"), root.resolve("token"))

  /** 32 bytes of randomness, hex, which is the daemon's secret and what the token file holds. */
  def randomToken(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    bytes.map(byte => f"${byte & 0xff}%02x").mkString
