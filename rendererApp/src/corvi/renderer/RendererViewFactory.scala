package corvi.renderer

import corvi.contract.{TerminalView as ShellView, TerminalViewFactory, *}
import javafx.scene.Node as JfxNode

/** The shell's view factory over the real client and renderer: one `TerminalView` per session, and the two session operations a tab needs.
  *
  * It takes the whole `Client` rather than a service and a sink, because the pair is the thing: a view only hears its session's damage
  * through the sink the client was wired with, so choosing the sink here would be choosing whether the tab updates at all.
  *
  * `create` and `close` hand their work to the client's runtime and return at once — a host that is slow or wedged must not freeze the
  * window. Results come back on the FX thread, which is where the shell and the hub both live.
  */
private[renderer] final class RendererViewFactory(client: Client, metrics: TerminalMetrics, cwd: String)
    extends TerminalViewFactory[JfxNode]:

  /** The grid the host gets when the window has not been laid out yet: the size this spike has always started a session at. A first tab
    * asked for before the stage is shown would otherwise be created at one cell per pixel value and then resized into the window.
    */
  private val defaultGrid: TermSize = TermSize(cols = 100, rows = 28)

  def open(info: SessionInfo, onActivity: () => Unit, onEnded: Ended => Unit): ShellView[JfxNode] =
    val inner = TerminalView(client.service, info.id, metrics)
    client.sink.plug(inner)
    inner.onOutput(onActivity)
    inner.onSessionEnded(onEnded)
    new ShellView[JfxNode]:
      def node: JfxNode = inner
      def focus(): Unit = inner.requestFocus()
      def onActivity(listener: () => Unit): Unit = inner.onOutput(listener)
      def onEnded(listener: Ended => Unit): Unit = inner.onSessionEnded(listener)

      /** Out of the sink first: a push that lands after this must not touch a disposed view, and the sink must not keep its frame alive. */
      def dispose(): Unit =
        client.sink.unplug(inner)
        inner.dispose()

  def create(size: TermSize, onCreated: Option[SessionInfo] => Unit): Unit =
    client.create(cwd, size, onCreated)

  def close(info: SessionInfo): Unit =
    client.close(info.id, () => ())

  def gridFor(width: Double, height: Double): TermSize =
    if width <= TerminalView.scrollbarWidth || height <= 0 then defaultGrid
    else
      TermSize(
        cols = metrics.layout.colsFor(width - TerminalView.scrollbarWidth),
        rows = metrics.layout.rowsFor(height)
      )
