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

import java.io.File
import java.nio.file.{Files, Path}

import org.specs2.main.CommandLine
import org.specs2.mutable.Specification

import sbt.librarymanagement.ModuleID
import sbt.util.Level
import sbttrickle.metadata.ModuleUpdateData

class SbtPrecogBaseSpec(params: CommandLine) extends Specification {
  val tmpdir: Path = params.value("tmpdir") map { f =>
    val file = new File(f, getClass.getName.replace('.', '/'))
    sbt.io.IO.delete(file)
    assert(file.mkdirs())
    file.toPath.pp("tmpdir: ")
  } getOrElse Files.createTempDirectory(getClass.getSimpleName)

  val module = ModuleID("parent", "module", "0.0.0")
  val org = "org"
  val prj = "dep"
  val version = "2.3.4"
  val repo = s"$org-$prj"
  val dep = ModuleID(org, prj, version)
  val url = s"https://github.com/$org/$prj"

  "updateDependencies" should {
    val base = new SbtPrecogBase() {
      override protected val autoImporter: autoImport = null
    }

    "throw exception for unmanaged versions" in {
      val versions = ManagedVersions(tmpdir.resolve("exception.json"))
      val mud = ModuleUpdateData(module, dep, "2.3.7", repo, url)
      val logger = TestLogger()

      base.updateDependencies("precog-quasar", Set(mud), versions, logger) must throwA[RuntimeException]
    }

    "log 'version: revision'" in {
      val versions = ManagedVersions(tmpdir.resolve("revision.json"))
      val mud = ModuleUpdateData(module, dep, "2.3.7", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-quasar", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: feature'" in {
      val versions = ManagedVersions(tmpdir.resolve("feature.json"))
      val mud = ModuleUpdateData(module, dep, "2.4.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-quasar", Set(mud), versions, logger)

      logger.logs(Level.Info) must not(contain("version: revision"))
      logger.logs(Level.Info) must contain("version: feature")
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: breaking'" in {
      val versions = ManagedVersions(tmpdir.resolve("breaking.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-quasar", Set(mud), versions, logger)

      logger.logs(Level.Info) must not(contain("version: revision"))
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must contain("version: breaking")
    }

    "log 'version: revision' for datasources" in {
      val versions = ManagedVersions(tmpdir.resolve("datasource.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-quasar-datasource-s3", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: revision' for destinations" in {
      val versions = ManagedVersions(tmpdir.resolve("destination.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-quasar-destination-azure", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: revision' for sdbe" in {
      val versions = ManagedVersions(tmpdir.resolve("sdbe.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-sdbe", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: revision' for onprem" in {
      val versions = ManagedVersions(tmpdir.resolve("onprem.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-onprem", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: revision' for electron" in {
      val versions = ManagedVersions(tmpdir.resolve("electron.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-electron", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }

    "log 'version: revision' for slamx" in {
      val versions = ManagedVersions(tmpdir.resolve("slamx.json"))
      val mud = ModuleUpdateData(module, dep, "3.0.0", repo, url)
      val logger = TestLogger()

      versions.update(repo, version)
      base.updateDependencies("precog-slamx", Set(mud), versions, logger)

      logger.logs(Level.Info) must contain("version: revision")
      logger.logs(Level.Info) must not(contain("version: feature"))
      logger.logs(Level.Info) must not(contain("version: breaking"))
    }
  }

}
