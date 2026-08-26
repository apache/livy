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

import org.apache.livy.{LivyConf, Logging}
import org.apache.livy.server.{AccessManager, JsonServlet}
import org.apache.livy.sessions.{BatchSessionManager, InteractiveSessionManager}
import org.apache.livy.sessions.SessionManager.{RefreshResult, SESSION_RECOVERY_MODE_OFF}

/**
 * Operator endpoint that re-scans the recovery state store and imports any sessions
 * written by another Livy server. Guarded by `livy.superusers`.
 */
class RecoveryServlet(
    livyConf: LivyConf,
    accessManager: AccessManager,
    interactiveSessionManager: InteractiveSessionManager,
    batchSessionManager: BatchSessionManager)
  extends JsonServlet
  with Logging {

  protected def remoteUser(req: HttpServletRequest): String = req.getRemoteUser()

  before() {
    contentType = "application/json"
    val user = remoteUser(request)
    if (!accessManager.checkSuperUser(user)) {
      halt(403, Map("msg" -> s"User '$user' not authorized for recovery endpoints."))
    }
    if (livyConf.get(LivyConf.RECOVERY_MODE) == SESSION_RECOVERY_MODE_OFF) {
      halt(409, Map("msg" ->
        "Recovery is disabled (livy.server.recovery.mode=off); there is no state to refresh."))
    }
  }

  private def resultMap(r: RefreshResult): Map[String, Int] =
    Map("added" -> r.added, "total" -> r.total, "failed" -> r.failed)

  post("/sessions/refresh") {
    info(s"Interactive session refresh triggered by user='${remoteUser(request)}'")
    resultMap(interactiveSessionManager.refresh())
  }

  post("/batches/refresh") {
    info(s"Batch session refresh triggered by user='${remoteUser(request)}'")
    resultMap(batchSessionManager.refresh())
  }

  post("/refresh") {
    info(s"Full session refresh triggered by user='${remoteUser(request)}'")
    Map(
      "batches" -> resultMap(batchSessionManager.refresh()),
      "sessions" -> resultMap(interactiveSessionManager.refresh())
    )
  }

}
