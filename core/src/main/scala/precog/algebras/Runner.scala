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

package precog.algebras

import java.io.File

import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger

/**
 * Executes external commands inside an effect.
 *
 * @tparam F Effect monad inside which to run the commands.
 */
trait Runner[F[_]] {

  /**
   * Send stderr output to stdout when executing command. Does not affect
   * return values, however -- these are always joined.
   *
   * @return A new runner with this property.
   */
  def stderrToStdout: Runner[F]

  /**
   * Executes commands with current work directory set to `workingDir`.
   *
   * @param workingDir New current work directory
   * @return A new runner with this property.
   */
  def cd(workingDir: File): Runner[F]

  /**
   * Appends variables to the execution environment. Calling it multiple
   * times will not remove existing variables.
   *
   * @param vars Pairs of variable name and value
   * @return A new runner with this property.
   */
  def withEnv(vars: (String, String)*): Runner[F]

  /**
   * Appends secret to be hidden. Calling it multiple times will not
   * remove previous secrets.
   *
   * This will not change the returned output.
   *
   * @param secret A string that will be hidden when logging.
   * @return A new runner with this property.
   */
  def hide(secret: String): Runner[F]

  /**
   * Split `command` by spaces and execute it as a command and its arguments,
   * returning the output.
   *
   * There's no shell processing, so do not try to use quotes or escapes to
   * allow spaces inside a single argument.
   *
   * Errors will be reported in whatever way supported by `F`.
   *
   * @param command A string in the format `command arg1 arg2 ... argn`
   * @return The stdout and stderr joined as multiple lines.
   */
  def !(command: String): F[List[String]]

  /**
   * Execute an external command `command.head`, with arguments `command.tail`,
   * returning the output.
   *
   * Errors will be reported in whatever way supported by `F`.
   *
   * @param command A sequence composed of a command and its arguments
   * @return The stdout and stderr joined as multiple lines.
   */
  def !(command: Seq[String]): F[List[String]]

  /**
   * Execute an external command `command.head`, with arguments `command.tail`,
   * returning the exit code.
   *
   * This command is not affected by `stderrToStdout`.
   *
   * @param command A sequence composed of a command and its arguments
   * @param processLogger Logger that will consume the stdout and stderr
   * @return The command's exit code.
   */
  def ?(command: Seq[String], processLogger: ProcessLogger): F[Int]
}
