package corvi.terminal

import ox.*
import ox.channels.Channel

/** Starts a terminal's watcher fork inside the application's Ox scope.
  *
  * This class exists for one reason: JavaFX's thread cannot fork. Ox refuses any fork from a thread that is not the scope's owner or a
  * thread started inside it — *"Forks can only be started inside a running concurrency scope, or from other forks started in the scope.
  * Creating this fork was attempted from an unrelated thread: JavaFX Application Thread."* — and JavaFX always runs handlers on a
  * toolkit-owned thread, whatever thread called `Application.launch`. The FX side therefore hands a freshly created session over this
  * channel, and a loop running *inside* the scope does the forking. It is the only channel in the app, and `docs/direct-style.md` has the
  * two experiments behind that.
  *
  * The sessions a watcher fork is waiting on cannot be cancelled through this channel, and do not need to be: closing a session destroys
  * its shell, which is what wakes its watcher, and the scope's own cancellation covers the rest when the app quits. Keeping the loop
  * stateless is why this is a channel and not an actor — there is state for an actor to own.
  */
private[corvi] final class SessionForker(sessions: Channel[TerminalSession]):
  /** Asks the scope to watch this session. If the scope has gone — the app is quitting, and the shell dies with the process — there is
    * nothing to watch and nothing to do.
    */
  def watch(session: TerminalSession): Unit =
    sessions.sendOrClosed(session).discard

  /** Ends the loop that watches sessions. Called once the toolkit has gone. */
  def close(): Unit = sessions.done()

private[corvi] object SessionForker:
  /** The scope-side half of the bridge: forks the loop that watches the sessions the FX side hands over, and answers with the forker it
    * keeps. Call this inside a scope, before the FX side needs it.
    */
  def inScope(using Ox): SessionForker =
    val sessions = Channel.buffered[TerminalSession](capacity = 128)
    // The loop runs inside the scope — which is what makes it able to fork at all — and ends when the
    // channel is closed or the scope is cancelled. A closed channel is how it is told to stop, not a
    // failure, so `foreachOrError` rather than `foreach`.
    forkDiscard:
      sessions.foreachOrError(session => forkDiscard(session.watch())).discard
    new SessionForker(sessions)
