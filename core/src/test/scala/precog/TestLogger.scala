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

package precog

import scala.collection.immutable.Map
import scala.collection.mutable.Buffer

import sbt.util.Level
import sbt.util.Logger

//noinspection ReferenceMustBePrefixed
class TestLogger extends Logger {
  val exceptions: Buffer[Throwable] = Buffer.empty
  val successes: Buffer[String] = Buffer.empty
  val logs: Map[Level.Value, Buffer[String]] = Level.values.map(_ -> Buffer.empty[String]).toMap

  override def trace(t: => Throwable): Unit = exceptions.append(t)
  override def success(message: => String): Unit = successes.append(message)
  override def log(level: Level.Value, message: => String): Unit = logs(level).append(message)
}

object TestLogger {
  def apply() = new TestLogger
}
