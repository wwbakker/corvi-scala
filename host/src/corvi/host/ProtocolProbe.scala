package corvi.host

import corvi.contract.Frame as ScreenFrame
import corvi.contract.*
import corvi.protocol.*
import kyo.*
import kyo.Frame
import scala.concurrent.duration.*

/** The protocol's regression suite: real shells, driven through the in-memory `kyo-jsonrpc` peers, with each client holding a screen it
  * only ever heard about from pushed deltas.
  *
  * ./mill -i host.runMain corvi.host.ProtocolProbe
  */
object ProtocolProbe:
  /** A push tick every 200 ms: slow enough that a delta covers several updates, fast enough not to slow the checks down. */
  private val pushInterval: kyo.Duration = kyo.Duration.fromScala(200.millis)

  private val waitTimeout: scala.concurrent.duration.Duration = 20.seconds

  def main(args: Array[String]): Unit =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(240.seconds)(probe) match
      case Result.Success(failures) =>
        println(if failures == 0 then "all protocol checks passed" else s"$failures protocol check(s) failed")
        sys.exit(if failures == 0 then 0 else 1)
      case Result.Failure(error) =>
        println(s"the protocol probe failed: $error")
        sys.exit(1)
      case Result.Panic(error) =>
        println(s"the protocol probe panicked: $error")
        error.printStackTrace()
        sys.exit(1)

  private def probe(using Frame): Int < (Async & Scope & Abort[Any]) =
    val reporter = Reporter()
    for
      cache <- FrameCache.init
      peers <- TerminalPeers.inMemory(cache, pushInterval, HostSession.loginShell)
      (client, host) = peers
      main <- screenChecks(client, cache, reporter)
      listed <- client.list
      info <- listed.find(_.id == main) match
        case Some(found) => Sync.defer(found)
        case None        => Abort.fail(RemoteError(0, "the session vanished"))
      _ <- resizeAndHistoryChecks(client, cache, info, reporter)

      // A second client, which attaches while the first one's shell is flooding the screen.
      secondCache <- FrameCache.init
      second <- host.client(secondCache)
      _ <- floodChecks(client, second.client, cache, secondCache, main, reporter)
      _ <- duplicateClientCheck(host, second.id, reporter)
      _ <- teardownChecks(client, second.client, host, cache, secondCache, main, reporter)
      _ <- closedTransportCheck(host, reporter)
      _ <- startFailureCheck(reporter)
    yield reporter.failures

  /** PASS/FAIL lines and the count the probe exits with. */
  private final class Reporter:
    private var failed = 0

    def report(name: String, ok: Boolean, detail: String): Unit =
      if !ok then failed += 1
      println(s"${if ok then "PASS" else "FAIL"}  $name")
      detail.linesIterator.filter(_.trim.nonEmpty).toSeq.takeRight(4).foreach(line => println(s"      | $line"))

    def failures: Int = failed

  /** A client's side of the pipeline: a frame per session, moved only by the host's pushes, plus what it was told about the end. */
  private final class FrameCache private (
      frames: AtomicRef[Map[SessionId, ScreenFrame]],
      ends: AtomicRef[Map[SessionId, Vector[Ended]]],
      damageCount: AtomicInt,
      widest: AtomicInt
  ) extends FrameSink:
    def damage(id: SessionId, damage: Damage): Unit < Sync =
      for
        _ <- frames.updateAndGet(current => current.updatedWith(id)(frame => frame.map(applied => applied.apply(damage))))
        _ <- damageCount.getAndIncrement.map(_ => ())
        _ <- widest.updateAndGet(current => math.max(current, damage.rows.size)).map(_ => ())
      yield ()

    def ended(id: SessionId, ended: Ended): Unit < Sync =
      ends.updateAndGet(current => current.updated(id, current.getOrElse(id, Vector.empty) :+ ended)).map(_ => ())

    /** Seeds a session's frame from the snapshot an attach returned. */
    def seed(snapshot: Snapshot): Unit < Sync =
      frames.updateAndGet(_.updated(snapshot.id, ScreenFrame.from(snapshot))).map(_ => ())

    def frame(id: SessionId): Maybe[ScreenFrame] < Sync = frames.get.map(current => Maybe.fromOption(current.get(id)))

    def endsFor(id: SessionId): Vector[Ended] < Sync = ends.get.map(current => current.getOrElse(id, Vector.empty))

    def damages: Int < Sync = damageCount.get

    def widestDamage: Int < Sync = widest.get

  private object FrameCache:
    def init(using Frame): FrameCache < Sync =
      for
        frames <- AtomicRef.init(Map.empty[SessionId, ScreenFrame])
        ends <- AtomicRef.init(Map.empty[SessionId, Vector[Ended]])
        damageCount <- AtomicInt.init(0)
        widest <- AtomicInt.init(0)
      yield new FrameCache(frames, ends, damageCount, widest)

  /** Listing order, the frame the client draws, deltas, resizes both ways, and history. */
  private def screenChecks(client: TerminalService, cache: FrameCache, reporter: Reporter)(using
      Frame
  ): SessionId < (Async & Abort[Any]) =
    val report = reporter.report
    for
      empty <- client.list
      _ = report("the host starts with no sessions", empty.isEmpty, s"${empty.size} sessions")

      // Enough sessions that lexicographic order would be visibly wrong: session-10 sorts before session-2.
      _ <- Async.foreachDiscard((1 to 11).toVector)(_ => client.create(cwd, TermSize(40, 12)))
      ordered <- client.list
      expectedIds = (1 to 11).map(index => s"session-$index")
      _ = report(
        "sessions are listed in the order they were created",
        ordered.map(_.id.value) == expectedIds && ordered.map(_.index) == (0 until 11),
        s"${ordered.take(3).map(info => s"${info.id.value}/${info.index}").mkString(", ")} ... ${ordered.lastOption.map(_.id.value)}"
      )
      _ <- Async.foreachDiscard(ordered.toVector)(info => client.close(info.id))

      info <- client.create(cwd, TermSize(80, 24))
      _ = report(
        "a session is created over the wire",
        info.running && info.size == TermSize(80, 24) && info.index == 11,
        s"info $info"
      )
      attached <- client.attach(info.id)
      _ = report(
        "attach carries the theme as well as the screen",
        attached.theme == TerminalTheme(foreground = Rgb(230, 237, 243), background = Rgb(13, 17, 23)),
        s"theme ${attached.theme}"
      )
      _ <- cache.seed(attached)

      // From here on the client's frame moves only when the host pushes a delta: nothing pulls it.
      _ <- client.text(info.id, "echo HELLO_FROM_THE_WIRE\n")
      pushed <- awaitFrame(cache, info.id)(frame => lines(frame).contains("HELLO_FROM_THE_WIRE"))
      reports <- cache.damages
      widest <- cache.widestDamage
      _ = report(
        "a command typed over the wire appears in the pushed frame",
        pushed.exists(frame => lines(frame).contains("HELLO_FROM_THE_WIRE")) && reports > 0,
        s"$reports deltas, widest $widest of ${attached.size.rows} rows"
      )
      _ = report("and the deltas are deltas, not whole screens", widest < attached.size.rows, s"widest delta $widest")

      converged <- converges(client, cache, info.id)
      settled <- cache.frame(info.id)
      fresh <- client.attach(info.id)
      _ = report(
        "the pushed frame equals a fresh snapshot",
        converged,
        settled.map(frame => difference(frame, ScreenFrame.from(fresh))).getOrElse("no frame")
      )

      // A delta can name a row the frame no longer has (one sent before a shrink that the client applied first): it is dropped, not thrown.
      stale = Damage(Vector(RowDamage(9999, Row(Vector.empty)), RowDamage(-1, Row(Vector.empty))), None, None, None, None)
      dropped <- Sync.defer(settled.map(frame => frame.apply(stale) == frame))
      _ = report("a damage row outside the grid is dropped, not thrown", dropped.contains(true), s"${dropped.map(_ => "unchanged")}")

    yield info.id

  /** A height-only resize (where the rows that survive must not be blanked), a shrink, and history. */
  private def resizeAndHistoryChecks(client: TerminalService, cache: FrameCache, info: SessionInfo, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      // A height-only resize: every row has to come down, or the rows that survived would be blanked.
      beforeResize <- cache.frame(info.id)
      taller = TermSize(cols = 80, rows = 40)
      _ <- client.resize(info.id, taller)
      grown <- awaitFrame(cache, info.id)(frame => frame.size == taller && frame.rows.size == taller.rows)
      _ = report(
        "a same-width resize keeps the rows that survive",
        grown.exists(frame =>
          frame.rows.size == taller.rows && beforeResize.exists(before => text(frame.rows.head) == text(before.rows.head))
        ),
        s"${grown.map(_.size)}, first row '${grown.map(frame => text(frame.rows.head).trim).getOrElse("")}'"
      )
      grownConverged <- converges(client, cache, info.id)
      _ = report("and the client's frame still equals the host's screen", grownConverged, "")

      // A shrink, including a narrower one, which re-indexes every row.
      smaller = TermSize(cols = 60, rows = 18)
      _ <- client.resize(info.id, smaller)
      shrunk <- awaitFrame(cache, info.id)(frame => frame.size == smaller && frame.rows.size == smaller.rows)
      _ = report("a shrink reaches the frame", shrunk.exists(_.rows.size == smaller.rows), s"size ${shrunk.map(_.size)}")
      shrunkConverged <- converges(client, cache, info.id)
      shrunk <- cache.frame(info.id)
      _ = report("and converges on the narrower screen", shrunkConverged, s"rows ${shrunk.map(_.rows.size)}")

      _ <- client.text(info.id, "stty size\n")
      stty <- awaitFrame(cache, info.id)(frame => lines(frame).exists(_.trim == "18 60"))
      _ = report("the PTY hears about the resize too", stty.exists(frame => lines(frame).exists(_.trim == "18 60")), "")

      // History: pages, a line the screen has scrolled past, and a page past the oldest row.
      _ <- client.text(info.id, "seq 1 200\n")
      _ <- awaitFrame(cache, info.id)(frame => lines(frame).exists(_.trim == "200"))
      historyPage <- client.history(info.id, from = 1, count = 10)
      oldPage <- client.history(info.id, from = 1, count = 200)
      past <- client.history(info.id, from = 100000, count = 10)
      cached <- cache.frame(info.id)
      onScreen = cached.map(frame => lines(frame)).getOrElse(Vector.empty)
      _ = report(
        "history pages come back over the wire",
        historyPage.nonEmpty && oldPage.exists(row => text(row).trim == "100") && !onScreen.exists(_.trim == "100"),
        s"${historyPage.size} rows newest first: ${historyPage.take(3).map(row => text(row).trim).mkString(" ")}"
      )
      _ = report("a page past the oldest row is empty, not an error", past.isEmpty, s"${past.size} rows")
    yield ()

  /** Attaching while the shell is flooding: the new client's baseline must be the frame it was handed, not a window it never saw. */
  private def floodChecks(
      client: TerminalService,
      late: TerminalService,
      cache: FrameCache,
      lateCache: FrameCache,
      main: SessionId,
      reporter: Reporter
  )(using Frame): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      _ <- client.text(main, "seq 1 2000\n")
      _ <- awaitFrame(cache, main)(frame => lines(frame).exists(line => line.toIntOption.exists(_ > 20)))
      attached <- late.attach(main)
      _ <- lateCache.seed(attached)
      _ <- awaitFrame(cache, main)(frame => lines(frame).exists(_.trim == "2000"))
      converged <- converges(late, lateCache, main)
      lateDamages <- lateCache.damages
      lateFrame <- lateCache.frame(main)
      _ = report(
        "a client that attaches mid-flood converges on the host's screen",
        converged && lateDamages > 0,
        s"$lateDamages deltas after attach, frame ${lateFrame.map(_.rows.size)} rows"
      )
    yield ()

  /** Two clients on one host, and an id that is already taken. */
  private def duplicateClientCheck(host: TerminalPeers.InMemoryHost, taken: ClientId, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Scope & Abort[Any]) =
    val report = reporter.report
    for
      duplicateCache <- FrameCache.init
      refused <- Abort.run[ClientAlreadyBound](host.client(duplicateCache, taken))
      _ = report(
        "a client id that is already connected is refused",
        refused match
          case Result.Failure(ClientAlreadyBound(id)) => id == taken
          case _                                      => false,
        s"$refused"
      )
    yield ()

  /** Ending: both clients hear it once, the ended session takes no input, and closing twice is safe. */
  private def teardownChecks(
      client: TerminalService,
      second: TerminalService,
      host: TerminalPeers.InMemoryHost,
      cache: FrameCache,
      secondCache: FrameCache,
      main: SessionId,
      reporter: Reporter
  )(using Frame): Unit < (Async & Abort[Any]) =
    val report = reporter.report
    for
      // A shell that exits on its own: both clients hear about it, once each, with the code and without `closed`.
      _ <- client.text(main, "exit\n")
      ended <- awaitEnds(cache, main)
      endedSecond <- awaitEnds(secondCache, main)
      _ = report(
        "a shell that exits is reported to every attached client, once",
        ended.size == 1 && endedSecond.size == 1,
        s"client one: ${ended.map(describe)}, client two: ${endedSecond.map(describe)}"
      )
      _ = report(
        "with its code and without the closed flag",
        ended.headOption.exists(e => e.code.contains(0) && !e.closed && e.reason.isEmpty),
        s"${ended.headOption.map(describe)}"
      )

      listed <- client.list
      _ = report(
        "an ended session stays listed, so a tab can show its final screen",
        listed.exists(entry => entry.id == main && !entry.running),
        s"${listed.map(entry => s"${entry.id.value}: running ${entry.running}").mkString(", ")}"
      )

      // A shell that is gone takes no input and no resize, and says so in a type rather than an exception.
      noText <- Abort.run[TerminalError | Closed](second.text(main, "echo too late\n"))
      noResize <- Abort.run[TerminalError | Closed](second.resize(main, TermSize(40, 10)))
      _ = report(
        "text and resize on an ended session fail with a typed error",
        noText match
          case Result.Failure(SessionEnded(id)) => id == main
          case _                                => false,
        s"$noText"
      )
      _ = report(
        "so does resize",
        noResize match
          case Result.Failure(SessionEnded(id)) => id == main
          case _                                => false,
        s"$noResize"
      )

      // Closing it now is how an ended session leaves the list, and closing twice is refused the second time.
      _ <- client.close(main)
      gone <- Abort.run[TerminalError | Closed](client.close(main))
      _ = report(
        "closing an ended session removes it, and closing again is refused",
        gone match
          case Result.Failure(UnknownSession(id)) => id == main
          case _                                  => false,
        s"$gone"
      )
      after <- client.list
      _ = report("the session is gone", !after.exists(_.id == main), s"${after.size} sessions")

      // A shell the client closes itself is reported with `closed`, and its code is advisory.
      closing <- client.create(cwd, TermSize(60, 18))
      _ <- client.attach(closing.id).map(_ => ())
      _ <- client.close(closing.id)
      closedEnd <- awaitEnds(cache, closing.id)
      _ = report(
        "a session the client closes is reported as closed",
        closedEnd.size == 1 && closedEnd.forall(_.closed),
        s"${closedEnd.map(describe)}"
      )
      remaining <- host.sessions.list
      _ = report("and nothing is left running", remaining.forall(info => !info.running), s"${remaining.size} sessions")
    yield ()

  /** A client whose transport is gone fails with `Closed` rather than hanging or throwing. */
  private def closedTransportCheck(host: TerminalPeers.InMemoryHost, reporter: Reporter)(using
      Frame
  ): Unit < (Async & Scope & Abort[Any]) =
    val report = reporter.report
    for
      holder <- AtomicRef.init[Maybe[TerminalService]](Maybe.empty)
      _ <- Async.timeout(20.seconds) {
        Scope.run {
          for
            cache <- FrameCache.init
            wired <- host.client(cache)
            _ <- holder.set(Maybe(wired.client))
            _ <- wired.client.list.map(_ => ())
          yield ()
        }
      }
      nested <- holder.get
      // The nested client's handler went with its scope, so a call on it now has no transport to go out on.
      outcome <- nested match
        case Maybe.Present(closed) => Abort.run[TerminalError | Closed](closed.list)
        case Maybe.Absent          => Sync.defer(Result.Failure(RemoteError(0, "no nested client")))
      _ = report(
        "a client whose scope ended fails with Closed",
        outcome match
          case Result.Failure(_: Closed) => true
          case _                         => false,
        s"$outcome"
      )

      // The host outlived that client: a fresh one can attach.
      freshCache <- FrameCache.init
      fresh <- host.client(freshCache)
      created <- fresh.client.create(cwd, TermSize(50, 14))
      _ = report("and a new client can work against the same host", created.running, s"created ${created.id.value}")
      _ <- fresh.client.close(created.id)
    yield ()

  /** A shell that cannot start reaches the client as a typed error instead of a session that reports `running` and dies. */
  private def startFailureCheck(reporter: Reporter)(using Frame): Unit < (Async & Scope & Abort[Any]) =
    val report = reporter.report
    for
      cache <- FrameCache.init
      peers <- TerminalPeers.inMemory(cache, pushInterval, Seq("/nonexistent/corvi-shell"))
      (client, _) = peers
      attempt <- Abort.run[TerminalError | Closed](client.create(cwd, TermSize(80, 24)))
      _ = report(
        "a shell that cannot start is a typed failure",
        attempt match
          case Result.Failure(SessionFailed(_, reason)) => reason.nonEmpty
          case _                                        => false,
        s"$attempt"
      )
      listed <- client.list
      _ = report("and leaves no session behind", listed.isEmpty, s"${listed.size} sessions")
    yield ()

  /** What differs between two frames, for the checks that compare them: the first few rows, then the non-row parts. */
  private def difference(expected: ScreenFrame, actual: ScreenFrame): String =
    val rows = expected.rows.zip(actual.rows).zipWithIndex.collect {
      case ((left, right), index) if left != right => s"row $index: '${text(left).trim}' vs '${text(right).trim}'"
    }
    val parts = Vector(
      Option.when(expected.size != actual.size)(s"size ${expected.size} vs ${actual.size}"),
      Option.when(expected.cursor != actual.cursor)(s"cursor ${expected.cursor} vs ${actual.cursor}"),
      Option.when(expected.modes != actual.modes)(s"modes ${expected.modes} vs ${actual.modes}"),
      Option.when(expected.historyRows != actual.historyRows)(s"history ${expected.historyRows} vs ${actual.historyRows}")
    ).flatten
    (rows.take(3) ++ parts).mkString("; ")

  private def describe(ended: Ended): String =
    s"code=${ended.code}, closed=${ended.closed}, reason=${ended.reason}"

  /** Whether the client's frame can be made to equal a snapshot the host takes now.
    *
    * A single comparison races the shell: the client can be a delta *ahead* of a snapshot taken a moment earlier. So the check takes a
    * fresh snapshot and gives the client a moment to reach it, and tries again if the shell moved on in between. When the screen is quiet,
    * one attempt is enough; when it is not, no claim is made.
    */
  private def converges(client: TerminalService, cache: FrameCache, id: SessionId)(using Frame): Boolean < Async =
    def attempt(remaining: Int): Boolean < Async =
      if remaining <= 0 then Sync.defer(false)
      else
        for
          snapshot <- Abort.run[TerminalError | Closed](client.attach(id)).map(_.toMaybe)
          reached <- snapshot match
            case Maybe.Present(attached) =>
              awaitFrameWithin(1500.millis)(cache, id)(frame => frame == ScreenFrame.from(attached))
                .map(_.exists(_ == ScreenFrame.from(attached)))
            case Maybe.Absent => Sync.defer(false)
          result <- if reached then Sync.defer(true) else attempt(remaining - 1)
        yield result
    attempt(5)

  /** `awaitFrame` with its own bound, for checks that retry. */
  private def awaitFrameWithin(timeout: scala.concurrent.duration.Duration)(cache: FrameCache, id: SessionId)(
      holds: ScreenFrame => Boolean
  )(using Frame): Maybe[ScreenFrame] < Async =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    def loop(): Maybe[ScreenFrame] < Async =
      for
        frame <- cache.frame(id)
        matched = frame.exists(holds)
        next <- if matched || java.lang.System.nanoTime() > deadline then Sync.defer(frame) else Async.sleep(20.millis).andThen(loop())
      yield next
    loop()

  /** Waits for the client's frame for `id` to satisfy `holds`, or gives up at the deadline. */
  private def awaitFrame(cache: FrameCache, id: SessionId)(holds: ScreenFrame => Boolean)(using
      Frame
  ): Maybe[ScreenFrame] < Async =
    val deadline = java.lang.System.nanoTime() + waitTimeout.toNanos
    def loop(): Maybe[ScreenFrame] < Async =
      for
        frame <- cache.frame(id)
        matched = frame.exists(holds)
        next <- if matched || java.lang.System.nanoTime() > deadline then Sync.defer(frame) else Async.sleep(20.millis).andThen(loop())
      yield next
    loop()

  private def awaitEnds(cache: FrameCache, id: SessionId)(using Frame): Vector[Ended] < Async =
    val deadline = java.lang.System.nanoTime() + waitTimeout.toNanos
    def loop(): Vector[Ended] < Async =
      for
        ends <- cache.endsFor(id)
        next <- if ends.nonEmpty || java.lang.System.nanoTime() > deadline then Sync.defer(ends) else Async.sleep(20.millis).andThen(loop())
      yield next
    loop()

  private def cwd: String = java.lang.System.getProperty("user.dir")

  private def text(row: Row): String = row.cells.map(_.text).mkString

  private def lines(frame: ScreenFrame): Vector[String] = frame.rows.map(row => text(row).trim)
