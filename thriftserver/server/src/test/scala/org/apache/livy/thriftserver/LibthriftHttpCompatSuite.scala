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

import org.apache.http.client.{HttpClient => ApacheHttpClient4}
import org.apache.thrift.transport.THttpClient
import org.scalatest.funsuite.AnyFunSuite

/**
 * Guards the Apache HttpClient 4.x `THttpClient` constructor that Hive JDBC relies on over HTTP
 * transport.
 *
 * Hive JDBC's `HiveConnection.createHttpTransport` calls
 * `new THttpClient(String, org.apache.http.client.HttpClient)`. libthrift 0.23+ (the version Livy
 * pins for CVE-2026-43869) migrated `THttpClient` to Apache HttpClient 5 and removed that
 * constructor, which previously surfaced only as a `NoSuchMethodError` deep inside the HTTP
 * integration suite. The test-scoped compatibility `THttpClient` restores it; this fast unit test
 * fails immediately with a clear message if a future libthrift/Hive bump breaks the shim again.
 */
class LibthriftHttpCompatSuite extends AnyFunSuite {

  test("THttpClient exposes the Apache HttpClient 4.x constructor used by Hive JDBC") {
    val ctor =
      try {
        classOf[THttpClient].getConstructor(classOf[String], classOf[ApacheHttpClient4])
      } catch {
        case _: NoSuchMethodException =>
          fail("org.apache.thrift.transport.THttpClient is missing the HttpClient 4.x " +
            "constructor (String, org.apache.http.client.HttpClient) required by Hive JDBC over " +
            "HTTP transport. The libthrift HTTP compatibility shim is missing or has been " +
            "shadowed by libthrift's HttpClient 5 THttpClient.")
      }
    assert(ctor.getParameterTypes()(1) == classOf[ApacheHttpClient4],
      "THttpClient constructor must take org.apache.http.client.HttpClient (HttpClient 4.x)")
  }
}
