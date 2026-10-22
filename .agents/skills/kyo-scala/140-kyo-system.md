# kyo-system

## Dependencies

- `io.getkyo::kyo-system` — `Command`, `CommandException`, `Path`, `FileSystem`, `System`.

`kyo-system` is not a dependency of this build yet (the daemon is spawned with `ProcessBuilder` so the UI can drop the
handle, and file access uses `java.nio`). The API below is the one you should reach for in Kyo code that runs a command
to completion or touches the filesystem as an effect.

---

## `Command`: a process as an immutable description

`Command(...)` builds a description; nothing runs until `spawn`, `text`, `textWithExitCode`, `waitFor` or `waitForSuccess`
is called. Arguments are passed to the OS as-is — there is no shell, so `|`, globbing and `$VAR` need
`Command("sh", "-c", "…")`.

```scala
import kyo.*

// Run and read stdout as UTF-8:
def versions: String < (Async & Abort[CommandException]) =
  Command("git", "log", "--oneline", "-5").text

// Working directory, environment, and an exit code as a value:
def build(project: kyo.Path): Process.ExitCode < (Async & Abort[CommandException]) =
  Command("npm", "run", "build")
    .cwd(project)
    .envAppend(Map("NODE_ENV" -> "production"))
    .inheritStderr
    .waitFor

// stdout *and* the exit code: non-zero is data, not an abort:
def grep(pattern: String, file: kyo.Path): (String, Process.ExitCode) < (Async & Abort[CommandException]) =
  Command("grep", pattern, file.toString).textWithExitCode

// Fail the effect on a non-zero exit:
def mustPass: Unit < (Async & Abort[CommandException | Process.ExitCode]) =
  Command("sh", "-c", "test -f build.mill").waitForSuccess
```

The builder methods you are likely to need: `cwd(Path)`, `envAppend`/`envRemove`/`envReplace`/`envClear`, `stdin(String)`,
`stdin(bytes)`, `stdin(Stream[Byte, Sync])`, `inheritStdin`, `inheritStdout`, `inheritStderr`, `inheritIO`,
`stdoutToFile(Path, append)`, `stderrToFile(Path, append)`, `redirectErrorStream(true)`, `andThen(command)` for a pipe,
and the accessors `args`, `env`, `workDir`.

Semantics worth knowing:

- `CommandException` covers **pre-launch** failures only: program not found, permission denied, bad cwd. A non-zero exit
  is a value (`Process.ExitCode`) — unless you ask for `waitForSuccess`, which aborts with the exit code.
- `text`/`textWithExitCode` drain stdout and stderr concurrently, so a chatty child cannot deadlock on a full pipe.
- The commands are scoped: `text` and `textWithExitCode` run inside a `Scope.run`, register the process and destroy it on
  exit. That is what makes them unsuitable for a daemon (below).

### When not to use it

A process that must **outlive its parent** cannot be bracketed. This UI spawns the host daemon and drops the handle:
nothing waits for it, nothing destroys it when the UI exits. Use `ProcessBuilder` for that, and pass the child its own
`-cp`, scheduler flags, redirected stdio (`/dev/null` stdin, a log file for stdout+stderr) and the environment it needs.

`Command` is the right tool for the opposite shape: a probe running `git`, `lsof` or a shell snippet and reading the
answer, or a build step.

## `Path`: the filesystem with capability effects

`kyo.Path` is an opaque type over a platform path. Build with `Path("a", "b")` or `Path / "a" / "b"`; the infix `/` is
available on the companion and on a `Path`.

```scala
import kyo.*

val socketDir: kyo.Path = Path("/tmp", "corvi")
val socket: kyo.Path = socketDir / "host.sock"

def tokenOf(path: kyo.Path): String < (Sync & Abort[FileSystemException]) =
  Path.runReadOnly(path.read)
```

Operations are grouped into capabilities:

- `PathRead` — `exists`, `isDirectory`, `isRegularFile`, `isSymbolicLink`, `realPath`, `read`, `readBytes`, `list`
  (optionally with a `Glob`), and the metadata accessors;
- `PathWrite` — `write`, `copy`, `move`, `remove`/`removeExisting`/`removeAll`, the atomic-create operations, and
  `tempDir` (which also wants a `Scope`). `PathWrite` extends `PathRead`.

A function that only reads declares `PathRead`; one that writes declares `PathWrite`. Run it at the boundary with
`Path.runReadOnly(program)` or `Path.run(program)`, which turn the capability into
`A < (Sync & Abort[FileSystemException] & S)`:

```scala
def readToken(dir: kyo.Path): String < (Sync & Abort[FileSystemException]) =
  Path.runReadOnly((dir / "token").read).map(_.trim)
```

The error channel is `FileSystemException`, with subtypes per group: `FileReadException`, `FileWriteException`,
`FileStructureException`, `FileLockException` (and their concrete cases for not-found, invalid path, access denied, …).

The payoff is testability: a test can run the same function over an in-memory or temporary filesystem instead of the real
one, and a reader cannot delete your files by accident because it never asks for `PathWrite`.

### What this project does instead, and why

`HostPaths` uses `java.nio.file.Files` directly for the socket directory, the token file and the owner marker, because:

- the token needs an **atomic create at 0600** with a specific failure mode (`CREATE_NEW`, loser re-reads) and a
  directory created at 0700, which is exactly the sort of ownership/ordering detail better kept in one small class;
- the daemon's paths must be resolvable from a plain `main` and from probes without running an effect;
- `java.nio` is the substrate either way, and there is no second implementation to test against yet.

If you extend path handling inside Kyo code (a new probe reading the host's log, say), prefer `kyo.Path`: the effect
signature is the documentation.

## `System` and environment

`kyo.System` exposes the environment, system properties and platform facts as context-bound effects:
`System.env[A](name)` and `System.property[A](name)` return `Maybe[A]` and use a `Parser` to decode the value (a parse
failure is in the effect row; a variable that is not set is `Absent`), while `System.lineSeparator`,
`System.userName`, `System.operatingSystem` and `System.architecture` need only `Sync`. `System.let(custom)(body)` swaps
the implementation for a test.

This project reads `CORVI_HOST_SOCKET`, `SHELL` and `user.dir` with `java.lang.System.getenv`/`getProperty` at plain
boundaries (process start, probe setup), which is fine because those values are fixed for the process's life. Reach for
`System.env` when the read happens *inside* an effect and you want it visible in the effect set — and note that a
missing variable is `Maybe.Absent`, not a failure.
