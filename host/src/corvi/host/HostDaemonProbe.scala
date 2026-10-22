package corvi.host

import corvi.contract.*
import corvi.contract.Frame as ScreenFrame
import corvi.protocol.*
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.Frame
import scala.concurrent.duration.*

/** The acceptance test the whole design exists for: the shells live in a daemon, and the window can be killed and restarted without them
  * noticing.
  *
  * Two sessions are created, typed into and photographed; the client is thrown away and a new one connects — a UI restart — and the same
  * host, the same shells and the same screens have to be there. Then a second client joins while the first is live, a bad token is refused,
  * a stale socket is replaced, and `host.stop` takes the daemon and both shells with it.
  *
  * ./mill -i host.runMain corvi.host.HostDaemonProbe
  */
object HostDaemonProbe:
  private val waitTimeout: scala.concurrent.duration.Duration = 30.seconds

  def main(args: Array[String]): Unit =
    val reporter = Reporter()
    val temp = Files.createTempDirectory("corvi-host-probe")
    val socket = temp.resolve("host.sock")
    val paths = HostPaths(socket, temp.resolve("token"))
    var host: Option[java.lang.Process] = None
    try
      paths.prepare()
      val token = paths.token()
      host = Some(spawn(paths))
      reporter.report("the daemon binds its socket", awaitSocket(socket), s"socket $socket")
      // The check HostConnection uses to decide whether to spawn a daemon at all: a live listener has to read as live, or every UI start
      // would spawn a second host and wait for a socket that is already there. It opens a UNIX-family channel on purpose.
      reporter.report(
        "a live daemon answers the client's liveness check",
        SocketWire.answersUnsafe(socket, scala.concurrent.duration.Duration(500, "ms")),
        s"socket $socket"
      )

      // A connection that has not said hello is refused, and a wrong token is refused in a way a client can match.
      val unauthorized = runSession(paths) { client =>
        for
          bad <- Abort.run[TerminalError | Closed](client.hello("not-the-token"))
          listed <- Abort.run[TerminalError | Closed](client.list)
        yield (bad, listed)
      }
      reporter.report(
        "a bad token is refused, and so is a call before hello",
        unauthorized.exists { case (bad, listed) => isUnauthorized(bad) && isUnauthorized(listed) },
        unauthorized.map { case (bad, listed) => s"hello: $bad, list: $listed" }.getOrElse("no answer")
      )

      // Two sessions, a command in each, and the pictures the client drew.
      val before = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          status <- client.status
          created <- Async.foreach((1 to 2).toVector)(index => client.create(cwd, TermSize(80 - index * 5, 24 - index)))
          _ <- client.text(created(0).id, "printf 'FIRST_%s\n' BEFORE\r")
          _ <- client.text(created(1).id, "printf 'SECOND_%s\n' BEFORE\r")
          _ <- awaitLine(client, created(0).id, "FIRST_BEFORE")
          _ <- awaitLine(client, created(1).id, "SECOND_BEFORE")
          first <- settle(client, created(0).id)
          second <- settle(client, created(1).id)
        yield (status, created, first, second)
      }
      val (beforeStatus, sessions, beforeFirst, beforeSecond) = before.getOrElse(fail("the first client could not set up two sessions"))
      reporter.report(
        "two sessions run a command each",
        beforeFirst.exists(frame => hasLine(frame, "FIRST_BEFORE")) && beforeSecond.exists(frame => hasLine(frame, "SECOND_BEFORE")),
        sessions.map(info => s"${info.id.value} pid ${info.shellPid}").mkString(", ")
      )

      // The UI goes away and comes back: a new runtime, a new connection, no state carried over.
      val after = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          status <- client.status
          listing <- client.list
          first <- settle(client, sessions(0).id)
          second <- settle(client, sessions(1).id)
          _ <- client.text(sessions(0).id, "printf 'STILL_%s\n' ALIVE\r")
          alive <- awaitLine(client, sessions(0).id, "STILL_ALIVE")
        yield (status, listing, first, second, alive)
      }
      val (afterStatus, afterListing, afterFirst, afterSecond, inputWorks) =
        after.getOrElse(fail("the second client could not reattach"))
      reporter.report(
        "the host is the same process after the client restarts",
        beforeStatus.pid == afterStatus.pid,
        s"pid ${beforeStatus.pid} -> ${afterStatus.pid}"
      )
      val beforeShells = sessions.map(info => info.id.value -> info.shellPid).toSet
      val afterShells = afterListing.map(info => info.id.value -> info.shellPid).toSet
      reporter.report(
        "and the shells are the same processes",
        beforeShells == afterShells && beforeShells.size == 2,
        s"$beforeShells -> $afterShells"
      )
      reporter.report(
        "and attaching reproduces each screen",
        beforeFirst.zip(afterFirst).exists { case (was, now) => sameScreen(was, now) } &&
          beforeSecond.zip(afterSecond).exists { case (was, now) => sameScreen(was, now) },
        s"first ${afterFirst.map(frame => lastLines(frame).mkString(" / "))}"
      )
      reporter.report("and the shells still take input", inputWorks, "")

      // A second client joins while the first is still connected, and both hear what the shell does.
      val twoClients = runTwoSessions(paths, token) { (first, second) =>
        for
          _ <- first.attach(sessions(0).id)
          _ <- second.attach(sessions(0).id)
          _ <- first.text(sessions(0).id, "printf 'BOTH_%s\n' CLIENTS\r")
          firstSaw <- awaitLine(first, sessions(0).id, "BOTH_CLIENTS")
          secondSaw <- awaitLine(second, sessions(0).id, "BOTH_CLIENTS")
        yield firstSaw && secondSaw
      }
      reporter.report("a second client attaches without disturbing the first", twoClients.contains(true), s"$twoClients")

      // A socket whose owning daemon is gone is replaced; a socket that is somebody else's is left alone.
      val staleDirectory = Files.createTempDirectory("corvi-host-stale")
      val staleSocket = staleDirectory.resolve("host.sock")
      val staleListener = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
      val _ = staleListener.bind(java.net.UnixDomainSocketAddress.of(staleSocket))
      val _ = staleListener.close()
      val stalePaths = HostPaths(staleSocket, staleDirectory.resolve("token"))
      val _ = Files.writeString(stalePaths.ownerFile, "999999")
      val deadHost = spawnFiles(stalePaths)
      reporter.report("a socket whose daemon died is replaced", awaitSocket(staleSocket), s"socket $staleSocket")
      reporter.report(
        "and the owner marker now names the host that took the socket",
        poll(scala.concurrent.duration.Duration(5, "s"))(stalePaths.owner().exists(pid => pid == deadHost.pid())),
        s"owner ${stalePaths.owner()}, replacement pid ${deadHost.pid()}"
      )
      deadHost.destroy()

      val foreignDirectory = Files.createTempDirectory("corvi-host-foreign")
      val foreignSocket = foreignDirectory.resolve("host.sock")
      val foreignListener = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
      val _ = foreignListener.bind(java.net.UnixDomainSocketAddress.of(foreignSocket))
      val foreignPaths = HostPaths(foreignSocket, foreignDirectory.resolve("token"))
      val foreignHost = spawnFiles(foreignPaths)
      val refused = poll(scala.concurrent.duration.Duration(15, "s"))(!foreignHost.isAlive)
      reporter.report(
        "a live socket that is not this daemon's is refused, not replaced",
        refused && Files.exists(foreignSocket) && !Files.exists(foreignPaths.socket.resolveSibling("host.sock.owner")),
        s"exited ${!foreignHost.isAlive}, socket still there ${Files.exists(foreignSocket)}"
      )
      val _ = foreignListener.close()
      foreignHost.destroy()
      val _ = deleteTree(foreignDirectory)
      val _ = deleteTree(staleDirectory)

      // Permissions: the door is 0600 inside a 0700 directory, and so is the token.
      reporter.report(
        "the socket, the token and the directory are private",
        permissions(paths.socket) == "rw-------" && permissions(paths.tokenFile) == "rw-------" &&
          permissions(paths.directory) == "rwx------",
        s"socket ${permissions(paths.socket)}, token ${permissions(paths.tokenFile)}, directory ${permissions(paths.directory)}"
      )

      // A token is good for the connection that presented it; a second bad hello does not de-authorize it, which is the documented choice.
      val secondHello = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          afterBad <- Abort.run[TerminalError | Closed](client.hello("wrong"))
          listed <- Abort.run[TerminalError | Closed](client.list)
        yield (afterBad, listed)
      }
      reporter.report(
        "a bad hello after a good one is refused and does not revoke the connection",
        secondHello.exists { case (afterBad, listed) => isUnauthorized(afterBad) && isSuccess(listed) },
        secondHello.map { case (afterBad, listed) => s"hello: $afterBad, list ok ${isSuccess(listed)}" }.getOrElse("no answer")
      )

      // Reattaching while the shell is flooding: the new client's frame converges on the screen, not on a half-written one.
      val flooded = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          _ <- client.text(sessions(0).id, "seq 1 400\r")
          duringFlood <- settle(client, sessions(0).id)
          _ <- awaitLine(client, sessions(0).id, "400")
          settled <- settle(client, sessions(0).id)
        yield (duringFlood, settled)
      }
      reporter.report(
        "a client that attaches during output still converges",
        flooded.exists { case (during, settled) => during.isDefined && settled.exists(frame => hasLine(frame, "400")) },
        flooded.map { case (_, settled) => s"frames ${settled.map(frame => lastLines(frame).mkString(" / "))}" }.getOrElse("no answer")
      )

      // File descriptors across reconnects: a daemon meant to survive hundreds of UI restarts must not grow by one per connection.
      val beforeDescriptors = descriptors(host)
      (1 to 10).foreach(_ => runSession(paths)(client => client.hello(token).map(_ => ())))
      val afterDescriptors = descriptors(host)
      reporter.report(
        "reconnecting does not leak a descriptor per connection",
        afterDescriptors - beforeDescriptors <= 2,
        s"descriptors $beforeDescriptors -> $afterDescriptors after 10 reconnects; ${descriptorDiff(host)}; log ${logTail(temp)}"
      )

      // A second daemon on the same socket refuses to start, and leaves the running one alone.
      val intruder = spawnFiles(paths)
      val intruderRefused = poll(scala.concurrent.duration.Duration(15, "s"))(!intruder.isAlive)
      val firstStillWorks = runSession(paths)(client => client.hello(token).map(_ => ()))
      reporter.report(
        "a second daemon on the same socket is refused, and the first keeps working",
        intruderRefused && firstStillWorks.isDefined && host.exists(_.isAlive),
        s"intruder exited ${!intruder.isAlive}, first alive ${host.map(_.isAlive)}"
      )
      intruder.destroy()

      // A daemon that dies is noticed — a connect fails rather than hanging — and a replacement answers.
      host.foreach(process => process.destroy())
      val sawDeath = poll(scala.concurrent.duration.Duration(10, "s"))(!host.exists(_.isAlive))
      val afterDeath = runSession(paths)(client => client.hello(token).map(_ => ()))
      host = Some(spawnFiles(paths))
      val _ = awaitSocket(paths.socket)
      val recovered = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          listed <- client.list
          fresh <- client.create(cwd, TermSize(60, 18))
          _ <- client.text(fresh.id, "printf 'AFTER_%s\n' DEATH\r")
          alive <- awaitLine(client, fresh.id, "AFTER_DEATH")
        yield (listed, fresh, alive)
      }
      reporter.report(
        "a host that dies is noticed, and a replacement answers",
        sawDeath && afterDeath.isEmpty && recovered.exists { case (listed, _, alive) => listed.isEmpty && alive },
        s"dead ${afterDeath.isEmpty}, sessions on the replacement ${recovered.map { case (listed, _, _) => listed.size }}"
      )

      // Stopping the replacement: the process goes, its socket and owner marker go, and the shell it held goes.
      val replacementShell = recovered.map { case (_, fresh, _) => fresh.shellPid }
      val stoppedReplacement = runSession(paths) { client =>
        for
          _ <- client.hello(token)
          outcome <- Abort.run[TerminalError | Closed](client.stop)
        yield outcome
      }
      val replacementExited = stoppedReplacement.isDefined && host.exists(process => process.waitFor(20, TimeUnit.SECONDS))
      reporter.report(
        "host.stop ends the daemon",
        replacementExited,
        s"stop $stoppedReplacement, alive ${host.map(_.isAlive)}"
      )
      reporter.report(
        "and takes its shells with it",
        replacementShell.forall(pid => poll(waitTimeout)(!ProcessHandle.of(pid).isPresent)),
        s"shell $replacementShell"
      )
      reporter.report(
        "and its socket and owner marker",
        poll(scala.concurrent.duration.Duration(10, "s"))(!Files.exists(socket) && !Files.exists(paths.ownerFile)),
        s"socket exists ${Files.exists(socket)}, owner exists ${Files.exists(paths.ownerFile)}"
      )
      reporter.report(
        "and stops answering the liveness check",
        !SocketWire.answersUnsafe(socket, scala.concurrent.duration.Duration(500, "ms")),
        s"socket $socket"
      )
    finally
      host.foreach(process => if process.isAlive then process.destroy())
      val _ = deleteTree(temp)
    reporter.finish()

  // --- the phases --------------------------------------------------------------------------------------------------------------------

  private def runSession[A](paths: HostPaths)(f: TerminalService => A < (Async & Abort[TerminalError | Closed]))(using
      Frame
  ): Option[A] =
    runKyo {
      Scope.run {
        for
          client <- connect(paths)
          value <- f(client)
        yield value
      }
    }

  private def runTwoSessions[A](paths: HostPaths, token: String)(
      f: (TerminalService, TerminalService) => A < (Async & Abort[TerminalError | Closed])
  )(using Frame): Option[A] =
    runSession(paths) { first =>
      for
        _ <- first.hello(token)
        value <- Scope.run {
          for
            second <- connect(paths)
            _ <- second.hello(token)
            result <- f(first, second)
          yield result
        }
      yield value
    }

  private def connect(paths: HostPaths)(using Frame): TerminalService < (Async & Scope & Abort[TerminalError | Closed]) =
    for
      closed <- Latch.init(1)
      opened <- Abort.run[Throwable](SocketWire.connect(paths.socket, closed))
      wire <- opened match
        case Result.Success(value) => Sync.defer(value)
        case Result.Failure(error) => Abort.fail(HostUnavailable(s"connect failed: $error"))
        case Result.Panic(error)   => Abort.fail(HostUnavailable(s"connect failed: $error"))
      transport <- JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength)
      handler <- JsonRpcHandler.init(transport, ClientRoutes.routes(Ignored)*)
    yield TerminalClient(handler)

  private object Ignored extends FrameSink:
    def damage(id: SessionId, damage: Damage): Unit < Sync = Sync.defer(())
    def ended(id: SessionId, ended: Ended): Unit < Sync = Sync.defer(())

  /** Runs a phase on a runtime of its own: a UI restart is a new runtime and a new connection, with nothing carried across. */
  private def runKyo[A](v: A < (Async & Scope & Abort[Any]))(using Frame): Option[A] =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(180.seconds)(v) match
      case Result.Success(value) => Some(value)
      case Result.Failure(error) =>
        java.lang.System.err.println(s"corvi host probe: a phase failed: $error")
        None
      case Result.Panic(error) =>
        java.lang.System.err.println(s"corvi host probe: a phase panicked: $error")
        error.printStackTrace()
        None

  private def isSuccess[A](result: Result[TerminalError | Closed, A]): Boolean = result match
    case Result.Success(_) => true
    case _                 => false

  private def isUnauthorized[A](result: Result[TerminalError | Closed, A]): Boolean = result match
    case Result.Failure(_: Unauthorized) => true
    case _                               => false

  // --- helpers -----------------------------------------------------------------------------------------------------------------------

  private def spawnFiles(paths: HostPaths): java.lang.Process = spawn(paths)

  /** The descriptors the daemon opened since the first measurement, so a leak names itself. */
  private def descriptorDiff(process: Option[java.lang.Process]): String =
    process match
      case None          => "no host"
      case Some(running) =>
        val builder = java.lang.ProcessBuilder("sh", "-c", s"lsof -p ${running.pid()} 2>/dev/null | tail -4")
        val started = builder.redirectErrorStream(true).start()
        val text = new String(started.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        val _ = started.waitFor()
        text.linesIterator.map(_.replaceAll("\\s+", " ")).toVector.takeRight(3).mkString(" | ")

  private def logTail(directory: java.nio.file.Path): String =
    val log = directory.resolve("probe-host.log")
    if !Files.exists(log) then "none"
    else
      val lines = Files.readString(log).linesIterator.toVector.filter(line => line.contains("wire[") || line.contains("connection["))
      lines.takeRight(8).mkString(" / ")

  private def permissions(path: java.nio.file.Path): String =
    try java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(path))
    catch case _: Throwable => "?"

  /** Open files of the daemon, as `lsof` counts them: the regression for one descriptor per reconnect. */
  private def descriptors(process: Option[java.lang.Process]): Int =
    process match
      case None          => 0
      case Some(running) =>
        val builder = java.lang.ProcessBuilder("sh", "-c", s"lsof -p ${running.pid()} 2>/dev/null | wc -l")
        val started = builder.redirectErrorStream(true).start()
        val text = new String(started.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        val _ = started.waitFor()
        text.toIntOption.getOrElse(0)

  private def spawn(paths: HostPaths): java.lang.Process =
    val javaBinary = Paths.get(java.lang.System.getProperty("java.home"), "bin", "java").toString
    val log = paths.directory.resolve("probe-host.log")
    val builder = java.lang
      .ProcessBuilder(
        javaBinary,
        "-Dkyo.scheduler.virtualizeWorkers=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-cp",
        java.lang.System.getProperty("java.class.path"),
        "corvi.host.HostMain"
      )
      .redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(log.toFile))
      .redirectErrorStream(true)
    val _ = builder.environment().put("CORVI_HOST_SOCKET", paths.socket.toString)
    builder.start()

  private def awaitSocket(socket: Path): Boolean = poll(waitTimeout)(answers(socket))

  private def answers(socket: Path): Boolean =
    try
      val probe = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(socket))
      val _ = probe.close()
      true
    catch case _: Throwable => false

  private def awaitLine(client: TerminalService, id: SessionId, line: String)(using
      Frame
  ): Boolean < (Async & Abort[TerminalError | Closed]) =
    val deadline = java.lang.System.nanoTime() + waitTimeout.toNanos
    def loop: Boolean < (Async & Abort[TerminalError | Closed]) =
      for
        snapshot <- client.attach(id)
        found = hasLine(ScreenFrame.from(snapshot), line)
        next <-
          if found || java.lang.System.nanoTime() > deadline then Sync.defer(found)
          else Async.sleep(50.millis).andThen(loop)
      yield next
    loop

  /** A frame that has stopped changing: the shell repaints its prompt after a command, and a capture taken mid-redraw is not the screen. */
  private def settle(client: TerminalService, id: SessionId)(using
      Frame
  ): Option[ScreenFrame] < (Async & Abort[TerminalError | Closed]) =
    def loop(previous: Option[ScreenFrame], attempts: Int): Option[ScreenFrame] < (Async & Abort[TerminalError | Closed]) =
      if attempts <= 0 then Sync.defer(previous)
      else
        for
          _ <- Async.sleep(120.millis)
          snapshot <- client.attach(id)
          current = ScreenFrame.from(snapshot)
          next <- if previous.contains(current) then Sync.defer(Some(current)) else loop(Some(current), attempts - 1)
        yield next
    loop(None, 12)

  private def hasLine(frame: ScreenFrame, line: String): Boolean =
    frame.rows.exists(row => row.cells.map(_.text).mkString.trim == line)

  private def lastLines(frame: ScreenFrame): Vector[String] =
    frame.rows.map(row => row.cells.map(_.text).mkString.trim).filter(_.nonEmpty).takeRight(2)

  private def sameScreen(left: ScreenFrame, right: ScreenFrame): Boolean = left.size == right.size && left.rows == right.rows

  private def cwd: String = java.lang.System.getProperty("user.dir")

  private def poll(timeout: scala.concurrent.duration.Duration)(condition: => Boolean): Boolean =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    var satisfied = condition
    while !satisfied && java.lang.System.nanoTime() < deadline do
      Thread.sleep(50)
      satisfied = condition
    satisfied

  private def fail(message: String): Nothing = throw new IllegalStateException(message)

  private def deleteTree(root: Path): Unit =
    val _ = Files
      .walk(root)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(path => { val _ = Files.deleteIfExists(path) })

  private final class Reporter:
    private var failed = 0

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then failed += 1
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(3).foreach(line => println(s"      | $line"))

    def finish(): Unit =
      println(if failed == 0 then "all host daemon checks passed" else s"$failed host daemon check(s) failed")
      java.lang.System.exit(if failed == 0 then 0 else 1)
