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

package org.apache.livy.server.recovery

import scala.collection.JavaConverters._

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api._
import org.apache.curator.framework.listen.Listenable
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.data.Stat
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf}

class ZooKeeperStateStoreSpec extends AnyFunSpec with LivyBaseUnitTestSuite {
  describe("ZooKeeperStateStore") {
    case class TestFixture(stateStore: ZooKeeperStateStore, curatorClient: CuratorFramework)
    val conf = new LivyConf()
    conf.set(LivyConf.RECOVERY_STATE_STORE, "zookeeper")
    conf.set(LivyConf.RECOVERY_STATE_STORE_URL, "host")
    val key = "key"
    val prefixedKey = s"/livy/$key"

    def mockCurator(): CuratorFramework = {
      val cc = mock[CuratorFramework]
      when(cc.getUnhandledErrorListenable())
        .thenReturn(mock[Listenable[UnhandledErrorListener]])
      when(cc.getConnectionStateListenable())
        .thenReturn(mock[Listenable[ConnectionStateListener]])
      cc
    }

    def withMock[R](testBody: TestFixture => R): R = {
      val curatorClient = mockCurator()
      val zkManager = new ZooKeeperManager(conf, Some(curatorClient))
      zkManager.start()
      val stateStore = new ZooKeeperStateStore(conf, zkManager)
      testBody(TestFixture(stateStore, curatorClient))
    }

    def mockExistsBuilder(curatorClient: CuratorFramework, exists: Boolean): Unit = {
      val existsBuilder = mock[ExistsBuilder]
      when(curatorClient.checkExists()).thenReturn(existsBuilder)
      if (exists) {
        when(existsBuilder.forPath(prefixedKey)).thenReturn(mock[Stat])
      }
    }

    it("should throw on bad config") {
      withMock { f =>
        val conf = new LivyConf()
        intercept[IllegalArgumentException] { new ZooKeeperManager(conf) }

        conf.set(LivyConf.RECOVERY_STATE_STORE_URL, "host")
        conf.set(LivyConf.ZK_RETRY_POLICY, "bad")
        intercept[IllegalArgumentException] { new ZooKeeperManager(conf) }
      }
    }

    it("set should use curatorClient") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, true)

        val setDataBuilder = mock[SetDataBuilder]
        when(f.curatorClient.setData()).thenReturn(setDataBuilder)

        f.stateStore.set("key", 1.asInstanceOf[Object])

        verify(f.curatorClient).start()
        verify(setDataBuilder).forPath(prefixedKey, Array[Byte](49))
      }
    }

    it("set should create parents if they don't exist") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, false)

        val createBuilder = mock[CreateBuilder]
        when(f.curatorClient.create()).thenReturn(createBuilder)
        val p = mock[ProtectACLCreateModeStatPathAndBytesable[String]]
        when(createBuilder.creatingParentsIfNeeded()).thenReturn(p)

        f.stateStore.set("key", 1.asInstanceOf[Object])

        verify(f.curatorClient).start()
        verify(p).forPath(prefixedKey, Array[Byte](49))
      }
    }

    it("get should retrieve retry policy configs") {
      conf.set(LivyConf.ZK_RETRY_POLICY, "11,77")
        withMock { f =>
        mockExistsBuilder(f.curatorClient, true)

        f.stateStore.getZooKeeperManager().retryPolicy should not be null
        f.stateStore.getZooKeeperManager().retryPolicy.getN shouldBe 11
      }
    }

    it("get should retrieve data from curatorClient") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, true)

        val getDataBuilder = mock[GetDataBuilder]
        when(f.curatorClient.getData()).thenReturn(getDataBuilder)
        when(getDataBuilder.forPath(prefixedKey)).thenReturn(Array[Byte](50))

        val v = f.stateStore.get[Int]("key")

        verify(f.curatorClient).start()
        v shouldBe Some(2)
      }
    }

    it("get should return None if key doesn't exist") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, false)

        val v = f.stateStore.get[Int]("key")

        verify(f.curatorClient).start()
        v shouldBe None
      }
    }

    it("getChildren should use curatorClient") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, true)

        val getChildrenBuilder = mock[GetChildrenBuilder]
        when(f.curatorClient.getChildren()).thenReturn(getChildrenBuilder)
        val children = List("abc", "def")
        when(getChildrenBuilder.forPath(prefixedKey)).thenReturn(children.asJava)

        val c = f.stateStore.getChildren("key")

        verify(f.curatorClient).start()
        c shouldBe children
      }
    }

    it("getChildren should return empty list if key doesn't exist") {
      withMock { f =>
        mockExistsBuilder(f.curatorClient, false)

        val c = f.stateStore.getChildren("key")

        verify(f.curatorClient).start()
        c shouldBe empty
      }
    }

    it("remove should use curatorClient") {
      withMock { f =>
        val deleteBuilder = mock[DeleteBuilder]
        when(f.curatorClient.delete()).thenReturn(deleteBuilder)
        val g = mock[ChildrenDeletable]
        when(deleteBuilder.guaranteed()).thenReturn(g)

        f.stateStore.remove(key)

        verify(g).forPath(prefixedKey)
      }
    }

    it("should set SASL system properties when ZK SASL is enabled") {
      val saslConf = new LivyConf()
      saslConf.set(LivyConf.RECOVERY_STATE_STORE_URL, "host")
      saslConf.set(LivyConf.ZK_SASL_ENABLED, true)

      val zkManager = new ZooKeeperManager(saslConf, None)
      zkManager.stop()

      System.getProperty("zookeeper.sasl.client") shouldBe "true"
      System.getProperty("zookeeper.sasl.clientconfig") shouldBe "Client"
    }

    it("should register a ConnectionStateListener that handles all connection states") {
      val curatorClient = mockCurator()
      val listenable = mock[Listenable[ConnectionStateListener]]
      when(curatorClient.getConnectionStateListenable()).thenReturn(listenable)

      new ZooKeeperManager(conf, Some(curatorClient))

      val captor = ArgumentCaptor.forClass(classOf[ConnectionStateListener])
      verify(listenable).addListener(captor.capture())
      ConnectionState.values.foreach { state =>
        captor.getValue.stateChanged(curatorClient, state)
      }
    }

    it("should not set SASL system properties when ZK SASL is disabled") {
      System.clearProperty("zookeeper.sasl.client")
      System.clearProperty("zookeeper.sasl.clientconfig")

      val noSaslConf = new LivyConf()
      noSaslConf.set(LivyConf.RECOVERY_STATE_STORE_URL, "host")

      val zkManager = new ZooKeeperManager(noSaslConf, None)
      zkManager.stop()

      System.getProperty("zookeeper.sasl.client") shouldBe null
      System.getProperty("zookeeper.sasl.clientconfig") shouldBe null
    }

    describe("SSL config") {
      case class SslTestFixture(zkManager: ZooKeeperManager, curatorClient: CuratorFramework)

      def makeSslConf(): LivyConf = {
        val c = new LivyConf()
        c.set(LivyConf.RECOVERY_STATE_STORE_URL, "/tmp/livy")
        c.set(LivyConf.SSL_KEYSTORE, "/tmp/keystore.jks")
        c.set(LivyConf.SSL_KEYSTORE_PASSWORD, "keystorePass")
        c.set(LivyConf.SSL_KEY_PASSWORD, "keyPass")
        c.set(LivyConf.SSL_KEYSTORE_TYPE, "JKS")
        c.set(LivyConf.LIVY_ZK_KEYSTORE_PASS, "keystorePass")
        c.set(LivyConf.LIVY_ZK_TRUSTSTORE_FILE, "/tmp/truststore.jks")
        c.set(LivyConf.LIVY_ZK_TRUSTSTORE_PASS, "truststorePass")
        c
      }

      def withSslMock[R](sslConf: LivyConf)(testBody: SslTestFixture => R): R = {
        val curatorClient = mockCurator()
        val zkManager = new ZooKeeperManager(sslConf, Some(curatorClient))
        zkManager.start()
        testBody(SslTestFixture(zkManager, curatorClient))
      }

      it("createZKClientConfig should set secure flag and socket class") {
        withSslMock(makeSslConf()) { f =>
          verify(f.curatorClient).start()
          val zkConfig = f.zkManager.createZKClientConfig
          zkConfig.getProperty("zookeeper.client.secure") shouldBe "true"
          zkConfig.getProperty("zookeeper.clientCnxnSocket") shouldBe
            "org.apache.zookeeper.ClientCnxnSocketNetty"
        }
      }

      it("createZKClientConfig should set keystore location, password and type from LivyConf") {
        withSslMock(makeSslConf()) { f =>
          verify(f.curatorClient).start()
          val zkConfig = f.zkManager.createZKClientConfig
          zkConfig.getProperty("zookeeper.ssl.keyStore.location") shouldBe "/tmp/keystore.jks"
          zkConfig.getProperty("zookeeper.ssl.keyStore.password") shouldBe "keystorePass"
          zkConfig.getProperty("zookeeper.ssl.keyStore.type") shouldBe "JKS"
        }
      }

      it("createZKClientConfig should set truststore location, password and type from LivyConf") {
        withSslMock(makeSslConf()) { f =>
          verify(f.curatorClient).start()
          val zkConfig = f.zkManager.createZKClientConfig
          zkConfig.getProperty("zookeeper.ssl.trustStore.location") shouldBe
            "/tmp/truststore.jks"
          zkConfig.getProperty("zookeeper.ssl.trustStore.password") shouldBe "truststorePass"
          // trustStore.type reuses SSL_KEYSTORE_TYPE
          zkConfig.getProperty("zookeeper.ssl.trustStore.type") shouldBe "JKS"
        }
      }

      it("should build successfully when LIVY_ZK_CLIENT_SECURE is enabled") {
        val sslConf = makeSslConf()
        sslConf.set(LivyConf.LIVY_ZK_CLIENT_SECURE, true)
        noException should be thrownBy {
          val zkManager = new ZooKeeperManager(sslConf, Some(mockCurator()))
          zkManager.start()
          zkManager.stop()
        }
      }

      it("should build successfully when LIVY_ZK_CLIENT_SECURE is disabled") {
        val noSslConf = new LivyConf()
        noSslConf.set(LivyConf.RECOVERY_STATE_STORE_URL, "host")
        noSslConf.set(LivyConf.LIVY_ZK_CLIENT_SECURE, false)
        noException should be thrownBy {
          val zkManager = new ZooKeeperManager(noSslConf, Some(mockCurator()))
          zkManager.start()
          zkManager.stop()
        }
      }

      Seq(
        (LivyConf.SSL_KEYSTORE, "keystore location"),
        (LivyConf.LIVY_ZK_KEYSTORE_PASS, "keystore password"),
        (LivyConf.LIVY_ZK_TRUSTSTORE_FILE, "truststore location"),
        (LivyConf.LIVY_ZK_TRUSTSTORE_PASS, "truststore password")
      ).foreach { case (entry, label) =>
        it(s"should fail fast when LIVY_ZK_CLIENT_SECURE is enabled but $label is missing") {
          val sslConf = makeSslConf()
          sslConf.set(LivyConf.LIVY_ZK_CLIENT_SECURE, true)
          sslConf.set(entry, null)
          val thrown = the[IllegalArgumentException] thrownBy {
            new ZooKeeperManager(sslConf)
          }
          thrown.getMessage should include(entry.key)
        }
      }
    }
  }
}
