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
import org.mockito.Mockito._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import org.apache.livy.LivyBaseUnitTestSuite
import org.apache.livy.server.batch.BatchSession
import org.apache.livy.server.interactive.InteractiveSession
import org.apache.livy.sessions.{BatchSessionManager, InteractiveSessionManager, SessionState}

class LivySessionMetricsSpec extends AnyFunSpec with Matchers with MockitoSugar
  with LivyBaseUnitTestSuite {

  private val expectedGauges = Seq(
    "livy.sessions.interactive.total",
    "livy.sessions.interactive.idle",
    "livy.sessions.interactive.starting",
    "livy.sessions.interactive.busy",
    "livy.sessions.interactive.dead",
    "livy.sessions.interactive.shutting_down",
    "livy.sessions.interactive.error",
    "livy.sessions.interactive.killed",
    "livy.sessions.batch.total",
    "livy.sessions.batch.starting",
    "livy.sessions.batch.running",
    "livy.sessions.batch.success",
    "livy.sessions.batch.dead",
    "livy.sessions.batch.error",
    "livy.sessions.batch.killed",
    "livy.sessions.total",
    "livy.sessions.active.total",
    "livy.sessions.terminal.total"
  )

  private def gaugeValue(registry: MetricRegistry, name: String): Int = {
    registry.getGauges.get(name).asInstanceOf[Gauge[Int]].getValue
  }

  private def mockInteractive(state: SessionState): InteractiveSession = {
    val session = mock[InteractiveSession]
    when(session.state).thenReturn(state)
    session
  }

  private def mockBatch(state: SessionState): BatchSession = {
    val session = mock[BatchSession]
    when(session.state).thenReturn(state)
    session
  }

  private def registerMetrics(
      interactiveSessions: Seq[InteractiveSession] = Seq.empty,
      batchSessions: Seq[BatchSession] = Seq.empty,
      registry: MetricRegistry = new MetricRegistry()): Unit = {
    val interactiveManager = mock[InteractiveSessionManager]
    val batchManager = mock[BatchSessionManager]
    when(interactiveManager.size).thenReturn(interactiveSessions.size)
    when(interactiveManager.all()).thenReturn(interactiveSessions)
    when(batchManager.size).thenReturn(batchSessions.size)
    when(batchManager.all()).thenReturn(batchSessions)
    LivySessionMetrics.register(registry, interactiveManager, batchManager)
  }

  describe("LivySessionMetrics") {

    it("should register all 18 session gauges") {
      val registry = new MetricRegistry()
      registerMetrics(registry = registry)

      expectedGauges.foreach { name =>
        registry.getGauges.keySet() should contain(name)
      }
      registry.getGauges.size() shouldBe 18
    }

    it("should not register duplicate gauges on the same registry") {
      val registry = new MetricRegistry()
      val interactiveManager = mock[InteractiveSessionManager]
      val batchManager = mock[BatchSessionManager]
      when(interactiveManager.size).thenReturn(0)
      when(interactiveManager.all()).thenReturn(Seq.empty)
      when(batchManager.size).thenReturn(0)
      when(batchManager.all()).thenReturn(Seq.empty)

      LivySessionMetrics.register(registry, interactiveManager, batchManager)
      LivySessionMetrics.register(registry, interactiveManager, batchManager)

      registry.getGauges.size() shouldBe 18
    }

    it("should report zero when there are no sessions") {
      val registry = new MetricRegistry()
      registerMetrics(registry = registry)

      expectedGauges.foreach { name =>
        gaugeValue(registry, name) shouldBe 0
      }
    }

    it("should count interactive sessions by state") {
      val registry = new MetricRegistry()
      val sessions = Seq(
        mockInteractive(SessionState.Idle),
        mockInteractive(SessionState.Idle),
        mockInteractive(SessionState.Busy),
        mockInteractive(SessionState.Starting),
        mockInteractive(SessionState.ShuttingDown),
        mockInteractive(SessionState.Dead()),
        mockInteractive(SessionState.Error()),
        mockInteractive(SessionState.Killed())
      )
      registerMetrics(interactiveSessions = sessions, registry = registry)

      gaugeValue(registry, "livy.sessions.interactive.total") shouldBe 8
      gaugeValue(registry, "livy.sessions.interactive.idle") shouldBe 2
      gaugeValue(registry, "livy.sessions.interactive.busy") shouldBe 1
      gaugeValue(registry, "livy.sessions.interactive.starting") shouldBe 1
      gaugeValue(registry, "livy.sessions.interactive.shutting_down") shouldBe 1
      gaugeValue(registry, "livy.sessions.interactive.dead") shouldBe 1
      gaugeValue(registry, "livy.sessions.interactive.error") shouldBe 1
      gaugeValue(registry, "livy.sessions.interactive.killed") shouldBe 1
    }

    it("should count batch sessions by state including succeeded alias") {
      val registry = new MetricRegistry()
      val sessions = Seq(
        mockBatch(SessionState.Starting),
        mockBatch(SessionState.Running),
        mockBatch(SessionState.Success()),
        mockBatch(SessionState.Dead()),
        mockBatch(SessionState.Error()),
        mockBatch(SessionState.Killed())
      )
      registerMetrics(batchSessions = sessions, registry = registry)

      gaugeValue(registry, "livy.sessions.batch.total") shouldBe 6
      gaugeValue(registry, "livy.sessions.batch.starting") shouldBe 1
      gaugeValue(registry, "livy.sessions.batch.running") shouldBe 1
      gaugeValue(registry, "livy.sessions.batch.success") shouldBe 1
      gaugeValue(registry, "livy.sessions.batch.dead") shouldBe 1
      gaugeValue(registry, "livy.sessions.batch.error") shouldBe 1
      gaugeValue(registry, "livy.sessions.batch.killed") shouldBe 1
    }

    it("should count batch success for succeeded state alias") {
      val registry = new MetricRegistry()
      val succeededState = mock[SessionState]
      when(succeededState.toString).thenReturn("succeeded")
      val session = mock[BatchSession]
      when(session.state).thenReturn(succeededState)
      registerMetrics(batchSessions = Seq(session), registry = registry)

      gaugeValue(registry, "livy.sessions.batch.success") shouldBe 1
    }

    it("should compute overall totals across interactive and batch sessions") {
      val registry = new MetricRegistry()
      val interactive = Seq(
        mockInteractive(SessionState.Idle),
        mockInteractive(SessionState.Dead())
      )
      val batch = Seq(
        mockBatch(SessionState.Running),
        mockBatch(SessionState.Success())
      )
      registerMetrics(
        interactiveSessions = interactive, batchSessions = batch, registry = registry)

      gaugeValue(registry, "livy.sessions.total") shouldBe 4
      gaugeValue(registry, "livy.sessions.active.total") shouldBe 2
      gaugeValue(registry, "livy.sessions.terminal.total") shouldBe 2
    }

    it("should return zero from gauges when session managers throw") {
      val registry = new MetricRegistry()
      val interactiveManager = mock[InteractiveSessionManager]
      val batchManager = mock[BatchSessionManager]
      when(interactiveManager.size).thenReturn(1)
      when(interactiveManager.all()).thenThrow(new RuntimeException("interactive failure"))
      when(batchManager.size).thenReturn(1)
      when(batchManager.all()).thenThrow(new RuntimeException("batch failure"))

      LivySessionMetrics.register(registry, interactiveManager, batchManager)

      gaugeValue(registry, "livy.sessions.interactive.idle") shouldBe 0
      gaugeValue(registry, "livy.sessions.batch.running") shouldBe 0
      gaugeValue(registry, "livy.sessions.active.total") shouldBe 0
      gaugeValue(registry, "livy.sessions.terminal.total") shouldBe 0
    }
  }
}
