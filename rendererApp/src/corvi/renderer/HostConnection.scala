package corvi.renderer

import corvi.protocol.*
import java.io.File
import java.nio.file.{Path, Paths}
import kyo.*
import kyo.Frame
import scala.concurrent.duration.*

/** Connects to the corvi host, starting it if nothing is listening.
  *
  * The host is spawned with this JVM's own `java`, classpath and Kyo flags, detached: its output goes to a log next to its socket, it reads
  * nothing from our stdin, and we never wait for it or destroy it. That is the whole point of the process — the shells have to outlive the
  * window that started them, so a UI restart is a reconnect and not a restart of the user's work.
  *
  * A spawn failure becomes the typed `HostUnavailable`; a refused hello comes back as the daemon's `Unauthorized`.
  */
object HostConnection:

  /** How long a freshly started daemon gets to bind its socket before the client gives up. */
  private val startTimeout: scala.concurrent.duration.Duration = 20.seconds

  /** How long a connect-to-test may take before the socket is treated as nobody's. */
  private val pingTimeout: scala.concurrent.duration.Duration = 500.millis

  /** Connects, starting the daemon first if nothing answers, and says hello with the token from the file beside the socket.
    *
    * The sink is made here, where the routes are wired to it, and leaves together with the service: a caller cannot pair a client with a
    * sink its pushes do not go to.
    */
  def ensure()(using Frame): Client < (Async & Scope & Abort[TerminalError | Closed]) =
    for
      paths <- Sync.defer(HostPaths.defaultPaths())
      _ <- Sync.defer(paths.prepare())
      token <- Sync.defer(paths.token())
      started <- Abort.run[Throwable](Abort.catching[Throwable](Sync.defer(connectOrStart(paths))))
      _ <- started match
        case Result.Success(_)     => Sync.defer(())
        case Result.Failure(error) => Abort.fail(HostUnavailable(error.toString))
        case Result.Panic(error)   => Abort.fail(HostUnavailable(error.toString))
      client <- connect(paths, token)
    yield client

  private def connect(paths: HostPaths, token: String)(using
      Frame
  ): Client < (Async & Scope & Abort[TerminalError | Closed]) =
    for
      sink <- Sync.defer(new ViewSink())
      closed <- Latch.init(1)
      opened <- Abort.run[Throwable](SocketWire.connect(paths.socket, closed))
      wire <- opened match
        case Result.Success(value) => Sync.defer(value)
        case Result.Failure(error) => Abort.fail(HostUnavailable(s"cannot connect to ${paths.socket}: $error"))
        case Result.Panic(error)   => Abort.fail(HostUnavailable(s"cannot connect to ${paths.socket}: $error"))
      transport <- JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength)
      handler <- JsonRpcHandler.init(transport, ClientRoutes.routes(sink)*)
      service <- Sync.defer(TerminalClient(handler))
      _ <- service.hello(token)
    yield Client(service, sink)

  private def connectOrStart(paths: HostPaths): Unit =
    if answers(paths.socket) then ()
    else
      start(paths)
      awaitSocket(paths.socket)

  /** Whether something is listening: a non-blocking connect that completes is a daemon, one that fails is a socket nobody owns. */
  private def answers(socket: Path): Boolean = SocketWire.answersUnsafe(socket, pingTimeout)

  private def start(paths: HostPaths): Unit =
    val javaBinary = Paths.get(java.lang.System.getProperty("java.home"), "bin", "java").toString
    val classpath = java.lang.System.getProperty("java.class.path")
    val log = paths.directory.resolve("host.log")
    val builder = java.lang
      .ProcessBuilder(
        javaBinary,
        "-Dkyo.scheduler.virtualizeWorkers=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-cp",
        classpath,
        "corvi.host.HostMain"
      )
      // Truncated on every spawn: the log is for the last launch, not a growing pile of JVM warnings.
      .redirectOutput(log.toFile)
      .redirectErrorStream(true)
      // No inherited stdin: the daemon must not hold the terminal open, and must not die when the UI's stdin does.
      .redirectInput(java.lang.ProcessBuilder.Redirect.from(new File("/dev/null")))
    val _ = builder.environment().put("CORVI_HOST_SOCKET", paths.socket.toString)
    // Detached on purpose: the handle is dropped, nothing waits on it, and nothing destroys it when this process exits.
    val process = builder.start()
    java.lang.System.err.println(s"corvi renderer: started the host as pid ${process.pid()}, logging to $log")

  private def awaitSocket(socket: Path): Unit =
    val deadline = java.lang.System.nanoTime() + startTimeout.toNanos
    while !answers(socket) && java.lang.System.nanoTime() < deadline do Thread.sleep(50)
    if !answers(socket) then throw new IllegalStateException(s"the host did not bind $socket within $startTimeout")
