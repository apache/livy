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

import java.nio.ByteBuffer
import java.sql.{DriverManager, SQLException}

import scala.util.control.NonFatal

import org.apache.hive.service.rpc.thrift.{TCLIService, TCloseSessionReq, TDownloadDataReq}
import org.apache.hive.service.rpc.thrift.{TOpenSessionReq, TProtocolVersion, TSessionHandle}
import org.apache.hive.service.rpc.thrift.{TStatusCode, TUploadDataReq}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TSocket

import org.apache.livy.LivyConf

/**
 * Verifies that the UploadData / DownloadData RPCs (introduced into TCLIService.Iface by the
 * Hive 4 service-rpc upgrade) return a non-success status rather than throwing or crashing the
 * server. Livy does not implement these RPCs, so the only contract we guarantee is a well-formed
 * error [[org.apache.hive.service.rpc.thrift.TStatus]].
 *
 * The suite uses NOSASL binary transport so a raw Thrift client can talk to the server directly,
 * since the Hive JDBC driver does not expose these two RPCs.
 */
class ThriftServerUnsupportedRpcSuite extends ThriftServerBaseTest {
  override def mode: ServerMode.Value = ServerMode.binary
  override def port: Int = 20004

  override def beforeAll(): Unit = {
    livyConf.set(LivyConf.THRIFT_AUTHENTICATION, "NOSASL")
    super.beforeAll()
  }

  private def latestProtocol: TProtocolVersion = {
    val values = TProtocolVersion.values()
    values(values.length - 1)
  }

  private def withRawCliClient(f: (TCLIService.Iface, TSessionHandle) => Unit): Unit = {
    val socket = new TSocket("localhost", port)
    socket.open()
    try {
      val client = new TCLIService.Client(new TBinaryProtocol(socket))
      val openReq = new TOpenSessionReq()
      openReq.setClient_protocol(latestProtocol)
      openReq.setUsername(System.getProperty("user.name"))
      val openResp = client.OpenSession(openReq)
      assert(openResp.getStatus.getStatusCode == TStatusCode.SUCCESS_STATUS)
      val sessionHandle = openResp.getSessionHandle
      try {
        f(client, sessionHandle)
      } finally {
        // Best-effort cleanup: the raw socket may already be torn down, and a failure here must
        // not mask an assertion failure from the test body above.
        try {
          client.CloseSession(new TCloseSessionReq(sessionHandle))
        } catch {
          case NonFatal(_) => // ignore
        }
      }
    } finally {
      try {
        socket.close()
      } catch {
        case NonFatal(_) => // ignore
      }
    }
  }

  test("UploadData returns an error status (unsupported)") {
    withRawCliClient { (client, sessionHandle) =>
      val resp = client.UploadData(
        new TUploadDataReq(sessionHandle, ByteBuffer.wrap(Array.empty[Byte])))
      assert(resp.getStatus.getStatusCode != TStatusCode.SUCCESS_STATUS)
    }
  }

  test("DownloadData returns an error status (unsupported)") {
    withRawCliClient { (client, sessionHandle) =>
      val resp = client.DownloadData(new TDownloadDataReq(sessionHandle))
      assert(resp.getStatus.getStatusCode != TStatusCode.SUCCESS_STATUS)
    }
  }
}

/**
 * Verifies that `livy.server.thrift.max.message.size` is honoured for the binary transport: a
 * request whose string payload exceeds the configured limit is rejected, while a normal request
 * continues to succeed.
 */
class ThriftServerMaxMessageSizeSuite extends ThriftServerBaseTest {
  override def mode: ServerMode.Value = ServerMode.binary
  override def port: Int = 20005

  private val maxMessageSize = 4096

  override def beforeAll(): Unit = {
    livyConf.set(LivyConf.THRIFT_MAX_MESSAGE_SIZE, maxMessageSize)
    super.beforeAll()
  }

  test("a request within the max message size succeeds") {
    withJdbcStatement { statement =>
      val rs = statement.executeQuery("select 1")
      assert(rs.next())
      rs.close()
    }
  }

  test("a request exceeding the max message size is rejected") {
    // Managed manually (not via withJdbcStatement) because the oversized request tears down the
    // connection server-side, so the subsequent connection.close() throws a cleanup SQLException
    // that must be swallowed rather than failing the test.
    val user = System.getProperty("user.name")
    val connection = DriverManager.getConnection(jdbcUri("default"), user, "")
    try {
      val statement = connection.createStatement()
      val oversized = "x" * (maxMessageSize * 2)
      intercept[SQLException] {
        statement.executeQuery(s"select '$oversized'")
      }
    } finally {
      try {
        connection.close()
      } catch {
        case NonFatal(_) => // ignore cleanup failure on a broken connection
      }
    }
  }
}
