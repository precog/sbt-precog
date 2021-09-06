/*
 * Copyright 2021 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package precog.algebras

import precog.algebras.Runner.RunnerConfig
import sbt.file

class RunnerConfigSpec extends org.specs2.mutable.Specification {
  "RunnerConfig fluent builders" should {
    "be equivalent to case class build" in {
      val config = Runner.DefaultConfig
      config mustEqual RunnerConfig(None, Map.empty, Nil, Runner.DefaultSecretReplacement)
      config.cd(file("./target")) mustEqual config.copy(cwd = Some(file("./target")))
      config.withEnv("X" -> "Y", "W" -> "Z") mustEqual config.copy(env =
        Map("X" -> "Y", "W" -> "Z"))
      config.hide("password") mustEqual config.copy(hide = List("password"))
    }

    "append instead of replace" in {
      val config = Runner.DefaultConfig
      config.withEnv("X" -> "Y").withEnv("W" -> "Z") mustEqual config.copy(env =
        Map("X" -> "Y", "W" -> "Z"))
      config.hide("xyzzy").hide("foobar") mustEqual config.copy(hide = List("foobar", "xyzzy"))
    }

    "sanitize strings" in {
      val config = Runner.DefaultConfig
      val input = "this xyzzy that print_foobar"
      Runner.DefaultSecretReplacement mustEqual "*****"
      config
        .hide("xyzzy")
        .hide("foobar")
        .sanitize(input) mustEqual "this ***** that print_*****"
    }
  }
}
