package corvi

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** What the OS and the JVM say about this process's terminal shells and JediTermFX threads — the leak checks both probes make.
  */
private[corvi] object LiveProcesses:
  /** The shells this JVM still has as children — the orphan check `pgrep -f` would do, scoped to us so the user's own shells are not
    * counted.
    */
  def childShells: Seq[ProcessHandle] =
    val shell = Paths.get(Option(System.getenv("SHELL")).filter(_.nonEmpty).getOrElse("/bin/sh")).getFileName.toString
    ProcessHandle.current.descendants.iterator.asScala.toSeq.filter(_.info.commandLine.orElse("").contains(shell))

  /** Whether the OS still has that process. */
  def alive(pid: Long): Boolean =
    val handle = ProcessHandle.of(pid)
    handle.isPresent && handle.get.isAlive

  /** JediTermFX's own threads, by the names its code gives them: the emulator runs on `TerminalEmulator-<connector>` (ours is
    * `TerminalEmulator-Local`), and its executors are `JediTerm-*` and `JediTerm-fast-job-*`. The emulator thread is created with
    * `daemon = false`, so one left behind holds the JVM open as well as leaking a shell's worth of work.
    */
  def sessionThreads: Seq[String] = (emulatorThreads ++ executorThreads).sorted

  /** The one that does the work: `TerminalEmulator-Local` for a session of ours. */
  def emulatorThreads: Seq[String] = threadsNamed("TerminalEmulator")

  def executorThreads: Seq[String] = threadsNamed("JediTerm")

  private def threadsNamed(containing: String): Seq[String] =
    Thread.getAllStackTraces.keySet.asScala.toSeq.map(_.getName).filter(_.contains(containing)).sorted
