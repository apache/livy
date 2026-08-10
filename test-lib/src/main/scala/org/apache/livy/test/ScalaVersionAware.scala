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

package org.apache.livy.test

/**
 * Shared Scala-version-dependent helpers for the Livy test suites. Mixed into
 * base spec classes so their subclasses see a single canonical
 * `optionalValPrefix` (and `optional*PrefixRegex` fragments for regex-based
 * `verifyResult` calls in integration tests). Detection uses the runtime
 * Scala version, so this trait works regardless of which Scala the artifact
 * was compiled against.
 */
trait ScalaVersionAware {

  /** Prefix that the Scala 2.13 REPL prints before a value binding, e.g.
   *  `val res0: Int = 2` vs. Scala 2.12's `res0: Int = 2`. Empty on 2.12.
   *  Use in exact-match expected strings. */
  protected val optionalValPrefix: String =
    if (scala.util.Properties.versionNumberString.startsWith("2.13")) "val " else ""

  /** Regex fragment `(val )?` -- an optional-`val ` alternation that matches
   *  the Scala 2.12 and 2.13 REPL output shape in one pattern. Use in regex-
   *  based `verifyResult` calls. */
  protected val optionalValPrefixRegex: String = "(val )?"

  /** Regex fragment `(?:defined )?` -- Scala 2.12's `defined class X` vs.
   *  Scala 2.13's plain `class X` REPL output. */
  protected val optionalDefinedPrefixRegex: String = "(?:defined )?"

  /** Regex fragment `(?:warning:.*\n)?` -- Scala 2.13 emits a
   *  `warning: n deprecation(s)...` line above value bindings that use a
   *  deprecated API; Scala 2.12 does not. Pair with `(?s)` DOTALL to span
   *  the newline. */
  protected val optionalWarningPrefixRegex: String = "(?:warning:.*\\n)?"
}
