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

package org.apache.livy.server.batch

import com.fasterxml.jackson.databind.{JsonMappingException, ObjectMapper}
import org.scalatest.funspec.AnyFunSpec

import org.apache.livy.LivyBaseUnitTestSuite

class CreateBatchRequestSpec extends AnyFunSpec with LivyBaseUnitTestSuite {

  private val mapper = new ObjectMapper()
    .registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)

  describe("CreateBatchRequest") {

    it("should have default values for fields after deserialization") {
      val json = """{ "file" : "foo" }"""
      val req = mapper.readValue(json, classOf[CreateBatchRequest])
      assert(req.file === "foo")
      assert(req.proxyUser === None)
      assert(req.args === List())
      assert(req.className === None)
      assert(req.jars === List())
      assert(req.pyFiles === List())
      assert(req.files === List())
      assert(req.driverMemory === None)
      assert(req.driverCores === None)
      assert(req.executorMemory === None)
      assert(req.executorCores === None)
      assert(req.numExecutors === None)
      assert(req.archives === List())
      assert(req.queue === None)
      assert(req.name === None)
      assert(req.conf === Map())
    }

    it("should deserialize numeric fields sent as JSON numbers") {
      val json =
        """{ "file" : "foo", "driverCores" : 4, "executorCores" : 2, "numExecutors" : 20 }"""
      val req = mapper.readValue(json, classOf[CreateBatchRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
      assert(req.numExecutors === Some(20))
    }

    it("should coerce numeric fields sent as JSON strings") {
      // A mistyped client that sends "4" instead of 4 must not blow up with a
      // ClassCastException in BatchSession.createSparkApp -- Jackson coerces the string.
      val json =
        """{ "file" : "foo", "driverCores" : "4", "executorCores" : "2", "numExecutors" : "20" }"""
      val req = mapper.readValue(json, classOf[CreateBatchRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
      assert(req.numExecutors === Some(20))
      // The unbox that used to throw at BatchSession.scala:95 now succeeds.
      assert(req.driverCores.map(_ + 1) === Some(5))
    }

    it("should reject a non-numeric string for a numeric field with a mapping error") {
      val json = """{ "file" : "foo", "driverCores" : "notanumber" }"""
      intercept[JsonMappingException] {
        mapper.readValue(json, classOf[CreateBatchRequest])
      }
    }

    it("should treat an empty string as None") {
      // Jackson's scalar coercion maps "" → null, which Option deserializes as None.
      val req = mapper.readValue("""{ "file" : "foo", "driverCores" : "" }""",
        classOf[CreateBatchRequest])
      assert(req.driverCores === None)
    }

    it("should coerce whitespace-padded numeric strings") {
      val req = mapper.readValue(
        """{ "file" : "foo", "driverCores" : "4 ", "executorCores" : " 2" }""",
        classOf[CreateBatchRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
    }

    it("should reject fractional numeric strings with a mapping error") {
      Seq("""{ "file" : "foo", "driverCores" : "4.5" }""",
          """{ "file" : "foo", "driverCores" : "4.0" }""").foreach { json =>
        intercept[JsonMappingException] {
          mapper.readValue(json, classOf[CreateBatchRequest])
        }
      }
    }

  }

}
