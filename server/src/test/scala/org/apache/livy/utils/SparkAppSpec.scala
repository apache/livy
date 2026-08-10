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

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.alias.CredentialProviderFactory
import org.scalatest.funspec.AnyFunSpec

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf}

class SparkAppSpec extends AnyFunSpec with LivyBaseUnitTestSuite {

  private val providerPathKey = "spark.hadoop.hadoop.security.credential.provider.path"
  private val truststorePasswordKey = "spark.hadoop.hive.metastore.truststore.password"

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
