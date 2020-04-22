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

import java.nio.file.Files

import cats.effect.Sync
import cats.implicits._
import precog.algebras.Runner
import precog.algebras.Runner.{DefaultConfig, RunnerConfig, RunnerException}
import sbt.util.Logger

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

final case class SyncRunner[F[_] : Sync](log: Logger, config: RunnerConfig = DefaultConfig) extends Runner[F] {
  import SyncRunner._

  override def withConfig(config: RunnerConfig): Runner[F] = SyncRunner(log, config)

  override def cdTemp(prefix: String): F[RunnerConfig] = {
    for {
      tempDir <- Sync[F].delay(Files.createTempDirectory(prefix))
    } yield config.copy(cwd = Some(tempDir.toFile))
  }

  def !(command: String): F[List[String]] = {
    this ! command.split("""\s+""").toVector
  }

  def !!(command: String): F[List[String]] = {
    this !! command.split("""\s+""").toVector
  }

  def !(command: Seq[String]): F[List[String]] = {
    this.run(command, merge = false)
  }

  def !!(command: Seq[String]): F[List[String]] = {
    this.run(command, merge = true)
  }

  def run(command: Seq[String], merge: Boolean): F[List[String]] = {
    val F = implicitly[Sync[F]]
    for {
      stdout <- F.pure(mutable.Buffer[String]())
      stderr <- F.pure(mutable.Buffer[String]())
      processLogger <- F.pure(getProcessLogger(Some(stdout), Some(stderr), merge))
      _ <- (this ? (command, processLogger))
        .ensureOr(RunnerException(_, command.toList, stderr.toList)) (_ == 0)
    } yield stdout.toList
  }

  def ?(command: Seq[String], processLogger: ProcessLogger): F[Int] = {
    val F = implicitly[Sync[F]]
    for {
      _ <- F.delay(log.info(config.sanitize(command.map(quoteIfNeeded).mkString(" "))))
      exitCode <- F.delay(Process(command, config.cwd, config.env.toSeq: _*) ! processLogger)
    } yield exitCode
  }

  def getProcessLogger(
      out: Option[mutable.Buffer[String]] = None,
      err: Option[mutable.Buffer[String]] = None,
      merge: Boolean = false)
      : ProcessLogger = {
    val stdout = { line: String =>
      log.info(line)
      out.foreach(_.append(line))
    }.compose(config.sanitize)
    val stderr = { line: String =>
      if (merge) {
        log.info(line)
        out.foreach(_.append(line))
      } else {
        log.error(line)
      }
      err.foreach(_.append(line))
    }.compose(config.sanitize)
    ProcessLogger(stdout, stderr)
  }
}

object SyncRunner {
  def quoteIfNeeded(s: String): String = {
    if (s.matches("[-\\w/\\\\:.]+")) s
    else if (s.contains("'")) f"$$'${s.map(c => if (c == '\'') "\\'" else c.toString).mkString}%s'"
    else f"'$s%s'"
  }
}

