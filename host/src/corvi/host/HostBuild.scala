package corvi.host

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** A fingerprint of the host code, so a UI can tell whether the daemon it found was built from the same sources as itself.
  *
  * The daemon is deliberately long-lived. That is what lets a UI restart without killing the shells, but it also means a UI started after a
  * host change would otherwise keep talking to the old process. The fingerprint covers the three modules the host is built from; the
  * classpath is the same one `HostConnection` hands to the spawned daemon, so both processes compute the same value.
  */
private[corvi] object HostBuild:

  private val prefixes: Seq[String] = Seq("corvi/host/", "corvi/protocol/", "corvi/contract/")

  /** Stable for one classpath, and different as soon as any of those classes changes. */
  val current: String = fingerprint()

  private def fingerprint(): String =
    val classpath = java.lang.System.getProperty("java.class.path", "")
    val digest = MessageDigest.getInstance("SHA-256")
    entries(classpath).sortBy(_._1).foreach { (name, bytes) =>
      digest.update(0.toByte)
      digest.update(name.getBytes(StandardCharsets.UTF_8))
      digest.update(0.toByte)
      digest.update(bytes)
    }
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  /** Every host, protocol or contract class on the classpath, read from directories or jars. */
  private def entries(classpath: String): Vector[(String, Array[Byte])] =
    classpath
      .split(File.pathSeparator)
      .toVector
      .filter(_.nonEmpty)
      .flatMap(entry => from(Paths.get(entry)))

  private def from(entry: Path): Vector[(String, Array[Byte])] =
    if Files.isDirectory(entry) then prefixes.flatMap(prefix => directoryClasses(entry.resolve(prefix), prefix)).toVector
    else if Files.isRegularFile(entry) then jarClasses(entry)
    else Vector.empty

  private def directoryClasses(root: Path, prefix: String): Vector[(String, Array[Byte])] =
    if !Files.isDirectory(root) then Vector.empty
    else
      val walk = Files.walk(root)
      try
        walk.iterator.asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".class"))
          .map(path => classpathName(prefix, root.relativize(path)) -> Files.readAllBytes(path))
          .toVector
      finally walk.close()

  private def jarClasses(entry: Path): Vector[(String, Array[Byte])] =
    try
      val jar = new JarFile(entry.toFile)
      try
        jar
          .entries()
          .asScala
          .filter(entry => prefixes.exists(entry.getName.startsWith) && entry.getName.endsWith(".class"))
          .map(entry => entry.getName -> jar.getInputStream(entry).readAllBytes())
          .toVector
      finally jar.close()
    catch case _: IOException => Vector.empty

  private def classpathName(prefix: String, relative: Path): String =
    prefix + relative.iterator().asScala.map(_.toString).mkString("/")
