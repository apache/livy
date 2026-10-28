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

import javax.servlet.FilterChain
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.mockito.Mockito.{never, verify, when}
import org.scalatest.funspec.AnyFunSpec
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.livy.LivyBaseUnitTestSuite

class HttpTraceTrackFilterSpec extends AnyFunSpec with LivyBaseUnitTestSuite {

  private case class FilterResult(
      request: HttpServletRequest,
      response: HttpServletResponse,
      chain: FilterChain)

  private def runFilter(method: String): FilterResult = {
    val request = mock[HttpServletRequest]
    val response = mock[HttpServletResponse]
    val chain = mock[FilterChain]
    when(request.getMethod).thenReturn(method)
    new HttpTraceTrackFilter().doFilter(request, response, chain)
    FilterResult(request, response, chain)
  }

  describe("HttpTraceTrackFilter") {

    (HttpTraceTrackFilter.DISALLOWED_METHODS + "TrAcE").foreach { method =>
      it(s"should reject $method with 405 Method Not Allowed") {
        val result = runFilter(method)
        verify(result.response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
        verify(result.chain, never()).doFilter(result.request, result.response)
      }
    }

    Seq("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD").foreach { method =>
      it(s"should allow $method requests") {
        val result = runFilter(method)
        verify(result.chain).doFilter(result.request, result.response)
        verify(result.response, never()).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
      }
    }
  }
}
