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
import precog.algebras.Runner
import precog.algebras.Runner.{RunnerConfig, RunnerException}
import sbt.util.{Level, Logger}

import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger

class SyncRunnerSpec(params: CommandLine) extends org.specs2.mutable.Specification {
  val log = Logger.Null
  val runner: Runner[IO] = SyncRunner[IO](log)
  val tmpdir: Path = params.value("tmpdir") map { f =>
    val file = new File(f, getClass.getName.replace('.', '/'))
    sbt.io.IO.delete(file)
    assert(file.mkdirs())
    file.toPath.pp("tmpdir: ")
  } getOrElse Files.createTempDirectory(getClass.getSimpleName)
      .toFile.getCanonicalFile.toPath.pp("tmpdir: ")
  val config: RunnerConfig = Runner.DefaultConfig

  "exclamation mark operator" should {
    "return captured output" in {
      (runner ! Seq("bash", "-c", "echo 'abc'")).unsafeRunSync() mustEqual List("abc")
    }

    "break string by spaces" in {
      (runner ! "printf %s%d abc 5").unsafeRunSync() mustEqual List("abc5")
    }

    "respect working directory" in {
      val cdRunner = runner.withConfig(config.cd(tmpdir.toFile))
      (cdRunner ! "pwd").unsafeRunSync().head mustEqual tmpdir.toString
    }

    "raise exception on errors" in {
      (runner ! Seq("bash", "-c", "exit 1")).unsafeRunSync() must throwA[RunnerException]("bash -c exit code 1")
    }

    "hide stuff" in {
      val hideRunner = runner.withConfig(config.hide("xyzzy"))
      (hideRunner ! "printf abc%sdef xyzzy").unsafeRunSync() mustEqual List("abc*****def")
    }

    "hide stuff sent to stdout in the log" in {
      val log = TestLogger()
      val loggingRunner = SyncRunner[IO](log).withConfig(config.hide("xyzzy"))
      (loggingRunner ! "printf abc%sdef xyzzy").unsafeRunSync()
      log.logs(Level.Info) mustEqual List("printf 'abc%sdef' *****", "abc*****def")
    }

    "hide stuff sent to stderr in the log" in {
      val errorLog = TestLogger()
      val errorRunner = SyncRunner[IO](errorLog).withConfig(config.hide("xyzzy"))
      (errorRunner ! Seq("bash", "-c", "printf  >&2 abc%sdef xyzzy")).unsafeRunSync()
      errorLog.logs(Level.Error) mustEqual List("abc*****def")
    }

    "pass environment variables" in {
      val envRunner = runner.withConfig(config.withEnv("X" -> "Y"))
      (envRunner ! Seq("bash", "-c", "echo $X")).unsafeRunSync() mustEqual List("Y")
    }

    "log info and error separately" in {
      val log = TestLogger()
      val loggingRunner = SyncRunner[IO](log)
      val cmd1 = Seq("bash", "-c", "echo 'normal log'")
      val cmd2 = Seq("bash", "-c", "echo >&2 'error log'")
      val p = for {
        _ <- loggingRunner ! cmd1
        _ <- loggingRunner ! cmd2
      } yield ()
      p.unsafeRunSync()

      log.logs(Level.Info).toList mustEqual List(
        cmd1.map(SyncRunner.quoteIfNeeded).mkString(" "),
        "normal log",
        cmd2.map(SyncRunner.quoteIfNeeded).mkString(" "))
      log.logs(Level.Error).toList mustEqual List("error log")
    }
  }

  "double exclamation mark operator" should {
    "return captured output" in {
      (runner !! Seq("bash", "-c", "echo 'abc'")).unsafeRunSync() mustEqual List("abc")
    }

    "break string by spaces" in {
      (runner !! "printf %s%d abc 5").unsafeRunSync() mustEqual List("abc5")
    }

    "respect working directory" in {
      val cdRunner = runner.withConfig(config.cd(tmpdir.toFile))
      (cdRunner !! "pwd").unsafeRunSync().head mustEqual tmpdir.toString
    }

    "raise exception on errors" in {
      (runner !! Seq("bash", "-c", "exit 1")).unsafeRunSync() must throwA[RunnerException]("bash -c exit code 1")
    }

    "hide stuff" in {
      val hideRunner = runner.withConfig(config.hide("xyzzy"))
      (hideRunner !! "printf abc%sdef xyzzy").unsafeRunSync() mustEqual List("abc*****def")
      (hideRunner !! Seq("bash", "-c", "printf >&2 abc%sdef xyzzy")).unsafeRunSync() mustEqual List("abc*****def")
    }

    "hide stuff sent to stdout in the log" in {
      val log = TestLogger()
      val loggingRunner = SyncRunner[IO](log).withConfig(config.hide("xyzzy"))
      (loggingRunner !! "printf abc%sdef xyzzy").unsafeRunSync()
      log.logs(Level.Info) mustEqual List("printf 'abc%sdef' *****", "abc*****def")
    }

    "hide stuff sent to stderr in the log" in {
      val errorLog = TestLogger()
      val errorRunner = SyncRunner[IO](errorLog).withConfig(config.hide("xyzzy"))
      (errorRunner !! Seq("bash", "-c", "printf >&2 abc%sdef xyzzy")).unsafeRunSync()
      errorLog.logs(Level.Info) mustEqual List("bash -c 'printf >&2 abc%sdef *****'", "abc*****def")
    }

    "pass environment variables" in {
      val envRunner = runner.withConfig(config.withEnv("X" -> "Y"))
      (envRunner !! Seq("bash", "-c", "echo $X")).unsafeRunSync() mustEqual List("Y")
    }

    "merge log" in {
      val log = TestLogger()
      val loggingRunner = SyncRunner[IO](log)
      val cmd1 = Seq("bash", "-c", "echo 'normal log'")
      val cmd2 = Seq("bash", "-c", "echo >&2 'error log'")
      val p = for {
        _ <- loggingRunner !! cmd1
        _ <- loggingRunner !! cmd2
      } yield ()
      p.unsafeRunSync()

      log.logs(Level.Info).toList mustEqual List(
        cmd1.map(SyncRunner.quoteIfNeeded).mkString(" "),
        "normal log",
        cmd2.map(SyncRunner.quoteIfNeeded).mkString(" "),
        "error log")
      log.logs(Level.Error).toList mustEqual Nil
    }
  }


  "question mark operator" should {
    "return exit code" in {
      (runner ? (Seq("bash", "-c", "exit 0"), ProcessLogger(_ => ()))).unsafeRunSync() mustEqual 0
      (runner ? (Seq("bash", "-c", "exit 1"), ProcessLogger(_ => ()))).unsafeRunSync() mustEqual 1
      (runner ? (Seq("bash", "-c", "exit 2"), ProcessLogger(_ => ()))).unsafeRunSync() mustEqual 2
    }

    "capture output with processLogger" in {
      val runner = SyncRunner[IO](log)
      val buffer = collection.mutable.Buffer[String]()
      val plog = ProcessLogger(line => buffer.append(line))
      (runner ? (Seq("bash", "-c", "echo 'abc'"), plog)).unsafeRunSync() mustEqual 0
      buffer.toList mustEqual List("abc")
    }
  }

  "cdTemp" should {
    "return a runner in a new directory" in {
      val p = for {
        tempConf <- runner.cdTemp("testPrefix")
        output <- runner.withConfig(tempConf) ! "pwd"
      } yield output

      val path = new File(p.unsafeRunSync().mkString)
      path.exists() must beTrue
      path.isDirectory must beTrue
      path.getName must be startingWith("testPrefix")
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
