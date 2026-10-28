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

import javax.servlet.{Filter, FilterChain, FilterConfig, ServletRequest, ServletResponse}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

object HttpTraceTrackFilter {
  val DISALLOWED_METHODS: Set[String] = Set("TRACE", "TRACK")
}

/**
 * Rejects HTTP TRACE and TRACK requests with 405 Method Not Allowed.
 */
class HttpTraceTrackFilter extends Filter {

  override def init(filterConfig: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest,
                        response: ServletResponse,
                        chain: FilterChain): Unit = {
    val method = request.asInstanceOf[HttpServletRequest].getMethod
    if (HttpTraceTrackFilter.DISALLOWED_METHODS.contains(method.toUpperCase)) {
      response.asInstanceOf[HttpServletResponse]
        .sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
    } else {
      chain.doFilter(request, response)
    }
  }

  override def destroy(): Unit = {}
}
