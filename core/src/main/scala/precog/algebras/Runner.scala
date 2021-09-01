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

import java.io.File
import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger

import precog.algebras.Runner.RunnerConfig

/**
 * Executes external commands inside an effect.
 *
 * @tparam F
 *   Effect monad inside which to run the commands.
 */
trait Runner[F[_]] {

  /**
   * Get a new runner that uses the provided configuration.
   */
  def withConfig(config: RunnerConfig): Runner[F]

  /**
   * Creates a temporary directory and returns a copy of the configuration with it as current
   * working directory.
   *
   * @param prefix
   *   Prefix used on temporary directory name
   * @return
   *   A new runner with this property.
   */
  def cdTemp(prefix: String): F[RunnerConfig]

  /**
   * Split `command` by spaces and execute it as a command and its arguments, returning the
   * standard output.
   *
   * There's no shell processing, so do not try to use quotes or escapes to allow spaces inside
   * a single argument.
   *
   * Errors will be reported as [[Runner.RunnerException]] in the error channel of F.
   *
   * @param command
   *   A string in the format `command arg1 arg2 ... argn`
   * @return
   *   The stdout and stderr joined as multiple lines.
   */
  def !(command: String): F[List[String]]

  /**
   * Split `command` by spaces and execute it as a command and its arguments, returning the
   * standard output and error mixed together.
   *
   * There's no shell processing, so do not try to use quotes or escapes to allow spaces inside
   * a single argument.
   *
   * Errors will be reported as [[Runner.RunnerException]] in the error channel of F.
   *
   * @param command
   *   A string in the format `command arg1 arg2 ... argn`
   * @return
   *   The stdout and stderr joined as multiple lines.
   */
  def !!(command: String): F[List[String]]

  /**
   * Execute an external command `command.head`, with arguments `command.tail`, returning the
   * standard output.
   *
   * Errors will be reported as [[Runner.RunnerException]] in the error channel of F.
   *
   * @param command
   *   A sequence composed of a command and its arguments
   * @return
   *   The stdout and stderr joined as multiple lines.
   */
  def !(command: Seq[String]): F[List[String]]

  /**
   * Execute an external command `command.head`, with arguments `command.tail`, returning the
   * standard output and error mixed together.
   *
   * Errors will be reported as [[Runner.RunnerException]] in the error channel of F.
   *
   * @param command
   *   A sequence composed of a command and its arguments
   * @return
   *   The stdout and stderr joined as multiple lines.
   */
  def !!(command: Seq[String]): F[List[String]]

  /**
   * Execute an external command `command.head`, with arguments `command.tail`, returning the
   * exit code.
   *
   * @param command
   *   A sequence composed of a command and its arguments
   * @param processLogger
   *   Logger that will consume the stdout and stderr
   * @return
   *   The command's exit code.
   */
  def ?(command: Seq[String], processLogger: ProcessLogger): F[Int]
}

object Runner {
  val DefaultSecretReplacement = "*****"

  final case class RunnerConfig(
      cwd: Option[File],
      env: Map[String, String],
      hide: List[String],
      replacement: String) {

    /**
     * Executes commands with current work directory set to `cwd`.
     *
     * @param cwd
     *   New current work directory
     * @return
     *   A new runner with this property.
     */
    def cd(cwd: File): RunnerConfig = copy(cwd = Some(cwd))

    /**
     * Appends variables to the execution environment. Calling it multiple times will not remove
     * existing variables.
     *
     * @param vars
     *   Pairs of variable name and value
     * @return
     *   A new runner with this property.
     */
    def withEnv(vars: (String, String)*): RunnerConfig = copy(env = env ++ vars.toMap)

    /**
     * Appends secret to be hidden. Calling it multiple times will not remove previous secrets.
     *
     * @param secret
     *   A string that will be hidden from the output.
     * @return
     *   A new runner with this property.
     */
    def hide(secret: String): RunnerConfig = copy(hide = secret :: hide)

    /**
     * Sanitize `line` by replacing all strings from `hide` with `replacement`.
     *
     * @param line
     *   String to be sanitized
     * @return
     *   Sanitized string.
     */
    def sanitize(line: String): String = {
      hide.foldLeft(line)(_.replaceAllLiterally(_, replacement))
    }
  }

  /**
   * Default configuration for Runner:
   *
   *   - No current work directory;
   *   - No environment variables;
   *   - No strings to hide.
   */
  val DefaultConfig: RunnerConfig = RunnerConfig(None, Map.empty, Nil, DefaultSecretReplacement)

  final case class RunnerException(exitCode: Int, cmd: List[String], stderr: List[String])
      extends RuntimeException(f"${cmd.take(2).mkString(" ")}%s exit code $exitCode%d")

  def apply[F[_]](config: RunnerConfig)(implicit runner: Runner[F]): Runner[F] =
    runner.withConfig(config)
}
