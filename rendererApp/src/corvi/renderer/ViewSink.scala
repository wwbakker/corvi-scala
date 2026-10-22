package corvi.renderer

import corvi.contract.{Damage, SessionId}
import corvi.protocol.{Ended, FrameSink}
import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** The client's sink for pushes, shared by every view on that client: each view drops the sessions that are not its own, so one connection
  * serves several tabs.
  */
final class ViewSink extends FrameSink:
  private val targets = AtomicReference[Vector[TerminalView]](Vector.empty)

  def plug(view: TerminalView): Unit =
    val _ = targets.updateAndGet(current => current :+ view)

  def views: Vector[TerminalView] = targets.get

  def damage(id: SessionId, damage: Damage): Unit < Sync =
    Sync.defer(targets.get.foreach(view => view.pushDamage(id, damage)))

  def ended(id: SessionId, ended: Ended): Unit < Sync =
    Sync.defer(targets.get.foreach(view => view.pushEnded(id, ended)))
