/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.repl

import java.io.{File, PrintWriter}
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Paths}

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{IMain, Repl}
import scala.tools.nsc.interpreter.Results.Result
import scala.tools.nsc.interpreter.shell.{Completion, NoCompletion, ReplCompletion}

import org.apache.spark.SparkConf
import org.apache.spark.repl.SparkILoop

/**
 * Spark 4.x / Scala 2.13 implementation of the Spark interpreter.
 *
 * The Spark 4 SparkILoop is built on top of the rewritten
 * `scala.tools.nsc.interpreter.shell.ILoop` API. It exposes an `intp`
 * (Repl / IMain) through which most bind/interpret/quiet operations flow;
 * the old `beQuietDuring` / `lastRequest` accessors that used to live on
 * SparkILoop itself in Scala 2.12 now live on `intp`.
 *
 * This class is not thread safe.
 */
class SparkInterpreter(protected override val conf: SparkConf) extends AbstractSparkInterpreter {

  private var sparkILoop: SparkILoop = _

  override def start(): Unit = {
    require(sparkILoop == null)

    val rootDir = conf.get("spark.repl.classdir", System.getProperty("java.io.tmpdir"))
    val outputDir = Files.createTempDirectory(Paths.get(rootDir), "spark").toFile
    outputDir.deleteOnExit()
    conf.set("spark.repl.class.outputDir", outputDir.getAbsolutePath)

    // Collect Spark's user JARs (from `spark.jars.packages`, `--jars`, etc.)
    // by walking up the context classloader chain to Spark's
    // MutableURLClassLoader. In Scala 2.13 we MUST feed these JARs to the
    // Scala compiler through `-classpath` at construction time -- the
    // post-init `IMain.addUrlsToClassPath` path that worked in Scala 2.12 no
    // longer registers URLs with the compiler's `platform.classPath` in a
    // way `import ...` can resolve, so `import org.codehaus.plexus.util._`
    // fails with `object plexus is not a member of package org.codehaus`.
    // This matches what Spark 4's own `spark-shell` (`repl/.../Main.scala`)
    // does: it passes user JARs via `-classpath` to `new Settings()`.
    val userJarsClasspath = collectUserJarsClasspath()

    val settings = new Settings()
    val baseArgs = List(
      "-Yrepl-class-based",
      "-Yrepl-outdir", s"${outputDir.getAbsolutePath}")
    val cpArgs = if (userJarsClasspath.nonEmpty) {
      List("-classpath", userJarsClasspath)
    } else {
      Nil
    }
    settings.processArguments(baseArgs ++ cpArgs, true)
    settings.usejavacp.value = true
    settings.embeddedDefaults(Thread.currentThread().getContextClassLoader())

    // Spark 4's SparkILoop takes (BufferedReader, PrintWriter). A null reader
    // is fine -- we never drive input through the loop; we only use its intp.
    sparkILoop = new SparkILoop(null, new PrintWriter(outputStream, true))

    // Scala 2.13's shell.ILoop constructs the interpreter via
    // `createInterpreter(settings)`. There is no post-construction `settings=`
    // setter -- settings are passed in here.
    sparkILoop.createInterpreter(settings)

    restoreContextClassLoader {
      postStart()
    }
  }

  /**
   * Walk the context classloader chain to find Spark's
   * `org.apache.spark.util.MutableURLClassLoader` and return its URLs joined
   * with the platform path separator, ready to pass to Scala compiler
   * `-classpath`. Filters out livy-* jars (they would collide with the shaded
   * repl classpath) and known-bad scala-reflect artifacts. Returns an empty
   * string if the classloader chain has no MutableURLClassLoader.
   */
  private def collectUserJarsClasspath(): String = {
    var classLoader = Thread.currentThread().getContextClassLoader
    while (classLoader != null &&
      classLoader.getClass.getCanonicalName !=
        "org.apache.spark.util.MutableURLClassLoader") {
      classLoader = classLoader.getParent
    }
    if (classLoader == null) {
      warn("Could not locate Spark's MutableURLClassLoader on the context " +
        "classloader chain; user JARs from `spark.jars.packages` may not " +
        "be visible to `import ...` inside the Scala interpreter.")
      ""
    } else {
      val extraJarPath = classLoader.asInstanceOf[URLClassLoader].getURLs()
        // Check if the file exists. Otherwise an exception will be thrown.
        .filter { u => u.getProtocol == "file" && new File(u.getPath).isFile }
        // Livy rsc and repl are also in the extra jars list. Filter them out.
        .filterNot { u => Paths.get(u.toURI).getFileName.toString.startsWith("livy-") }
        // Some bad spark packages depend on the wrong version of scala-reflect.
        // Blacklist it.
        .filterNot { u =>
          Paths.get(u.toURI).getFileName.toString.contains("org.scala-lang_scala-reflect")
        }
      extraJarPath.foreach { p => debug(s"Adding $p to Scala interpreter's class path...") }
      extraJarPath.map { u => new File(u.toURI).getAbsolutePath }
        .mkString(File.pathSeparator)
    }
  }

  override def close(): Unit = synchronized {
    super.close()

    if (sparkILoop != null) {
      sparkILoop.closeInterpreter()
      sparkILoop = null
    }
  }

  override def addJar(jar: String): Unit = {
    // Guard against the same `_runtimeClassLoader == null` NPE described in
    // `start()`, in case `addJar` is called before the interpreter has ever
    // touched its classloader (e.g. on a fresh session). `classLoader()` on
    // the `Repl` interface triggers `ensureClassLoader()` internally.
    sparkILoop.intp.classLoader
    sparkILoop.intp.addUrlsToClassPath(new URL(jar))
  }

  override protected def isStarted(): Boolean = {
    sparkILoop != null
  }

  override protected def interpret(code: String): Result = {
    sparkILoop.intp.interpret(code)
  }

  override protected def completeCandidates(code: String, cursor: Int) : Array[String] = {
    // Scala 2.13 replaced `PresentationCompilerCompleter` with
    // `shell.ReplCompletion`, which takes a `Repl` (the interface `IMain`
    // now implements). Instantiate it directly rather than by reflection.
    val completer: Completion =
      try new ReplCompletion(sparkILoop.intp.asInstanceOf[Repl])
      catch { case _: Throwable => NoCompletion }
    // CompletionCandidate exposes `name` in 2.13 (the older `defString` field
    // no longer exists on the case class).
    completer.complete(code, cursor, filter = false).candidates.map(_.name).toArray
  }

  override protected def valueOfTerm(name: String): Option[Any] = {
    // IMain#valueOfTerm will always return None, so reach into the last
    // request's line-representation directly.
    Option(sparkILoop.intp.asInstanceOf[IMain].lastRequest.lineRep.call("$result"))
  }

  override protected def bind(name: String,
      tpe: String,
      value: Object,
      modifier: List[String]): Unit = {
    // 2.13's shell.ILoop dropped `beQuietDuring`; suppress result printing
    // by calling through the reporter directly instead.
    sparkILoop.intp.reporter.withoutPrintingResults {
      sparkILoop.intp.bind(name, tpe, value, modifier)
    }
  }
}
