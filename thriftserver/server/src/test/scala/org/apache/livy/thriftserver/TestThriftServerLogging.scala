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

import scala.collection.JavaConverters._

import org.junit.Assert._
import org.junit.Test
import org.slf4j.LoggerFactory

/**
 * Guards the thriftserver classpath against a shaded SLF4J binding. The
 * jetty-runner uber-jar used to bundle a copy of SLF4J 1.x (org.slf4j.*). On the
 * server classpath that copy could shadow slf4j-api 2.x, which then looked for
 * the removed 1.x org.slf4j.impl.StaticLoggerBinder and fell back to the no-op
 * (NOP) logger -- silently dropping all logging. These tests fail if such a jar
 * is reintroduced.
 */
class TestThriftServerLogging {

  @Test
  def exactlyOneLoggerFactoryOnClasspath(): Unit = {
    val resources =
      getClass.getClassLoader.getResources("org/slf4j/LoggerFactory.class").asScala.toList
    assertEquals(
      "Expected exactly one org.slf4j.LoggerFactory on the classpath. A shaded SLF4J " +
        "(e.g. the jetty-runner uber-jar) adds a second copy that can shadow slf4j-api and " +
        s"trigger the StaticLoggerBinder NOP fallback. Found: $resources",
      1, resources.size)
  }

  @Test
  def slf4jBindsToLog4j2NotNop(): Unit = {
    val factory = LoggerFactory.getILoggerFactory.getClass.getName
    assertEquals("SLF4J did not bind to Log4j 2 (log4j-slf4j2-impl)",
      "org.apache.logging.slf4j.Log4jLoggerFactory", factory)

    val logger = LoggerFactory.getLogger(classOf[TestThriftServerLogging]).getClass.getName
    assertFalse(s"SLF4J fell back to a NOP logger implementation: $logger",
      logger.toUpperCase.contains("NOP"))
  }
}
