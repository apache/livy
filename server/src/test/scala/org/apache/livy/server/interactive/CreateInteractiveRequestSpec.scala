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

package org.apache.livy.server.interactive

import com.fasterxml.jackson.databind.{JsonMappingException, ObjectMapper}
import org.scalatest.funspec.AnyFunSpec

import org.apache.livy.LivyBaseUnitTestSuite
import org.apache.livy.sessions.{PySpark, SessionKindModule}

class CreateInteractiveRequestSpec extends AnyFunSpec with LivyBaseUnitTestSuite {

  private val mapper = new ObjectMapper()
    .registerModule(com.fasterxml.jackson.module.scala.DefaultScalaModule)
    .registerModule(new SessionKindModule())

  describe("CreateInteractiveRequest") {

    it("should have default values for fields after deserialization") {
      val json = """{ "kind" : "pyspark" }"""
      val req = mapper.readValue(json, classOf[CreateInteractiveRequest])
      assert(req.kind === PySpark)
      assert(req.proxyUser === None)
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
        """{ "kind" : "pyspark", "driverCores" : 4, "executorCores" : 2, "numExecutors" : 20 }"""
      val req = mapper.readValue(json, classOf[CreateInteractiveRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
      assert(req.numExecutors === Some(20))
    }

    it("should coerce numeric fields sent as JSON strings") {
      // Same Option[Int] erasure fix as CreateBatchRequest — string "4" must coerce to 4.
      val json =
        """{ "kind" : "pyspark", "driverCores" : "4", "executorCores" : "2", """ +
          """"numExecutors" : "20" }"""
      val req = mapper.readValue(json, classOf[CreateInteractiveRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
      assert(req.numExecutors === Some(20))
      assert(req.driverCores.map(_ + 1) === Some(5))
    }

    it("should reject a non-numeric string for a numeric field with a mapping error") {
      val json = """{ "kind" : "pyspark", "driverCores" : "notanumber" }"""
      intercept[JsonMappingException] {
        mapper.readValue(json, classOf[CreateInteractiveRequest])
      }
    }

    it("should treat an empty string as None") {
      // Jackson's scalar coercion maps "" → null, which Option deserializes as None.
      val req = mapper.readValue("""{ "kind" : "pyspark", "driverCores" : "" }""",
        classOf[CreateInteractiveRequest])
      assert(req.driverCores === None)
    }

    it("should coerce whitespace-padded numeric strings") {
      val req = mapper.readValue(
        """{ "kind" : "pyspark", "driverCores" : "4 ", "executorCores" : " 2" }""",
        classOf[CreateInteractiveRequest])
      assert(req.driverCores === Some(4))
      assert(req.executorCores === Some(2))
    }

    it("should reject fractional numeric strings with a mapping error") {
      Seq("""{ "kind" : "pyspark", "driverCores" : "4.5" }""",
          """{ "kind" : "pyspark", "driverCores" : "4.0" }""").foreach { json =>
        intercept[JsonMappingException] {
          mapper.readValue(json, classOf[CreateInteractiveRequest])
        }
      }
    }

  }

}
