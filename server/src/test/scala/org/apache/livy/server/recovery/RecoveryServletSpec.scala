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

import javax.servlet.http.HttpServletRequest

import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.livy.LivyConf
import org.apache.livy.server.{AccessManager, BaseJsonServletSpec}
import org.apache.livy.server.batch.BatchRecoveryMetadata
import org.apache.livy.server.interactive.InteractiveRecoveryMetadata
import org.apache.livy.sessions.{BatchSessionManager, InteractiveSessionManager}
import org.apache.livy.sessions.SessionManager.{SESSION_RECOVERY_MODE_OFF,
  SESSION_RECOVERY_MODE_RECOVERY}

object RecoveryServletSpec {
  val REMOTE_USER_HEADER = "X-Livy-RecoveryServlet-User"
  val ADMIN = "__admin__"
  val REGULAR_USER = "__user__"
}

/** Reads the test-only remote-user header instead of the (unavailable in tests) container user. */
class TestRecoveryServlet(
    livyConf: LivyConf,
    accessManager: AccessManager,
    interactiveSessionManager: InteractiveSessionManager,
    batchSessionManager: BatchSessionManager)
  extends RecoveryServlet(livyConf, accessManager, interactiveSessionManager, batchSessionManager) {

  override protected def remoteUser(req: HttpServletRequest): String = {
    req.getHeader(RecoveryServletSpec.REMOTE_USER_HEADER)
  }
}

trait RecoveryServletSpecBase extends BaseJsonServletSpec {

  import RecoveryServletSpec._

  protected def recoveryMode: String

  protected def headersFor(user: String): Map[String, String] =
    defaultHeaders ++ Map(REMOTE_USER_HEADER -> user)

  private def mockSessionStore(): SessionStore = {
    val sessionStore = mock[SessionStore]
    when(sessionStore.getAllSessions[BatchRecoveryMetadata]("batch")).thenReturn(Seq.empty)
    when(sessionStore.getAllSessions[InteractiveRecoveryMetadata]("interactive"))
      .thenReturn(Seq.empty)
    when(sessionStore.getNextSessionId("batch")).thenReturn(0)
    when(sessionStore.getNextSessionId("interactive")).thenReturn(0)
    sessionStore
  }

  private val livyConf = new LivyConf()
    .set(LivyConf.SUPERUSERS, ADMIN)
    .set(LivyConf.RECOVERY_MODE, recoveryMode)
  private val accessManager = new AccessManager(livyConf)
  private val sessionStore = mockSessionStore()
  private val batchSessionManager = new BatchSessionManager(livyConf, sessionStore)
  private val interactiveSessionManager = new InteractiveSessionManager(livyConf, sessionStore)

  addServlet(
    new TestRecoveryServlet(livyConf, accessManager, interactiveSessionManager,
      batchSessionManager),
    "/*")
}

class RecoveryServletEnabledSpec extends RecoveryServletSpecBase {

  import RecoveryServletSpec._

  override protected def recoveryMode: String = SESSION_RECOVERY_MODE_RECOVERY

  describe("RecoveryServlet with recovery enabled") {

    it("rejects non-superusers with 403") {
      post("/refresh", headers = headersFor(REGULAR_USER)) {
        status should be (403)
      }
    }

    it("allows superusers and returns refresh counts with 200") {
      post("/refresh", headers = headersFor(ADMIN)) {
        status should be (200)
        body should include ("\"sessions\"")
        body should include ("\"batches\"")
      }
    }

    it("allows superusers on the single-manager endpoints with 200") {
      post("/sessions/refresh", headers = headersFor(ADMIN)) {
        status should be (200)
        body should include ("\"added\"")
      }
      post("/batches/refresh", headers = headersFor(ADMIN)) {
        status should be (200)
        body should include ("\"added\"")
      }
    }
  }
}

class RecoveryServletDisabledSpec extends RecoveryServletSpecBase {

  import RecoveryServletSpec._

  override protected def recoveryMode: String = SESSION_RECOVERY_MODE_OFF

  describe("RecoveryServlet with recovery disabled") {

    it("returns 409 when recovery is disabled, even for superusers") {
      post("/refresh", headers = headersFor(ADMIN)) {
        status should be (409)
      }
    }

    it("checks authorization before the recovery-mode check") {
      post("/refresh", headers = headersFor(REGULAR_USER)) {
        status should be (403)
      }
    }
  }
}
