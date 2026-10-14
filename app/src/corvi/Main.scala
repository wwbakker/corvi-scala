package corvi

import corvi.terminal.SessionForker
import ox.*

/** The application's entry point, and the arrangement that gives JavaFX and Ox each what it needs.
  *
  * Both want the process's top level, and both can have it: JavaFX gets the main thread — `CorviApp.launch` calls `Application.launch` on
  * this thread, *inside* the scope — and Ox gets every long-lived piece of work, since each terminal's watcher is a fork of this scope.
  * That is what the experiments in `docs/direct-style.md` settled: `OxApp` cannot host JavaFX (it runs its entry point on a virtual thread,
  * and a window launched that way never lays out), while `supervised` around `Application.launch` costs nothing at all and lets the scope
  * cancel whatever is left when the app quits.
  *
  * The one thing the scope cannot do is fork from JavaFX's handlers: JavaFX runs them on a toolkit-owned thread, and Ox refuses any fork
  * from outside the scope's own thread tree. `SessionForker` is the bridge — the app's only channel — and it exists for exactly that
  * reason.
  */
object Main:
  def main(args: Array[String]): Unit =
    supervised {
      val forker = SessionForker.inScope
      CorviApp.launch(forker, args)
      // The toolkit is gone and the scope body is done: closing the channel ends the loop that watches
      // sessions, and the scope then cancels whatever is still forked. A session that is still open kills
      // its shell on the way out.
      forker.close()
    }
