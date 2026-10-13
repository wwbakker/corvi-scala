# New Project Setup

For an **HTTP project**, use
[adopt-tapir](https://adopt-tapir.softwaremill.com) as a reference for the
server wiring (**Scala 3** / **Ox stack** / **Netty**): it generates a
compiling, runnable project — entry point, server wiring, an example endpoint,
JSON, and a test — already on the direct-style stack. It emits an sbt build, so
read it to see what to write, then port the build to Mill with the skeleton
below. The chapters then explain each part and add what the skeleton omits
(error handling, auth, config, persistence).

For a **non-HTTP** app (CLI, worker, batch job), bootstrap the minimal Mill + Ox
skeleton below — a compiling, runnable entry point with nothing else. Either way,
add features (HTTP, JSON, persistence, config) by following the chapters listed at
the end.

## Dependencies

- `com.softwaremill.ox::core` — direct-style concurrency (`OxApp`,
  `supervised`, `fork`, `useInScope`)
- `ch.qos.logback:logback-classic` — logging backend
- `mill.scalalib.scalafmt.ScalafmtModule` — formatting (built into Mill, no
  plugin needed)

---

## Directory layout

```
myapp/
  build.mill
  mill                  # launcher script (./mill.bat on Windows)
  .mill-version
  app/
    src/com/example/
      Main.scala
    resources/
      logback.xml
  .gitignore
  .scalafmt.conf
```

The package path under `app/src/` mirrors the package of your code. A module
named `app` takes its sources from `app/src/` and its resources from
`app/resources/`.

## build.mill

```scala
package build

import mill.*, scalalib.*

object app extends ScalaModule:
  def scalaVersion = "<latest>"
  def mainClass = Some("com.example.Main")
  def scalacOptions = Seq(
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wnonunit-statement"
  )
  def mvnDeps = Seq(
    mvn"com.softwaremill.ox::core:<latest>",
    mvn"ch.qos.logback:logback-classic:<latest>"
  )
```

A Mill `mvn` coordinate uses `::` for a Scala artifact (`groupId::artifactId`)
and `:` for a Java artifact (`groupId:artifactId`). Write `mvnDeps` (not sbt's
`libraryDependencies`) and put the version at the end.

> **Required:** `scalacOptions` must include `-Wunused:all`,
> `-Wvalue-discard`, and `-Wnonunit-statement`. These catch silently-ignored
> values and unused bindings — common bug sources in direct-style code where
> effects aren't tracked by types. Fix warnings at source; only use `@nowarn`
> for generated code or unfixable third-party issues.

> **Note:** `<latest>` (here and below) is a placeholder — resolve the current
> release of each library and tool rather than copying a pinned number, which
> goes stale.

## Mill bootstrap

Download the Mill launcher into the project root and pin the version so every
contributor and CI run uses the same tool:

```bash
curl -sL -o mill https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/<version>/mill-dist-<version>-mill.sh
chmod +x mill
echo <version> > .mill-version
./mill --version
```

The launcher downloads Mill's own runner JVM on first use. Run tasks with
`./mill`, for example `./mill app.compile` and `./mill app.run`.

## .scalafmt.conf

```
version = <latest>
maxColumn = 140
runner.dialect = scala3
```

`runner.dialect = scala3` is mandatory for Scala 3 / braceless syntax to
format correctly.

Format with Mill's built-in Scalafmt module:

```bash
./mill mill.scalalib.scalafmt/                    # reformat every module
./mill mill.scalalib.scalafmt/checkFormatAll      # verify without writing
```

Modules that mix in `mill.scalalib.scalafmt.ScalafmtModule` also expose
`reformat` and `checkFormat` per module.

## .gitignore

```
# Mill
out/
.bsp/
.bloop/
.metals/
.scala-build/
# IDE / OS
.idea/
.vscode/
.DS_Store
```

## Main.scala

```scala
package com.example

import ox.*

object Main extends OxApp.Simple:
  def run(using Ox): Unit =
    println("myapp started")
```

`OxApp.Simple` opens the root `supervised` scope. When `run` returns the
scope ends, cleaning up any resources registered with `useInScope` and
joining all forks. For a long-running application, end `run` with `never`
instead — see [Background Processes](110-background-processes.md).

## app/resources/logback.xml

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="DEBUG">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

## Extending the skeleton

Add features by following the relevant chapter:

- Scala visibility, package, file, and module rules — [Code Organization and
  Visibility](160-code-organization.md).
- Resource lifecycle — [Resource Management](100-resource-management.md).
- `application.conf` + `Config.read` — [Type-Safe Configuration](120-type-safe-configuration.md).
- Wiring multiple services — [Compile-Time Dependency Injection](130-compile-time-dependency-injection.md).
- HTTP endpoints with synchronous Tapir — [HTTP Server Configuration](310-http-server-configuration.md).
- In-process endpoint testing — [Testing HTTP Endpoints](500-testing-http-endpoints.md).
- PostgreSQL + Flyway — [SQL Persistence](400-sql-persistence.md).
- Observability via OpenTelemetry — [OpenTelemetry Observability](510-opentelemetry-observability.md).
