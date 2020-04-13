/*
 * Copyright 2020 Precog Data
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

package precog

import cats.effect.IO
import sbt.file

import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger

class RunnerSpec extends org.specs2.mutable.Specification {
  val log = sbt.util.LogExchange.logger("test")

  "Fluent builders" should {
    "be equivalent to case class build" in {
      val runner = Runner[IO](log)
      runner === Runner[IO](log, false, None, Map.empty, Seq.empty)
      runner.stderrToStdout === Runner[IO](log, merge = true)
      runner.cd(file("./target")) === Runner[IO](log, workingDir = Some(file("./target")))
      runner.withEnv("X" -> "Y", "W" -> "Z") === Runner[IO](log, env = Map("X" -> "Y", "W" -> "Z"))
      runner.hide("password") === Runner[IO](log, hide = Seq("password"))
    }

    "append instead of replace" in {
      Runner[IO](log).withEnv("X" -> "Y").withEnv("W" -> "Z") === Runner[IO](log, env = Map("X" -> "Y", "W" -> "Z"))
      Runner[IO](log).hide("xyzzy").hide("foobar") === Runner[IO](log, hide = Seq("xyzzy", "foobar"))
    }
  }

  "exclamation mark operator" should {
    "return captured output" in {
      val runner = Runner[IO](log)
      (runner ! Seq("bash", "-c", "echo 'abc'")).unsafeRunSync() === List("abc")
    }

    "break string by spaces" in {
      val runner = Runner[IO](log)
      (runner ! "printf %s%d abc 5").unsafeRunSync() === List("abc5")
    }

    "respect working directory" in {
      val runner = Runner[IO](log).cd(file("core/target"))
      (runner ! "bash -c pwd").unsafeRunSync().head must be endingWith("/target")
    }

    "raise exception on errors" in {
      val runner = Runner[IO](log)
      (runner ! Seq("bash", "-c", "exit 1")).unsafeRunSync() must throwA[RuntimeException]("bash -c exit code 1")
    }

    "hide stuff" in {
      val runner = Runner[IO](log).hide("xyzzy")
      (runner ! "printf abc%sdef xyzzy").unsafeRunSync() === List("abc*****def")
    }.pendingUntilFixed("must capture logger")

    "pass environment variables" in {
      val runner = Runner[IO](log).withEnv("X" -> "Y")
      (runner ! Seq("bash", "-c", "echo $X")).unsafeRunSync() === List("Y")
    }

    "merge log" in {
      todo
    }

    "log info and error separately" in {
      todo
    }
  }

  "question mark operator" should {
    "return exit code" in {
      val runner = Runner[IO](log)
      (runner ? Seq("bash", "-c", "exit 0")).unsafeRunSync() === 0
      (runner ? Seq("bash", "-c", "exit 1")).unsafeRunSync() === 1
      (runner ? Seq("bash", "-c", "exit 2")).unsafeRunSync() === 2
    }

    "capture output with processLogger" in {
      val runner = Runner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = ProcessLogger(line => buffer.append(line))
      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() === 0
      buffer.toList === List("abc")
    }
  }

  "getProcessLogger" should {
    "capture stdout" in {
      val runner = Runner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(Some(buffer), None)

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() === 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() === 0

      buffer.toList === List("abc")
    }

    "capture stderr" in {
      val runner = Runner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(None, Some(buffer))

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() === 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() === 0

      buffer.toList === List("xyzzy")
    }

    "join stdout and stderr as needed" in {
      val runner = Runner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(Some(buffer), Some(buffer))

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() === 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() === 0

      buffer.toList === List("abc", "xyzzy")
    }
  }

  "safeEcho" should {
    "replace strings to be hidden" in {
      val runner = Runner[IO](log).hide("xyzzy").hide("foobar")
      Runner.SecretReplacement === "*****"
      runner.safeEcho(Seq("this", "xyzzy", "that", "print_foobar")) === "this ***** that print_*****"
    }
  }

  "quoteIfNeeded" should {
    "quote strings with spaces" in {
      Runner.quoteIfNeeded(" abc") === "' abc'"
      Runner.quoteIfNeeded("abc ") === "'abc '"
      Runner.quoteIfNeeded("a b c") === "'a b c'"
    }
    "quote strings with symbols" in {
      Runner.quoteIfNeeded("!?") === "'!?'"
    }
    "leave words and numbers unquoted" in {
      Runner.quoteIfNeeded("abc") === "abc"
      Runner.quoteIfNeeded("plus4") === "plus4"
      Runner.quoteIfNeeded("foo_bar") === "foo_bar"
    }
    "leave paths unquoted" in {
      Runner.quoteIfNeeded("/home/user/.bashrc") === "/home/user/.bashrc"
      Runner.quoteIfNeeded("C:\\Users") === "C:\\Users"
    }
  }

}
