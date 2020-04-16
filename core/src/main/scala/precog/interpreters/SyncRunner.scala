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

import cats.effect.Sync
import cats.implicits._
import precog.algebras.Runner
import sbt.util.Logger

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

final case class SyncRunner[F[_] : Sync](
    log: Logger,
    merge: Boolean = false,
    workingDir: Option[File] = None,
    env: Map[String, String] = Map.empty,
    hide: Seq[String] = Seq.empty)
    extends Runner[F] {
  import SyncRunner._

  def stderrToStdout: SyncRunner[F] = copy(merge = true)
  def cd(workingDir: File): SyncRunner[F] = copy(workingDir = Some(workingDir))
  def withEnv(vars: (String, String)*): SyncRunner[F] = copy(env = env ++ vars.toMap)
  def hide(secret: String): SyncRunner[F] = copy(hide = hide :+ secret)

  def !(command: String): F[List[String]] = {
    this ! command.split("""\s+""").toVector
  }

  def !(command: Seq[String]): F[List[String]] = {
    val F = implicitly[Sync[F]]
    for {
      lines <- F.pure(mutable.Buffer[String]())
      processLogger <- F.pure(getProcessLogger(Some(lines), Some(lines)))
      res <- (this ? (command, processLogger))
        .ensureOr(res => new RuntimeException(f"${command.take(2).mkString(" ")}%s exit code $res%d"))(_ == 0)
    } yield lines.toList
  }

  def ?(command: Seq[String], processLogger: ProcessLogger = getProcessLogger()): F[Int] = {
    val F = implicitly[Sync[F]]
    for {
      _ <- F.delay(log.info(safeEcho(command, hide)))
      exitCode <- F.delay(Process(command, workingDir, env.toSeq: _*) ! processLogger)
    } yield exitCode
  }

  def getProcessLogger(
      out: Option[mutable.Buffer[String]] = None,
      err: Option[mutable.Buffer[String]] = None)
      : ProcessLogger = {
    val stdout = { line: String =>
      log.info(line)
      out.foreach(_.append(line))
    }
    val stderr = { line: String =>
      if (merge) log.info(line) else log.error(line)
      err.foreach(_.append(line))
    }
    ProcessLogger(stdout, stderr)
  }
}

object SyncRunner {
  val SecretReplacement = "*****"

  def safeEcho(command: Seq[String], hide: Seq[String]): String =  {
    val commandLine = command.map(quoteIfNeeded).mkString(" ")
    val safeCommandLine = hide.foldLeft(commandLine)(_.replaceAllLiterally(_, SecretReplacement))
    safeCommandLine
  }

  def quoteIfNeeded(s: String): String = {
    if (s.matches("[-\\w/\\\\:.]+")) s
    else if (s.contains("'")) f"$$'${s.map(c => if (c == '\'') "\\'" else c.toString).mkString}%s'"
    else f"'$s%s'"
  }
}

