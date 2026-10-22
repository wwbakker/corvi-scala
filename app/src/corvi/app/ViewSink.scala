package corvi.app

import corvi.contract.{Damage, Ended, SessionId}
import corvi.protocol.FrameSink
import corvi.renderer.TerminalView
import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** The client's sink for pushes, shared by every view on that client: each view drops the sessions that are not its own, so one connection
  * serves several tabs.
  *
  * A view plugs in when it is opened and unplugs when it is disposed: a closed tab must stop being iterated by every push, and its frame
  * must not stay reachable through the sink.
  */
final class ViewSink extends FrameSink:
  private val targets = AtomicReference[Vector[TerminalView]](Vector.empty)

  def plug(view: TerminalView): Unit =
    val _ = targets.updateAndGet(current => current :+ view)

  def unplug(view: TerminalView): Unit =
    val _ = targets.updateAndGet(current => current.filterNot(_ eq view))

  def views: Vector[TerminalView] = targets.get

  def damage(id: SessionId, damage: Damage): Unit < Sync =
    Sync.defer(targets.get.foreach(view => view.pushDamage(id, damage)))

  def ended(id: SessionId, ended: Ended): Unit < Sync =
    Sync.defer(targets.get.foreach(view => view.pushEnded(id, ended)))
