package corvi.app

import corvi.contract.SessionInfo
import corvi.protocol.TerminalError
import corvi.renderer.AltMode
import corvi.ui.{ShellView, StatusView, Stylesheet, TerminalFontSize}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kyo.*
import kyo.Frame
import scala.concurrent.duration.FiniteDuration

/** The window's lifecycle, independent of how the process was started: one stage, shown at once with a placeholder, then the shell when the
  * host has answered — or the failure, in the window, when it has not.
  *
  * The stage is supplied by the caller: `ShellApp` hands over JavaFX's primary stage, so there is exactly one window, and a test hands over
  * a stage of its own and gets the same sequence. Nothing here blocks the FX thread waiting for the host; the placeholder is what the
  * window shows meanwhile.
  */
private[app] final class AppWindow(
    stage: Stage,
    cwd: String,
    altMode: AltMode = AltMode.configured,
    initialFontSize: Double = TerminalFontSize.default,
    openLink: String => Unit = _ => (),
    connectHost: () => Client < (Async & Scope & Abort[TerminalError | Closed]) = () => HostConnection.ensure(),
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy.default,
    sleepFor: FiniteDuration => Unit < Async = delay => Async.sleep(kyo.Duration.fromScala(delay))
):

  /** The client this window owns, once the host has answered: closing the window stops its runtime but not the host. */
  private val connected = AtomicReference[Option[Client]](None)

  /** The shell it mounted, for the window's own tests: the scene root is the ScalaFX delegate, so a test cannot recover the wrapper from
    * it.
    */
  private val mounted = AtomicReference[Option[ShellView]](None)

  /** The window is closing; the supervisor exits after the current sleep or connection attempt. */
  private val closing = new AtomicBoolean(false)

  /** The current attempt's wake-up signal, released by the window close or the client's lost connection. */
  private val reconnectSignal = AtomicReference[Option[Latch]](None)

  /** Whether a shell has ever been mounted; only the first successful show may create the initial blank terminal. */
  private var everShowed: Boolean = false

  /** The last font size chosen in a mounted shell, so a replacement session starts at the same size. */
  private var lastFontSize: Double = initialFontSize

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
    closing.set(true)
    reconnectSignal.getAndSet(None).foreach(release)
    mounted.getAndSet(None).foreach(_.dispose())
    connected.getAndSet(None).foreach(_.stop())

  /** Runs the connection supervisor for the window's lifetime. Each connection attempt owns a scope, so a lost or failed connection
    * releases its handler, reader and serve fiber before the next attempt and the window never stacks two connections.
    */
  private def session(using Frame): Unit < (Async & Abort[Any]) =
    connectionLoop

  /** Keeps one connection alive at a time. A connection's attempt scope owns its handler, reader and serve fiber; when the attempt ends,
    * all of them are released before the supervisor decides whether to retry or reattach.
    */
  private def connectionLoop(using Frame): Unit < (Async & Abort[Any]) =
    def attempt(delay: FiniteDuration, first: Boolean): Unit < (Async & Abort[Any]) =
      if closing.get() then Sync.defer(())
      else
        val outcome = Abort.run[TerminalError | Closed] {
          Scope.run {
            for
              client <- connectHost()
              // The attempt scope owns the client's queue as well as its handler and serve fiber: an early close or an abort still
              // stops it, and no client survives the window.
              _ <- Scope.ensure(Sync.defer(client.stop()))
              _ <-
                if closing.get() then Sync.defer(())
                else
                  for
                    lost <- Latch.init(1)
                    _ <- Sync.defer {
                      if !closing.get() then
                        reconnectSignal.set(Some(lost))
                        client.onConnectionLost(() => release(lost))
                    }
                    _ <-
                      if closing.get() then
                        Sync.defer {
                          reconnectSignal.compareAndSet(Some(lost), None)
                          release(lost)
                        }
                      else
                        for
                          _ <- Fiber.init(client.serve)
                          sessions <- client.service.list
                          _ <- Sync.defer(onFx(() => if !closing.get() then show(client, sessions.toVector)))
                          _ <-
                            if closing.get() then
                              Sync.defer {
                                reconnectSignal.compareAndSet(Some(lost), None)
                                release(lost)
                              }
                            else lost.await
                        yield ()
                  yield ()
            yield ()
          }
        }
        outcome.flatMap {
          case _ if closing.get() => Sync.defer(())
          case Result.Success(_)  =>
            for
              _ <- Sync.defer(reconnectSignal.set(None))
              _ <- Sync.defer(onFx(() => lostConnection()))
              _ <- sleepFor(delay)
              _ <- attempt(reconnectPolicy.next(delay), first = false)
            yield ()
          case Result.Failure(error) =>
            for
              _ <- Sync.defer(reconnectSignal.set(None))
              _ <- Sync.defer(onFx(() => connectionFailed(error.toString, first)))
              _ <- sleepFor(delay)
              _ <- attempt(reconnectPolicy.next(delay), first = false)
            yield ()
          case Result.Panic(error) =>
            for
              _ <- Sync.defer(reconnectSignal.set(None))
              _ <- Sync.defer(onFx(() => connectionFailed(error.toString, first)))
              _ <- sleepFor(delay)
              _ <- attempt(reconnectPolicy.next(delay), first = false)
            yield ()
        }
    attempt(reconnectPolicy.initial, first = true)

  private def show(client: Client, sessions: Vector[SessionInfo]): Unit =
    connected.set(Some(client))
    val factory = RendererViewFactory(client, lastFontSize, cwd, altMode, openLink)
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
    if sessions.nonEmpty then sessions.foreach(shell.open)
    else if !everShowed then shell.newSession()
    // After a shell has been shown, an empty replacement stays on Overview with its New action; no blank terminal is created.
    everShowed = true

  private def lostConnection(): Unit =
    mounted.get().foreach(shell => lastFontSize = shell.currentFontSize)
    mounted.getAndSet(None).foreach(_.dispose())
    connected.set(None)
    stage.setScene(statusScene("Host lost; reconnecting. The previous shells ended with the host."))

  private def connectionFailed(message: String, first: Boolean): Unit =
    mounted.get().foreach(shell => lastFontSize = shell.currentFontSize)
    mounted.getAndSet(None).foreach(_.dispose())
    connected.set(None)
    report(s"connection failed: $message")
    val detail = if first then s"Could not reach the host: $message" else s"Host lost; reconnecting. $message"
    stage.setScene(statusScene(s"$detail Retrying…"))

  private def statusScene(message: String): Scene =
    val scene = new Scene(StatusView(message), 1180, 740)
    Stylesheet.attachTo(scene)
    scene

  private def onFx(action: () => Unit): Unit =
    if Platform.isFxApplicationThread then action() else Platform.runLater(() => action())

  private def release(latch: Latch): Unit =
    import AllowUnsafe.embrace.danger
    latch.unsafe.release()

  private def report(message: String): Unit = java.lang.System.err.println(s"corvi shell: $message")
