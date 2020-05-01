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

import sbt._
import librarymanagement.{ModuleFilter, ModuleDescriptor, ScalaModuleInfo}

final case class UnsafeEvictionsException(prefix: String, evictions: Seq[EvictionPair])
    extends RuntimeException(s"$prefix: ${evictions.map(e => s"${e.organization}:${e.name}").mkString(", ")}")

object UnsafeEvictions {
  /** Performs logging and exception-throwing given report and configurations */
  def check(currentProject: String,
      module: ModuleDescriptor,
      isFatal: Boolean,
      conf: Seq[(ModuleFilter, VersionNumberCompatibility)],
      evictionWarningOptions: EvictionWarningOptions,
      report: UpdateReport,
      log: Logger): UpdateReport = {
    import sbt.util.ShowLines._

    val ewo = evictionWarningOptions.withGuessCompatible(guessCompatible(conf))
    val ew = EvictionWarning(module, ewo, report)
    val logLevel = if (isFatal) Level.Error else Level.Warn
    ew.lines.foreach(line => log.log(logLevel, s"[${currentProject}] $line"))
    if (isFatal && ew.binaryIncompatibleEvictionExists) {
      val evictions = ew.scalaEvictions ++ ew.directEvictions ++ ew.transitiveEvictions
       throw UnsafeEvictionsException("Unsafe evictions detected", evictions)
    }
    report
  }

  /** Applies compatibility configuration, and, otherwise, assume it's compatible */
  private def guessCompatible(confs: Seq[(ModuleFilter, VersionNumberCompatibility)])
      : ((ModuleID, Option[ModuleID], Option[ScalaModuleInfo])) => Boolean = {
    case (m1, Some(m2), _) =>
      confs.find(conf => conf._1(m1)) forall {
        case (_, vnc) =>
          val r1 = VersionNumber(m1.revision)
          val r2 = VersionNumber(m2.revision)
          vnc.isCompatible(r1, r2)
      }
    case _                 => true
  }

  /** Creates a ModuleFilter that does a strict organization matching */
  trait IsOrg {
    import sbt.librarymanagement.DependencyFilter

    /** Creates a ModuleFilter that does a strict organization matching */
    def apply(org: String): ModuleFilter = DependencyFilter.fnToModuleFilter(_.organization == org)
  }

  object IsOrg extends IsOrg
}

