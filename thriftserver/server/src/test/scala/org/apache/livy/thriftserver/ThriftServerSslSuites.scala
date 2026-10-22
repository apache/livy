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

package org.apache.livy.thriftserver

import java.io.File
import java.nio.file.Paths

import scala.collection.JavaConverters._
import scala.io.Source

import org.apache.livy.LivyConf

/**
 * Exercises the TLS path of the Thrift server for both transports. This is the surface addressed by
 * CVE-2026-43869 (improper TLS host validation in libthrift's `TSSLTransportFactory`, fixed in
 * 0.23+): the binary transport builds its server socket via `TSSLTransportFactory` and the HTTP
 * transport serves over HTTPS. A successful JDBC query over `ssl=true` proves the server completed
 * a TLS handshake with the (self-signed) test certificate.
 *
 * The keystore is generated at runtime with the JDK's own `keytool` (no committed binary, no extra
 * dependency) and removed in `afterAll`.
 */
abstract class ThriftServerSslTest extends ThriftServerBaseTest {

  protected val keyStorePassword = "livypass"

  protected val keyStorePath: String = {
    val f = File.createTempFile("livy-thrift-test", ".jks")
    f.delete()
    f.deleteOnExit()
    f.getAbsolutePath
  }

  private def generateKeyStore(): Unit = {
    val keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString
    val cmd = Seq(
      keytool, "-genkeypair",
      "-alias", "livy-thrift-test",
      "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
      "-dname", "CN=localhost, OU=Livy, O=Apache, L=NA, ST=NA, C=NA",
      "-ext", "SAN=dns:localhost,ip:127.0.0.1",
      "-keystore", keyStorePath,
      "-storepass", keyStorePassword,
      "-keypass", keyStorePassword,
      "-storetype", "JKS")
    val process = new ProcessBuilder(cmd.asJava).redirectErrorStream(true).start()
    val output = Source.fromInputStream(process.getInputStream).mkString
    val exitCode = process.waitFor()
    assert(exitCode == 0, s"keytool failed to generate the test keystore ($exitCode): $output")
  }

  override def beforeAll(): Unit = {
    generateKeyStore()
    livyConf.set(LivyConf.THRIFT_USE_SSL, true)
    livyConf.set(LivyConf.SSL_KEYSTORE, keyStorePath)
    livyConf.set(LivyConf.SSL_KEYSTORE_PASSWORD, keyStorePassword)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try {
      super.afterAll()
    } finally {
      new File(keyStorePath).delete()
    }
  }

  // Inject the client-side TLS session variables ahead of the hive conf (`?`) section of the URI.
  override def jdbcUri(defaultDb: String, sessionConf: String*): String = {
    val base = super.jdbcUri(defaultDb, sessionConf: _*)
    val sslVars =
      s";ssl=true;sslTrustStore=$keyStorePath;trustStorePassword=$keyStorePassword"
    val queryIdx = base.indexOf('?')
    if (queryIdx >= 0) {
      base.substring(0, queryIdx) + sslVars + base.substring(queryIdx)
    } else {
      base + sslVars
    }
  }

  test("runs a query over a TLS connection") {
    withJdbcStatement { statement =>
      val rs = statement.executeQuery("select 1 as col")
      assert(rs.next())
      assert(rs.getInt(1) == 1)
      assert(!rs.next())
    }
  }
}

class BinarySslThriftServerSuite extends ThriftServerSslTest {
  override def mode: ServerMode.Value = ServerMode.binary
  override def port: Int = 20002
}

class HttpSslThriftServerSuite extends ThriftServerSslTest {
  override def mode: ServerMode.Value = ServerMode.http
  override def port: Int = 20003
}
