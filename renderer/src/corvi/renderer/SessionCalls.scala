package corvi.renderer

import corvi.contract.{Ended, Row, SessionId, Snapshot, TermSize}

/** What the client's runtime tells a view. Every method runs on the **FX thread**: the runtime hands the call over with
  * `Platform.runLater`, so a view never has to guard its state.
  */
trait SessionEvents:
  def attached(snapshot: Snapshot): Unit
  def history(rows: Vector[Row]): Unit
  def sessionEnded(ended: Ended): Unit
  def failed(message: String): Unit

/** What a view asks of the client: the session operations, submitted to the one runtime that owns the connection and its reader. Every
  * method returns at once; results arrive through the view's `SessionEvents` on the FX thread.
  *
  * One runtime for the whole client is not an implementation detail. A client used from several Kyo runtimes — one per view, say — is a
  * handler whose received messages were read but whose pushes then went undispatched, and the failure was intermittent; the client owns the
  * queue, the views own nothing but their state.
  */
trait SessionCalls:
  def attach(session: SessionId, events: SessionEvents): Unit
  def text(session: SessionId, text: String): Unit
  def resize(session: SessionId, size: TermSize): Unit
  def history(session: SessionId, from: Int, count: Int): Unit
  def close(session: SessionId): Unit
