package corvi.app

import corvi.contract.SessionInfo
import corvi.protocol.TerminalError
import corvi.renderer.TerminalMetrics
import corvi.ui.{ShellView, StatusView, Stylesheet}
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kyo.*
import kyo.Frame

/** The window's lifecycle, independent of how the process was started: one stage, shown at once with a placeholder, then the shell when the
  * host has answered — or the failure, in the window, when it has not.
  *
  * The stage is supplied by the caller: `ShellApp` hands over JavaFX's primary stage, so there is exactly one window, and a test hands over
  * a stage of its own and gets the same sequence. Nothing here blocks the FX thread waiting for the host; the placeholder is what the
  * window shows meanwhile.
  */
private[app] final class AppWindow(stage: Stage, metrics: TerminalMetrics, cwd: String):

  /** The client this window owns, once the host has answered: closing the window stops its runtime but not the host. */
  private val connected = AtomicReference[Option[Client]](None)

  /** The shell it mounted, for the window's own tests: the scene root is the ScalaFX delegate, so a test cannot recover the wrapper from
    * it.
    */
  private val mounted = AtomicReference[Option[ShellView]](None)

  /** The runtime's own latch, released when the window closes: awaiting it suspends the client's fiber instead of parking a worker thread.
    */
  private val closing = AtomicReference[Option[Latch]](None)

  private[app] def shell: Option[ShellView] = mounted.get()

  locally:
    stage.setTitle("Corvi")
    stage.setMinWidth(720)
    stage.setMinHeight(420)
    stage.setScene(statusScene("Connecting to the host…"))
    stage.show()

  /** Connects on a thread of its own, then puts the shell in the window. The window is already up when this is called. */
  def connect(): Unit =
    val thread = Thread.ofPlatform().name("corvi-shell-client").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity)(session) match
        case Result.Success(_)     => ()
        case Result.Failure(error) => report(s"the client stopped: $error")
        case Result.Panic(error)   => report(s"the client panicked: $error")
    }

  /** Releases the window's views and ends its client runtime. The host and its shells stay: they belong to the daemon, which is the point
    * of the process split.
    */
  def close(): Unit =
    mounted.getAndSet(None).foreach(_.dispose())
    connected.getAndSet(None).foreach(_.stop())
    import AllowUnsafe.embrace.danger
    closing.getAndSet(None).foreach(latch => latch.unsafe.release())

  /** Ensures the host, lists what it holds and hands both to `show`; a failure goes into the window, not only to stderr.
    *
    * The whole connection lives inside one `Abort.run` region, and the region ends only when the window does. That is not tidiness: the
    * region's isolate cancels the fibers created inside it when it completes, and the client's dispatch/reader fiber is created by
    * `HostConnection.ensure`. A region that ended after the first `list` therefore consumed the attach response and then left nothing to
    * read the socket — the terminal never received a frame after its seed.
    */
  private def session(using Frame): Unit < (Async & Abort[Any]) =
    Scope.run {
      for
        stop <- Latch.init(1)
        _ <- Sync.defer(closing.set(Some(stop)))
        outcome <- Abort.run[TerminalError | Closed] {
          for
            client <- HostConnection.ensure()
            // The one fiber that serves every view: same runtime as the connection and its reader, so a result and a push are
            // dispatched by the same exchange that received them.
            _ <- Fiber.init(client.serve)
            sessions <- client.service.list
            _ <- Sync.defer(onFx(() => show(client, sessions.toVector)))
            _ <- stop.await
          yield ()
        }
        _ <- outcome match
          case Result.Success(_)     => Sync.defer(())
          case Result.Failure(error) => Sync.defer(onFx(() => failed(error.toString)))
          case Result.Panic(error)   => Sync.defer(onFx(() => failed(error.toString)))
      yield ()
    }

  private def show(client: Client, sessions: Vector[SessionInfo]): Unit =
    connected.set(Some(client))
    val factory = RendererViewFactory(client, metrics, cwd)
    val shell = ShellView(factory)
    mounted.set(Some(shell))
    val scene = new Scene(shell, 1180, 740)
    Stylesheet.attachTo(scene)
    shell.installChords(scene)
    stage.setOnCloseRequest(_ => {
      // The window goes; the host and its shells stay, which is the whole point of the daemon.
      close()
      Platform.exit()
    })
    stage.setScene(scene)
    stage.show()
    // After the stage is up, so the terminal area has the size the layout gives it: a session created before the first layout pass has no
    // room to measure and would be created at the placeholder grid, then regrid (and reprint its prompt) once the view attaches.
    if sessions.isEmpty then shell.newSession() else sessions.foreach(shell.open)

  private def failed(message: String): Unit =
    report(message)
    stage.setScene(statusScene(s"Could not reach the host: $message"))

  private def statusScene(message: String): Scene =
    val scene = new Scene(StatusView(message), 1180, 740)
    Stylesheet.attachTo(scene)
    scene

  private def onFx(action: () => Unit): Unit =
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())

  private def report(message: String): Unit = java.lang.System.err.println(s"corvi shell: $message")
