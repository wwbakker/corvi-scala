# Version API

## Dependencies

- `com.softwaremill.sttp.tapir::tapir-netty-server-sync` — HTTP server
- `mill.contrib.buildinfo.BuildInfo` — Mill contrib module that generates a
  Scala object with build metadata at compile time

---

## Build info generation

Mill's `buildinfo` contrib module generates a Scala object from the build. Pull
`mill-contrib-buildinfo` into the build file's meta-build, mix the trait into
the module, and list the members:

```scala
//| mvnDeps: ["com.lihaoyi::mill-contrib-buildinfo:$MILL_VERSION"]
package build

import mill.*, scalalib.*
import mill.contrib.buildinfo.BuildInfo

object app extends ScalaModule with BuildInfo:
  def buildInfoPackageName = "com.example.myapp.version"
  def buildInfoMembers = Seq(
    BuildInfo.Value("name", "myapp"),
    BuildInfo.Value("scalaVersion", scalaVersion()),
    BuildInfo.Value(
      "lastCommitHash",
      os.proc("git", "rev-parse", "HEAD").call(check = false).out.trim()
    )
  )
```

> **Note:** `$MILL_VERSION` in the build-file header is substituted with the
> Mill version from `.mill-version`; contrib modules are released in lockstep
> with Mill.

## The version endpoint

The endpoint is minimal — a GET that returns the commit hash:

```scala
class VersionApi extends ServerEndpoints:
  import VersionApi.*

  private val versionServerEndpoint: ServerEndpoint[Any, Identity] =
    versionEndpoint.handleSuccess { _ =>
      Version_OUT(BuildInfo.lastCommitHash)
    }

  override val endpoints = wireList

object VersionApi extends EndpointsForDocs:
  private val versionEndpoint = baseEndpoint.get
    .in("admin" / "version")
    .out(jsonBody[Version_OUT])

  override val endpointsForDocs = wireList[AnyEndpoint].map(_.tag("admin"))

  case class Version_OUT(buildSha: String) derives ConfiguredJsonValueCodec, Schema
```

