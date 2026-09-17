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
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.alias.CredentialProviderFactory
import org.scalatest.funspec.AnyFunSpec

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf}

class SparkAppSpec extends AnyFunSpec with LivyBaseUnitTestSuite {

  private val providerPathKey = "spark.hadoop.hadoop.security.credential.provider.path"
  private val truststorePasswordKey = "spark.hadoop.hive.metastore.truststore.password"

  private def k8sConf(sparkHome: Option[String] = None): LivyConf = {
    val conf = new LivyConf(false)
    conf.set(LivyConf.LIVY_SPARK_MASTER, "k8s://https://kubernetes.default.svc:443")
    sparkHome.foreach(conf.set(LivyConf.SPARK_HOME, _))
    conf
  }

  /** Create a throwaway SPARK_HOME with the given spark-defaults.conf contents (or none). */
  private def withSparkHome(defaultsContent: Option[String])(f: String => Unit): Unit = {
    // These tests assert resolution from $SPARK_HOME/conf; an ambient SPARK_CONF_DIR takes
    // precedence and would shadow the throwaway SPARK_HOME, so skip when it is set.
    assume(sys.env.get("SPARK_CONF_DIR").forall(_.isEmpty))
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

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      Files.walk(path).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
    }
  }

  private def withCredentialStore(secrets: Map[String, String])
      (f: LivyConf => Unit): Unit = {
    val jceksDir = Files.createTempDirectory("livy-cred-test")
    val jceksPath = jceksDir.resolve("credentials.jceks")
    val providerPath = s"jceks://file${jceksPath.toAbsolutePath}"

    val hadoopConf = new Configuration()
    hadoopConf.set("hadoop.security.credential.provider.path", providerPath)
    val provider = CredentialProviderFactory.getProviders(hadoopConf).get(0)
    secrets.foreach { case (alias, value) =>
      provider.createCredentialEntry(alias, value.toCharArray)
    }
    provider.flush()

    val livyConf = new LivyConf(false)
    livyConf.set(LivyConf.HADOOP_CREDENTIAL_PROVIDER_PATH, providerPath)
    try {
      f(livyConf)
    } finally {
      deleteRecursively(jceksDir)
    }
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
      // Namespace resolution reads SPARK_CONF_DIR then falls back to the SPARK_HOME env var,
      // so only assert the env-independent default when the ambient environment sets neither.
      assume(sys.env.get("SPARK_HOME").forall(_.isEmpty))
      assume(sys.env.get("SPARK_CONF_DIR").forall(_.isEmpty))
      assert(SparkApp.getNamespace(Map.empty, k8sConf()) ===
        SparkApp.DEFAULT_KUBERNETES_NAMESPACE)
    }

    it("should fall back to the default namespace when spark-defaults.conf is malformed") {
      // An invalid unicode escape makes java.util.Properties.load throw; getNamespace must
      // swallow it and fall back rather than aborting session creation.
      withSparkHome(Some("spark.driver.extraJavaOptions=-Dp=\\uZZZZ\n")) { sparkHome =>
        assert(SparkApp.getNamespace(Map.empty, k8sConf(Some(sparkHome))) ===
          SparkApp.DEFAULT_KUBERNETES_NAMESPACE)
      }
    }

    it("should treat an empty namespace in the conf as unset") {
      withSparkHome(Some(s"${SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY} team-c\n")) { sparkHome =>
        val sparkConf = Map(SparkApp.SPARK_KUBERNETES_NAMESPACE_KEY -> "")
        assert(SparkApp.getNamespace(sparkConf, k8sConf(Some(sparkHome))) === "team-c")
      }
    }
  }

  describe("SparkApp.prepareSparkConf") {
    it("should leave conf unchanged when no credential provider path is configured") {
      val livyConf = new LivyConf(false)
      livyConf.set(LivyConf.LIVY_SPARK_MASTER, "local")
      val inputConf = Map("spark.app.name" -> "test-app")

      val result = SparkApp.prepareSparkConf("livy-test-tag", livyConf, inputConf)

      assert(result === inputConf)
    }

    it("should resolve HMS SSL credentials locally and preserve the provider path") {
      withCredentialStore(Map("hive.metastore.truststore.password" -> "trust-secret")) { livyConf =>
        livyConf.set(LivyConf.LIVY_SPARK_MASTER, "local")
        val inputConf = Map(
          "spark.app.name" -> "test-app",
          providerPathKey -> "jceks://file/local-only-path")

        val result = SparkApp.prepareSparkConf("livy-test-tag", livyConf, inputConf)

        assert(result(truststorePasswordKey) === "trust-secret")
        assert(result(providerPathKey) === "jceks://file/local-only-path")  // preserved
        assert(result("spark.app.name") === "test-app")
      }
    }

    it("should leave conf unchanged when JCEKS has no HMS SSL aliases") {
      withCredentialStore(Map("livy.keystore.password" -> "livy-secret")) { livyConf =>
        livyConf.set(LivyConf.LIVY_SPARK_MASTER, "local")
        val inputConf = Map(
          "spark.app.name" -> "test-app",
          providerPathKey -> "jceks://file/user-supplied")

        val result = SparkApp.prepareSparkConf("livy-test-tag", livyConf, inputConf)

        assert(result === inputConf)
      }
    }

    it("should leave conf unchanged when credential provider path cannot be read") {
      val livyConf = new LivyConf(false)
      livyConf.set(LivyConf.LIVY_SPARK_MASTER, "local")
      livyConf.set(LivyConf.HADOOP_CREDENTIAL_PROVIDER_PATH, "jceks://file/does-not-exist.jceks")
      val inputConf = Map("spark.app.name" -> "test-app")

      val result = SparkApp.prepareSparkConf("livy-test-tag", livyConf, inputConf)

      assert(result === inputConf)
    }
  }
}
