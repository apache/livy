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

package org.apache.livy.client.http

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.client.utils.URIBuilder
import org.eclipse.jetty.security._
import org.eclipse.jetty.security.UserStore
import org.eclipse.jetty.security.authentication.BasicAuthenticator
import org.eclipse.jetty.util.security._
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}
import org.scalatest.Matchers._
import org.scalatra.{LifeCycle, ScalatraServlet}
import org.scalatra.servlet.ScalatraListener

import org.apache.livy.{LivyBaseUnitTestSuite, LivyConf}
import org.apache.livy.client.common.HttpMessages.{CreateClientRequest, SessionInfo}
import org.apache.livy.server.WebServer

class LivyConnectionSpec extends FunSpecLike with BeforeAndAfterAll with LivyBaseUnitTestSuite {
  describe("LivyConnection") {
    def basicAuth(username: String, password: String, realm: String): SecurityHandler = {
      val roles = Array("user")

      val l = new HashLoginService()
      val userStore = new UserStore()
      userStore.addUser(username, Credential.getCredential(password), roles)
      l.setUserStore(userStore)
      l.setName(realm)

      val constraint = new Constraint()
      constraint.setName(Constraint.__BASIC_AUTH)
      constraint.setRoles(roles)
      constraint.setAuthenticate(true)

      val cm = new ConstraintMapping()
      cm.setConstraint(constraint)
      cm.setPathSpec("/*")

      val csh = new ConstraintSecurityHandler()
      csh.setAuthenticator(new BasicAuthenticator())
      csh.setRealmName(realm)
      csh.addConstraintMapping(cm)
      csh.setLoginService(l)

      csh
    }

    def test(password: String, livyConf: LivyConf = new LivyConf()): Unit = {
      val username = "user name"

      val server = new WebServer(livyConf, "0.0.0.0", 0)
      server.context.setSecurityHandler(basicAuth(username, password, "realm"))
      server.context.setResourceBase("src/main/org/apache/livy/server")
      server.context.setInitParameter(ScalatraListener.LifeCycleKey,
        classOf[HttpClientTestBootstrap].getCanonicalName)
      server.context.addEventListener(new ScalatraListener)
      server.start()

      val uri = new URIBuilder()
        .setScheme(server.protocol)
        .setHost("127.0.0.1")
        .setPort(server.port)
        .setUserInfo(username, password)
        .build()
      info(uri.toString)
      val conn = new LivyConnection(uri, new HttpConf(null))
      try {
        conn.get(classOf[Object], "/") should not be (null)

      } finally {
        conn.close()
      }

      server.stop()
      server.join()
    }

    it("should support HTTP auth with password") {
      test("pass:word")
    }

    it("should support HTTP auth with password containing plus") {
      test("p+w")
    }

    it("should support HTTP auth with empty password") {
      test("")
    }

    it("should be failed with large header size") {
      val livyConf = new LivyConf()
        .set(LivyConf.REQUEST_HEADER_SIZE, 1024)
        .set(LivyConf.RESPONSE_HEADER_SIZE, 1024)
      val pwd = "test-password" * 100
      val exception = intercept[IOException](test(pwd, livyConf))
      exception.getMessage.contains("Request Header Fields Too Large") should be(true)
    }

    it("should be succeeded with configured header size") {
      val livyConf = new LivyConf()
        .set(LivyConf.REQUEST_HEADER_SIZE, 2048)
        .set(LivyConf.RESPONSE_HEADER_SIZE, 2048)
      val pwd = "test-password" * 100
      test(pwd, livyConf)
    }
  }

  describe("LivyConnection HA redirect handling") {
    def serverBaseUrl(server: WebServer): String =
      s"${server.protocol}://127.0.0.1:${server.port}"

    def startActiveServer(): WebServer = {
      val server = new WebServer(new LivyConf(), "0.0.0.0", 0)
      server.context.setResourceBase("src/main/org/apache/livy/server")
      server.context.setInitParameter(ScalatraListener.LifeCycleKey,
        classOf[ActiveServerBootstrap].getCanonicalName)
      server.context.addEventListener(new ScalatraListener)
      server.start()
      server
    }

    def startStandbyServer(bootstrap: Class[_ <: LifeCycle]): WebServer = {
      val server = new WebServer(new LivyConf(), "0.0.0.0", 0)
      server.context.setResourceBase("src/main/org/apache/livy/server")
      server.context.setInitParameter(ScalatraListener.LifeCycleKey, bootstrap.getCanonicalName)
      server.context.addEventListener(new ScalatraListener)
      server.start()
      server
    }

    def standbyUri(server: WebServer): URI =
      new URIBuilder().setScheme(server.protocol).setHost("127.0.0.1").setPort(server.port).build()

    def withHaServers(
        bootstrap: Class[_ <: LifeCycle] = classOf[StandbyRedirectBootstrap],
        includeActive: Boolean = true)(
        testFn: (URI, Option[WebServer], WebServer) => Unit): Unit = {
      HaTestContext.reset()
      var activeServer: WebServer = null
      var standbyServer: WebServer = null
      try {
        val activeOpt = if (includeActive) {
          activeServer = startActiveServer()
          HaTestContext.activeServerUrl = serverBaseUrl(activeServer)
          Some(activeServer)
        } else {
          None
        }
        standbyServer = startStandbyServer(bootstrap)
        testFn(standbyUri(standbyServer), activeOpt, standbyServer)
      } finally {
        if (standbyServer != null) {
          standbyServer.stop()
          standbyServer.join()
        }
        if (activeServer != null) {
          activeServer.stop()
          activeServer.join()
        }
      }
    }

    def header(req: RecordedRequest, name: String): Option[String] =
      req.headers.get(name.toLowerCase)

    it("should follow 307 redirects and preserve request contract on active server") {
      withHaServers() { (uri, _, _) =>
        val conn = new LivyConnection(uri, new HttpConf(null))
        val tmpFile = Files.createTempFile("livy-ha-test", ".jar").toFile
        try {
          Files.write(tmpFile.toPath, "test-jar-content".getBytes(UTF_8))

          val create = new CreateClientRequest(Map("spark.app.name" -> "test").asJava)
          val info = conn.post(create, classOf[SessionInfo], "/")
          conn.delete(classOf[java.lang.Void], "/%d", Int.box(42))
          conn.post(tmpFile, classOf[java.lang.Void], "jar", "/%d/upload-jar", Int.box(1))

          info.id should be (1)
          info.state should be ("idle")
          info.kind should be ("spark")

          HaTestContext.standbyRequests.asScala should have size 3
          HaTestContext.activeRequests.asScala should have size 3

          val postActive = HaTestContext.activeRequests.asScala.toList(0)
          postActive.method should be ("POST")
          postActive.path should be ("/sessions/")
          header(postActive, "Content-Type") should contain ("application/json")
          header(postActive, "Accept") should contain ("application/json")
          header(postActive, "X-Requested-By") should contain ("livy")
          postActive.body.get should include ("spark.app.name")

          val deleteActive = HaTestContext.activeRequests.asScala.toList(1)
          deleteActive.method should be ("DELETE")
          deleteActive.path should be ("/sessions/42")
          header(deleteActive, "X-Requested-By") should contain ("livy")

          val multipartActive = HaTestContext.activeRequests.asScala.toList(2)
          multipartActive.method should be ("POST")
          multipartActive.path should be ("/sessions/1/upload-jar")
          header(multipartActive, "Content-Type").get should startWith ("multipart/")
          multipartActive.body.get should include ("test-jar-content")

          HaTestContext.standbyRequests.asScala.map(_.method).toList should
            equal(List("POST", "DELETE", "POST"))
        } finally {
          conn.close()
          tmpFile.delete()
        }
      }
    }

    it("should fail fast on invalid redirect responses without contacting active server") {
      case class FailureScenario(
          bootstrap: Class[_ <: LifeCycle],
          setup: (Option[WebServer], WebServer) => Unit,
          action: LivyConnection => Unit,
          expectedMsg: String,
          activeCount: Int,
          standbyCount: Int,
          includeActive: Boolean = true)

      val scenarios = Seq(
        FailureScenario(
          classOf[StandbyNoLocationBootstrap],
          (_, _) => (),
          _.post(null, classOf[SessionInfo], "/"),
          "Temporary Redirect without Location header",
          activeCount = 0,
          standbyCount = 1),
        FailureScenario(
          classOf[StandbyEmptyLocationBootstrap],
          (_, _) => (),
          _.delete(classOf[java.lang.Void], "/%d", Int.box(0)),
          "Temporary Redirect without Location header",
          activeCount = 0,
          standbyCount = 1),
        FailureScenario(
          classOf[StandbyRedirectLoopBootstrap],
          (_, standby) => HaTestContext.redirectLoopUrl = serverBaseUrl(standby),
          _.get(classOf[SessionInfo], "/"),
          "Too many redirects",
          activeCount = 0,
          standbyCount = 2,
          includeActive = false),
        FailureScenario(
          classOf[StandbyErrorBootstrap],
          (_, _) => (),
          _.post(null, classOf[SessionInfo], "/"),
          "Not Found",
          activeCount = 0,
          standbyCount = 1))

      scenarios.foreach { scenario =>
        withHaServers(scenario.bootstrap, scenario.includeActive) { (uri, activeOpt, standby) =>
          scenario.setup(activeOpt, standby)
          val conn = new LivyConnection(uri, new HttpConf(null))
          try {
            val ex = intercept[IOException](scenario.action(conn))
            ex.getMessage should include (scenario.expectedMsg)
            HaTestContext.activeRequests.asScala should have size scenario.activeCount
            HaTestContext.standbyRequests.asScala should have size scenario.standbyCount
          } finally {
            conn.close()
          }
        }
      }
    }
  }
}

private case class RecordedRequest(
    server: String,
    method: String,
    path: String,
    headers: Map[String, String],
    body: Option[String])

private object HaTestContext {
  val standbyRequests = new ConcurrentLinkedQueue[RecordedRequest]()
  val activeRequests = new ConcurrentLinkedQueue[RecordedRequest]()
  var activeServerUrl: String = _
  var redirectLoopUrl: String = _
  private val mapper = new ObjectMapper()

  def reset(): Unit = {
    standbyRequests.clear()
    activeRequests.clear()
    activeServerUrl = null
    redirectLoopUrl = null
  }

  def sessionInfoJson(id: Int): String = {
    val info = new SessionInfo(id, null, null, null, "idle", "spark",
      Map.empty[String, String].asJava, List.empty[String].asJava, null, null, null, 0, null, 0,
      Map.empty[String, String].asJava, List.empty[String].asJava, List.empty[String].asJava, 0,
      List.empty[String].asJava, 0, null, List.empty[String].asJava, null)
    mapper.writeValueAsString(info)
  }
}

private object HaTestServlet {
  def log(server: String, req: HttpServletRequest, content: Option[String] = None): Unit = {
    val headers = req.getHeaderNames.asScala.flatMap { name =>
      req.getHeaders(name).asScala.map(value => name.toLowerCase -> value)
    }.toMap
    val recorded = RecordedRequest(server, req.getMethod, req.getRequestURI, headers, content)
    if (server == "standby") {
      HaTestContext.standbyRequests.add(recorded)
    } else {
      HaTestContext.activeRequests.add(recorded)
    }
  }
}

private class ActiveLivyServlet extends ScalatraServlet {
  before() {
    contentType = "application/json"
  }

  get("/") {
    HaTestServlet.log("active", request)
    HaTestContext.sessionInfoJson(0)
  }

  post("/") {
    HaTestServlet.log("active", request, Some(request.body))
    HaTestContext.sessionInfoJson(1)
  }

  delete("/:id") {
    HaTestServlet.log("active", request)
    status = 200
    "{}"
  }

  post("/:id/upload-jar") {
    HaTestServlet.log("active", request, Some(request.body))
    status = 200
    "{}"
  }
}

private class ActiveServerBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new ActiveLivyServlet, s"${LivyConnection.SESSIONS_URI}/*")
  }
}

private class StandbyRedirectServlet(location: String => String) extends ScalatraServlet {
  private def redirect(): Unit = {
    HaTestServlet.log("standby", request, Some(request.body))
    response.setStatus(307)
    response.setHeader("Location", location(request.getRequestURI))
    halt(307, "Temporary Redirect")
  }

  get("/*") { redirect() }
  post("/*") { redirect() }
  delete("/*") { redirect() }
}

private class StandbyRedirectBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new StandbyRedirectServlet((uri: String) =>
      HaTestContext.activeServerUrl.stripSuffix("/") + uri),
      s"${LivyConnection.SESSIONS_URI}/*")
  }
}

private class StandbyNoLocationBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new ScalatraServlet {
      post("/*") {
        HaTestServlet.log("standby", request)
        response.setStatus(307)
        halt(307, "Temporary Redirect")
      }
    }, s"${LivyConnection.SESSIONS_URI}/*")
  }
}

private class StandbyEmptyLocationBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new ScalatraServlet {
      delete("/*") {
        HaTestServlet.log("standby", request)
        response.setStatus(307)
        response.setHeader("Location", "")
        halt(307, "Temporary Redirect")
      }
    }, s"${LivyConnection.SESSIONS_URI}/*")
  }
}

private class StandbyRedirectLoopBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new StandbyRedirectServlet((uri: String) =>
      HaTestContext.redirectLoopUrl.stripSuffix("/") + uri),
      s"${LivyConnection.SESSIONS_URI}/*")
  }
}

private class StandbyErrorBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new ScalatraServlet {
      post("/*") {
        HaTestServlet.log("standby", request)
        status = 404
        contentType = "application/json"
        """{"msg":"session not found"}"""
      }
    }, s"${LivyConnection.SESSIONS_URI}/*")
  }
}
