# Compile-Time OpenAPI Generation

## Dependencies

- `com.softwaremill.sttp.tapir::tapir-openapi-docs` — OpenAPI model
  generation from endpoint descriptions
- `com.softwaremill.sttp.apispec::openapi-circe-yaml` — serialise OpenAPI
  model to YAML

---

## Collecting endpoints for docs

Each API module's companion object extends `EndpointsForDocs` (see
[Compile-Time Dependency
Injection](130-compile-time-dependency-injection.md)) and tags its endpoints:

```scala
object UserApi extends EndpointsForDocs:
  override val endpointsForDocs = wireList[AnyEndpoint].map(_.tag("user"))
```

All endpoint lists are merged in a single place:

```scala
object Dependencies:
  val endpointsForDocs: List[AnyEndpoint] =
    List(UserApi, PasswordResetApi, VersionApi).flatMap(_.endpointsForDocs)
```

## The generator

A standalone `@main` function converts the endpoint descriptions to YAML and
writes the file:

```scala
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import java.nio.file.*

object OpenAPIDescription:
  val Title = "My API"
  val Version = "1.0"

@main def writeOpenAPIDescription(path: String): Unit =
  val yaml = OpenAPIDocsInterpreter()
    .toOpenAPI(Dependencies.endpointsForDocs, OpenAPIDescription.Title, OpenAPIDescription.Version)
    .toYaml
  Files.writeString(Paths.get(path), yaml)
```

## Wiring as a Mill task

In `build.mill`, define a command that runs the generator. Mill's `runMain` is
the equivalent of sbt's `Compile / runMain`:

```scala
object app extends ScalaModule:
  /** Generate OpenAPI YAML from the endpoint descriptions. */
  def generateOpenAPIDescription(path: String = "openapi.yaml") =
    runMain("com.softwaremill.myapp.writeOpenAPIDescription", path)
```

Run it with `./mill app.generateOpenAPIDescription`, or pass an explicit
destination with
`./mill app.generateOpenAPIDescription --path target/openapi.yaml`.

