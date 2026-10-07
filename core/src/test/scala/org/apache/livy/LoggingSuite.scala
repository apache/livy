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

package org.apache.livy

import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters._

import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.{LogEvent, LoggerContext}
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider

class LoggingSuite extends AnyFunSuite with LivyBaseUnitTestSuite {

  test("SLF4J is bound to the Log4j 2 provider via log4j-slf4j2-impl") {
    // The org.slf4j.spi.SLF4JServiceProvider interface only exists in SLF4J 2.x,
    // and the ServiceLoader-based lookup replaces the SLF4J 1.x StaticLoggerBinder.
    // log4j-slf4j-impl (the 1.x binding) registers no provider, so this check fails
    // if the binding was not migrated to log4j-slf4j2-impl.
    val providers = ServiceLoader.load(classOf[SLF4JServiceProvider]).asScala.toList
    assert(providers.size === 1,
      s"expected exactly one SLF4J provider, found: ${providers.map(_.getClass.getName)}")
    assert(providers.head.getClass.getName === "org.apache.logging.slf4j.SLF4JServiceProvider")

    // The binding actually wired into LoggerFactory must be the same Log4j 2 implementation.
    assert(LoggerFactory.getILoggerFactory.getClass.getName ===
      "org.apache.logging.slf4j.Log4jLoggerFactory")
    val logger = LoggerFactory.getLogger(classOf[LoggingSuite])
    assert(logger.getClass.getName === "org.apache.logging.slf4j.Log4jLogger")
  }

  test("messages logged through the Logging trait are routed to Log4j 2") {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val config = ctx.getConfiguration
    val root = config.getRootLogger

    val appender = new CapturingAppender("LoggingSuiteCapture")
    appender.start()
    config.addAppender(appender)
    val previousLevel = root.getLevel
    root.addAppender(appender, Level.TRACE, null)
    root.setLevel(Level.TRACE)
    ctx.updateLoggers()

    try {
      val subject = new Logging {}
      val marker = "livy-slf4j2-logging-check"
      subject.info(marker)
      subject.warn(s"$marker-warn")

      val captured = appender.formattedMessages
      assert(captured.contains(marker),
        s"info message not routed to Log4j 2; captured: $captured")
      assert(captured.contains(s"$marker-warn"),
        s"warn message not routed to Log4j 2; captured: $captured")
    } finally {
      root.removeAppender(appender.getName)
      root.setLevel(previousLevel)
      appender.stop()
      ctx.updateLoggers()
    }
  }
}

/** In-memory Log4j 2 appender that records the events it receives. */
private class CapturingAppender(name: String)
  extends AbstractAppender(
    name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {

  private val events = new ConcurrentLinkedQueue[LogEvent]()

  override def append(event: LogEvent): Unit = {
    events.add(event.toImmutable)
  }

  def formattedMessages: Seq[String] =
    events.asScala.map(_.getMessage.getFormattedMessage).toSeq
}
