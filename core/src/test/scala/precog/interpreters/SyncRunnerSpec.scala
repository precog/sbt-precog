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

package precog.interpreters

import java.io.File
import java.nio.file.{Files, Path}

import org.specs2.main.CommandLine

import cats.effect.IO
import precog.TestLogger
import sbt.file
import sbt.util.{Level, Logger}

import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger

class SyncRunnerSpec(params: CommandLine) extends org.specs2.mutable.Specification {
  val log = Logger.Null
  val tmpdir: Path = params.value("tmpdir") map { f =>
    val file = new File(f, getClass.getName.replace('.', '/'))
    sbt.io.IO.delete(file)
    assert(file.mkdirs())
    file.toPath.pp("tmpdir: ")
  } getOrElse Files.createTempDirectory(getClass.getSimpleName).pp("tmpdir: ")

  "Fluent builders" should {
    "be equivalent to case class build" in {
      val runner = SyncRunner[IO](log)
      runner mustEqual SyncRunner[IO](log, false, None, Map.empty, Seq.empty)
      runner.stderrToStdout mustEqual SyncRunner[IO](log, merge = true)
      runner.cd(file("./target")) mustEqual SyncRunner[IO](log, workingDir = Some(file("./target")))
      runner.withEnv("X" -> "Y", "W" -> "Z") mustEqual SyncRunner[IO](log, env = Map("X" -> "Y", "W" -> "Z"))
      runner.hide("password") mustEqual SyncRunner[IO](log, hide = Seq("password"))
    }

    "append instead of replace" in {
      SyncRunner[IO](log).withEnv("X" -> "Y").withEnv("W" -> "Z") mustEqual SyncRunner[IO](log, env = Map("X" -> "Y", "W" -> "Z"))
      SyncRunner[IO](log).hide("xyzzy").hide("foobar") mustEqual SyncRunner[IO](log, hide = Seq("xyzzy", "foobar"))
    }
  }

  "exclamation mark operator" should {
    "return captured output" in {
      val runner = SyncRunner[IO](log)
      (runner ! Seq("bash", "-c", "echo 'abc'")).unsafeRunSync() mustEqual List("abc")
    }

    "break string by spaces" in {
      val runner = SyncRunner[IO](log)
      (runner ! "printf %s%d abc 5").unsafeRunSync() mustEqual List("abc5")
    }

    "respect working directory" in {
      val runner = SyncRunner[IO](log).cd(tmpdir.toFile)
      (runner ! "pwd").unsafeRunSync().head mustEqual tmpdir.toString
    }

    "raise exception on errors" in {
      val runner = SyncRunner[IO](log)
      (runner ! Seq("bash", "-c", "exit 1")).unsafeRunSync() must throwA[RuntimeException]("bash -c exit code 1")
    }

    "hide stuff" in {
      val runner = SyncRunner[IO](log).hide("xyzzy")
      (runner ! "printf abc%sdef xyzzy").unsafeRunSync() mustEqual List("abc*****def")
    }.pendingUntilFixed("must capture logger")

    "pass environment variables" in {
      val runner = SyncRunner[IO](log).withEnv("X" -> "Y")
      (runner ! Seq("bash", "-c", "echo $X")).unsafeRunSync() mustEqual List("Y")
    }

    "merge log" in {
      val log = TestLogger()
      val runner = SyncRunner[IO](log).stderrToStdout
      val cmd1 = Seq("bash", "-c", "echo 'normal log'")
      val cmd2 = Seq("bash", "-c", "echo >&2 'error log'")
      val p = for {
        _ <- runner ! cmd1
        _ <- runner ! cmd2
      } yield ()
      p.unsafeRunSync()

      log.logs(Level.Info).toList mustEqual List(
        SyncRunner.safeEcho(cmd1, Nil),
        "normal log",
        SyncRunner.safeEcho(cmd2, Nil),
        "error log")
      log.logs(Level.Error).toList mustEqual Nil
    }

    "log info and error separately" in {
      val log = TestLogger()
      val runner = SyncRunner[IO](log)
      val cmd1 = Seq("bash", "-c", "echo 'normal log'")
      val cmd2 = Seq("bash", "-c", "echo >&2 'error log'")
      val p = for {
        _ <- runner ! cmd1
        _ <- runner ! cmd2
      } yield ()
      p.unsafeRunSync()

      log.logs(Level.Info).toList mustEqual List(
        SyncRunner.safeEcho(cmd1, Nil),
        "normal log",
        SyncRunner.safeEcho(cmd2, Nil))
      log.logs(Level.Error).toList mustEqual List("error log")
    }
  }

  "question mark operator" should {
    "return exit code" in {
      val runner = SyncRunner[IO](log)
      (runner ? Seq("bash", "-c", "exit 0")).unsafeRunSync() mustEqual 0
      (runner ? Seq("bash", "-c", "exit 1")).unsafeRunSync() mustEqual 1
      (runner ? Seq("bash", "-c", "exit 2")).unsafeRunSync() mustEqual 2
    }

    "capture output with processLogger" in {
      val runner = SyncRunner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = ProcessLogger(line => buffer.append(line))
      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() mustEqual 0
      buffer.toList mustEqual List("abc")
    }
  }

  "getProcessLogger" should {
    "capture stdout" in {
      val runner = SyncRunner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(Some(buffer), None)

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() mustEqual 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() mustEqual 0

      buffer.toList mustEqual List("abc")
    }

    "capture stderr" in {
      val runner = SyncRunner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(None, Some(buffer))

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() mustEqual 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() mustEqual 0

      buffer.toList mustEqual List("xyzzy")
    }

    "join stdout and stderr as needed" in {
      val runner = SyncRunner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = runner.getProcessLogger(Some(buffer), Some(buffer))

      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() mustEqual 0
      (runner ? (Seq("bash", "-c", "echo >&2 'xyzzy'"), plog)).unsafeRunSync() mustEqual 0

      buffer.toList mustEqual List("abc", "xyzzy")
    }
  }

  "safeEcho" should {
    "replace strings to be hidden" in {
      val input = Seq("this", "xyzzy", "that", "print_foobar")
      val hide = List("xyzzy", "foobar")
      SyncRunner.SecretReplacement mustEqual "*****"
      SyncRunner.safeEcho(input, hide) mustEqual "this ***** that print_*****"
    }
  }

  "quoteIfNeeded" should {
    "quote strings with spaces" in {
      SyncRunner.quoteIfNeeded(" abc") mustEqual "' abc'"
      SyncRunner.quoteIfNeeded("abc ") mustEqual "'abc '"
      SyncRunner.quoteIfNeeded("a b c") mustEqual "'a b c'"
    }
    "quote strings with symbols" in {
      SyncRunner.quoteIfNeeded("!?") mustEqual "'!?'"
    }
    "leave words and numbers unquoted" in {
      SyncRunner.quoteIfNeeded("abc") mustEqual "abc"
      SyncRunner.quoteIfNeeded("plus4") mustEqual "plus4"
      SyncRunner.quoteIfNeeded("foo_bar") mustEqual "foo_bar"
    }
    "leave paths unquoted" in {
      SyncRunner.quoteIfNeeded("/home/user/.bashrc") mustEqual "/home/user/.bashrc"
      SyncRunner.quoteIfNeeded("C:\\Users") mustEqual "C:\\Users"
    }
  }

}
