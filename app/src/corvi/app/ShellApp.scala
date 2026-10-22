package corvi.app

import corvi.contract.SessionInfo
import corvi.renderer.TerminalMetrics
import corvi.ui.{ShellView, Stylesheet}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kyo.*
import kyo.Frame

/** The real window: it ensures the host, reattaches to whatever the host already holds (a UI restart is exactly this), and opens one if the
  * host has nothing. Closing the window disposes the views and leaves the host and its shells running.
  *
  * ./mill -i app.runMain corvi.app.ShellApp
  */
object ShellApp extends scalafx.application.JFXApp3:

  private final case class Started(client: Client, sessions: Vector[SessionInfo])

  private val started = AtomicReference[Option[Started]](None)
  private val ready = CountDownLatch(1)
  private val closing = CountDownLatch(1)

  locally:
    val thread = Thread.ofPlatform().name("corvi-shell-client").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity) {
        Scope.run {
          for
            client <- HostConnection.ensure()
            sessions <- client.service.list
            _ <- Sync.defer {
              started.set(Some(Started(client, sessions.toVector)))
              ready.countDown()
            }
            _ <- Sync.defer(closing.await())
          yield ()
        }
      } match
        case Result.Success(_)     => ()
        case Result.Failure(error) => java.lang.System.err.println(s"corvi shell: the client stopped: $error")
        case Result.Panic(error)   => java.lang.System.err.println(s"corvi shell: the client panicked: $error")
    }

  override def start(): Unit =
    if !ready.await(120, TimeUnit.SECONDS) then
      java.lang.System.err.println("corvi shell: the host did not answer")
      Platform.exit()
    else
      started.get() match
        case None                            => Platform.exit()
        case Some(Started(client, sessions)) => show(client, sessions)

  private def show(client: Client, sessions: Vector[SessionInfo]): Unit =
    val metrics = TerminalMetrics.monospaced(13)
    val factory = RendererViewFactory(client, metrics, java.lang.System.getProperty("user.dir"))
    val shell = ShellView(factory)
    if sessions.isEmpty then shell.newSession() else sessions.foreach(shell.open)
    val scene = Scene(shell, 1180, 740)
    Stylesheet.attachTo(scene)
    shell.installChords(scene)
    val window = Stage()
    window.setTitle("Corvi")
    window.setScene(scene)
    window.setMinWidth(720)
    window.setMinHeight(420)
    window.setOnCloseRequest(_ => {
      // The window goes; the host and its shells stay, which is the whole point of the daemon.
      shell.dispose()
      client.stop()
      closing.countDown()
      Platform.exit()
    })
    window.show()
