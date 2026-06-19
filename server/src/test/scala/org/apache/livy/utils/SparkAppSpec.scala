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
package org.apache.livy.utils

import java.io.{File, PrintWriter}
import java.nio.file.Files

import org.scalatest.{BeforeAndAfterAll, FunSpec}

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf}

class SparkAppSpec extends FunSpec with LivyBaseUnitTestSuite with BeforeAndAfterAll {

  private def k8sConf(sparkHome: Option[String] = None): LivyConf = {
    val conf = new LivyConf(false)
    conf.set(LivyConf.LIVY_SPARK_MASTER, "k8s://https://kubernetes.default.svc:443")
    sparkHome.foreach(conf.set(LivyConf.SPARK_HOME, _))
    conf
  }

  /** Create a throwaway SPARK_HOME with the given spark-defaults.conf contents (or none). */
  private def withSparkHome(defaultsContent: Option[String])(f: String => Unit): Unit = {
    val sparkHome = Files.createTempDirectory("livy-spark-home").toFile
    try {
      val confDir = new File(sparkHome, "conf")
      assert(confDir.mkdirs())
      defaultsContent.foreach { content =>
        val writer = new PrintWriter(new File(confDir, "spark-defaults.conf"))
        try writer.write(content) finally writer.close()
      }
      f(sparkHome.getAbsolutePath)
    } finally {
      deleteRecursively(sparkHome)
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
    }
    file.delete()
  }

  describe("SparkApp.getNamespace") {

    it("should return an empty namespace when not running on Kubernetes") {
      val conf = new LivyConf(false)
      conf.set(LivyConf.LIVY_SPARK_MASTER, "yarn")
      // A namespace in the conf must be ignored for non-Kubernetes masters.
      val sparkConf = Map(SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY -> "ignored")
      assert(SparkApp.getNamespace(sparkConf, conf) === "")
    }

    it("should prefer the namespace from the session Spark conf") {
      val sparkConf = Map(SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY -> "team-a")
      assert(SparkApp.getNamespace(sparkConf, k8sConf()) === "team-a")
    }

    it("should fall back to spark-defaults.conf when the conf has no namespace") {
      withSparkHome(Some(s"${SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY}  team-b\n")) { sparkHome =>
        assert(SparkApp.getNamespace(Map.empty, k8sConf(Some(sparkHome))) === "team-b")
      }
    }

    it("should fall back to the default namespace when spark-defaults.conf is absent") {
      withSparkHome(None) { sparkHome =>
        assert(SparkApp.getNamespace(Map.empty, k8sConf(Some(sparkHome))) ===
          SparkApp.DEFAULT_KUBERNETES_NAMESPACE)
      }
    }

    it("should fall back to the default namespace when spark-defaults.conf lacks the key") {
      withSparkHome(Some("spark.executor.memory 1g\n")) { sparkHome =>
        assert(SparkApp.getNamespace(Map.empty, k8sConf(Some(sparkHome))) ===
          SparkApp.DEFAULT_KUBERNETES_NAMESPACE)
      }
    }

    it("should fall back to the default namespace when SPARK_HOME is not set") {
      // No SPARK_HOME on the conf; getNamespace must not throw and must use the default.
      assert(SparkApp.getNamespace(Map.empty, k8sConf()) ===
        SparkApp.DEFAULT_KUBERNETES_NAMESPACE)
    }

    it("should treat an empty namespace in the conf as unset") {
      withSparkHome(Some(s"${SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY} team-c\n")) { sparkHome =>
        val sparkConf = Map(SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY -> "")
        assert(SparkApp.getNamespace(sparkConf, k8sConf(Some(sparkHome))) === "team-c")
      }
    }
  }
}
