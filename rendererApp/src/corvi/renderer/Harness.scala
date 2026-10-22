package corvi.renderer

import corvi.contract.*
import corvi.host.TerminalPeers
import corvi.protocol.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.Frame

/** The shared setup for the window, the snapshot harness and the probe: an in-memory host with one shell, kept alive on a thread of its own
  * because the scope that owns it cannot be built on the FX thread.
  */
object Harness:

  /** The sink the host pushes to. The view needs the service and the service needs the sink, so the sink is created empty and the view is
    * plugged in once both exist.
    */
  final class Sink extends FrameSink:
    private val targets = AtomicReference[Vector[TerminalView]](Vector.empty)

    /** Every plugged view hears every push and drops the sessions that are not its own, which is what makes one client serve several tabs.
      */
    def plug(view: TerminalView): Unit =
      val _ = targets.updateAndGet(current => current :+ view)

    def damage(id: SessionId, damage: Damage): Unit < Sync =
      Sync.defer(targets.get.foreach(view => view.pushDamage(id, damage)))

    def ended(id: SessionId, ended: Ended): Unit < Sync =
      Sync.defer(targets.get.foreach(view => view.pushEnded(id, ended)))

  final case class Running(
      service: TerminalService,
      session: SessionId,
      extras: Vector[SessionId],
      sink: Sink,
      host: TerminalPeers.InMemoryHost,
      stop: () => Unit
  )

  /** Starts the host, waits for the client and one shell, and returns them. The shell lives until `stop()` is called; callers that exit the
    * process should call it first, so a harness cannot leave a shell behind.
    */
  def start(cols: Int, rows: Int, pushInterval: kyo.Duration, command: Seq[String]): Running =
    val sink = new Harness.Sink()
    val ready = CountDownLatch(1)
    val stopping = CountDownLatch(1)
    val outcome = AtomicReference[Either[String, Running]](Left("the host did not start"))

    val thread = Thread.ofPlatform().name("corvi-renderer-host").daemon(true)
    val _ = thread.start { () =>
      import AllowUnsafe.embrace.danger
      KyoApp.Unsafe.runAndBlock(kyo.Duration.Infinity) {
        Scope.run {
          for
            host <- TerminalPeers.host(pushInterval, command)
            wired <- host.client(sink)
            created <- wired.client.create(java.lang.System.getProperty("user.dir"), TermSize(cols, rows))
            // A second session from the same scope: the two-view check needs one that no short-lived runtime created.
            second <- wired.client.create(java.lang.System.getProperty("user.dir"), TermSize(60, 16))
            _ <- Sync.defer {
              outcome.set(
                Right(
                  Running(
                    service = wired.client,
                    session = created.id,
                    extras = Vector(second.id),
                    sink = sink,
                    host = host,
                    stop = () => stopping.countDown()
                  )
                )
              )
              ready.countDown()
            }
            _ <- Sync.defer(stopping.await())
          yield ()
        }
      } match
        case Result.Success(_) => ()
        case failure           =>
          outcome.set(Left(s"the host failed: $failure"))
          ready.countDown()
    }

    if !ready.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("the renderer's host did not start in time")
    outcome.get() match
      case Right(running)    => running
      case Left(explanation) => throw new IllegalStateException(explanation)
