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

package org.apache.livy.server

import com.codahale.metrics.{Gauge, MetricRegistry}

import org.apache.livy.sessions.{BatchSessionManager, InteractiveSessionManager}

/**
 * Registers Livy session metrics as Codahale/Dropwizard gauges in the MetricRegistry.
 *
 * Uses the existing `io.dropwizard.metrics:metrics-core` dependency (Java package
 * `com.codahale.metrics`) already declared in `server/pom.xml` and wired through
 * `scalatra-metrics` (`MetricsBootstrap`) in `LivyServer`. No new metrics library
 * is introduced by this class; it only adds session gauges to the shared registry
 * exposed at `/metrics`.
 *
 * 18 gauges total:
 *   - 3 overall (total, active_total, terminal_total)
 *   - 8 interactive (total, idle, busy, starting, shutting_down, dead, error, killed)
 *   - 7 batch (total, starting, running, success, dead, error, killed)
 */
object LivySessionMetrics {

  /**
   * Registers session count gauges into `metricRegistry`. Idempotent: existing gauge names
   * are left unchanged. Gauge callbacks capture the session managers passed here, so no
   * long-lived helper instance is required.
   */
  def register(
      metricRegistry: MetricRegistry,
      interactiveSessionManager: InteractiveSessionManager,
      batchSessionManager: BatchSessionManager): Unit = {

    def registerGauge(name: String, valueFn: => Int): Unit = {
      if (!metricRegistry.getGauges.containsKey(name)) {
        metricRegistry.register(name, new Gauge[Int] {
          override def getValue: Int = {
            try {
              valueFn
            } catch {
              case _: Throwable => 0
            }
          }
        })
      }
    }

    def countInteractiveByState(expectedStates: Set[String]): Int = {
      interactiveSessionManager.all().count { session =>
        expectedStates.contains(normalizeState(session.state.toString))
      }
    }

    def countBatchByState(expectedStates: Set[String]): Int = {
      batchSessionManager.all().count { session =>
        expectedStates.contains(normalizeState(session.state.toString))
      }
    }

    def normalizeState(state: String): String = {
      Option(state).getOrElse("").trim.toLowerCase.replace("-", "_").replace(" ", "_")
    }

    // ========== Interactive Session Metrics (8) ==========

    registerGauge("livy.sessions.interactive.total",
      interactiveSessionManager.size)

    registerGauge("livy.sessions.interactive.idle",
      countInteractiveByState(Set("idle")))

    registerGauge("livy.sessions.interactive.starting",
      countInteractiveByState(Set("starting")))

    registerGauge("livy.sessions.interactive.busy",
      countInteractiveByState(Set("busy")))

    registerGauge("livy.sessions.interactive.dead",
      countInteractiveByState(Set("dead")))

    registerGauge("livy.sessions.interactive.shutting_down",
      countInteractiveByState(Set("shutting_down", "shuttingdown")))

    registerGauge("livy.sessions.interactive.error",
      countInteractiveByState(Set("error")))

    registerGauge("livy.sessions.interactive.killed",
      countInteractiveByState(Set("killed")))

    // ========== Batch Session Metrics (7) ==========

    registerGauge("livy.sessions.batch.total",
      batchSessionManager.size)

    registerGauge("livy.sessions.batch.starting",
      countBatchByState(Set("starting")))

    registerGauge("livy.sessions.batch.running",
      countBatchByState(Set("running")))

    registerGauge("livy.sessions.batch.success",
      countBatchByState(Set("success", "succeeded")))

    registerGauge("livy.sessions.batch.dead",
      countBatchByState(Set("dead")))

    registerGauge("livy.sessions.batch.error",
      countBatchByState(Set("error")))

    registerGauge("livy.sessions.batch.killed",
      countBatchByState(Set("killed")))

    // ========== Overall Metrics (3) ==========

    registerGauge("livy.sessions.total",
      interactiveSessionManager.size + batchSessionManager.size)

    registerGauge("livy.sessions.active.total",
      countInteractiveByState(Set("starting", "idle", "busy")) +
        countBatchByState(Set("starting", "running")))

    registerGauge("livy.sessions.terminal.total",
      countInteractiveByState(Set("dead", "error", "killed")) +
        countBatchByState(Set("success", "succeeded", "dead", "error", "killed")))
  }
}
