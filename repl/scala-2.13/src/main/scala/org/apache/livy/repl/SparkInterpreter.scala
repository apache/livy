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
 * Spark 4.x / Scala 2.13 implementation of the Spark interpreter
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
    // and feed them to the Scala compiler through `-classpath` at construction
    // time.
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

    // Spark 4's SparkILoop takes (BufferedReader, PrintWriter)
    sparkILoop = new SparkILoop(null, new PrintWriter(outputStream, true))
    sparkILoop.createInterpreter(settings)

    restoreContextClassLoader {
      postStart()
    }
  }

  /**
   * Return Spark's `MutableURLClassLoader` URLs joined with
   * `File.pathSeparator`, ready to pass to Scala compiler `-classpath`.
   * Skips livy-* and scala-reflect jars; returns "" if no such classloader
   * is found on the context classloader chain.
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
        // Only real files -- getURLs() may include stale entries.
        .filter { u => u.getProtocol == "file" && new File(u.getPath).isFile }
        // Drop livy-* (would collide with the shaded repl classpath) and
        // wrong scala-reflect version jars that some Spark packages depend on.
        .filterNot { u =>
          val name = Paths.get(u.toURI).getFileName.toString
          name.startsWith("livy-") || name.contains("org.scala-lang_scala-reflect")
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
    // Guard against the `_runtimeClassLoader == null` NPE inside
    // `addUrlsToClassPath` (`urls.foreach(_runtimeClassLoader.addURL)`) on a
    // fresh session that hasn't run any code yet. Calling `.classLoader` on
    // the `Repl` interface triggers `ensureClassLoader()` -> `makeClassLoader()`
    // in IMain, which initialises BOTH `_classLoader` (the
    // AbstractFileClassLoader returned to us) AND `_runtimeClassLoader` (the
    // URLClassLoader used by `addUrlsToClassPath`). We discard the returned
    // value; we only need the side effect on `_runtimeClassLoader`.
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
    completer.complete(code, cursor, filter = false).candidates.map(_.name).toArray
  }

  override protected def valueOfTerm(name: String): Option[Any] = {
    // IMain#valueOfTerm always returns None; read `$result` off the last request instead.
    Option(sparkILoop.intp.asInstanceOf[IMain].lastRequest.lineRep.call("$result"))
  }

  override protected def bind(name: String,
      tpe: String,
      value: Object,
      modifier: List[String]): Unit = {
    // 2.12's `SparkILoop.beQuietDuring` moved to `intp.beQuietDuring` in 2.13;
    // call the underlying `reporter.withoutPrintingResults` directly.
    sparkILoop.intp.reporter.withoutPrintingResults {
      sparkILoop.intp.bind(name, tpe, value, modifier)
    }
  }
}
