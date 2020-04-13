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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE

import org.yaml.snakeyaml.Yaml

import _root_.io.crashbox.gpg.SbtGpg
import de.heikoseeberger.sbtheader.AutomateHeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import sbt.Def.Initialize
import sbt.Keys._
import sbt.complete.DefaultParsers.fileParser
import sbt.{Def, _}
import sbtghactions.GenerativeKeys._
import sbtghactions.GitHubActionsPlugin.autoImport._
import sbtghactions.{GitHubActionsPlugin, Ref, RefPredicate, WorkflowJob, WorkflowStep}
import sbttrickle.TricklePlugin
import sbttrickle.TricklePlugin.autoImport._
import sbttrickle.metadata.ModuleUpdateData

import scala.collection.JavaConverters._
import scala.collection.immutable.{Seq, Set}
import scala.sys.process._

abstract class SbtPrecogBase extends AutoPlugin {
  private var foundLocalEvictions: Set[(String, String)] = Set()
  val VersionsPath = ".versions.json"

  private lazy val internalPublishAsOSSProject = settingKey[Boolean]("Internal proxy to lift the scoping on publishAsOSSProject")

  override def requires =
    plugins.JvmPlugin &&
    GitHubActionsPlugin &&
    SbtGpg &&
    TricklePlugin

  override def trigger = allRequirements

  class autoImport extends SbtPrecogKeys {
    val BothScopes = "test->test;compile->compile"

    // Exclusive execution settings
    lazy val ExclusiveTests = config("exclusive") extend Test

    val ExclusiveTest = Tags.Tag("exclusive-test")

    def exclusiveTasks(tasks: Scoped*) =
      tasks.flatMap(inTask(_)(tags := Seq((ExclusiveTest, 1))))

    def scalacOptions_2_10(strict: Boolean): Seq[String] = {
      val global = Seq(
        "-encoding", "UTF-8",
        "-deprecation",
        "-language:existentials",
        "-language:higherKinds",
        "-language:implicitConversions",
        "-feature",
        "-Xlint")

      if (strict) {
        global ++ Seq(
          "-unchecked",
          "-Xfuture",
          "-Yno-adapted-args",
          "-Yno-imports",
          "-Ywarn-dead-code",
          "-Ywarn-numeric-widen",
          "-Ywarn-value-discard")
      } else {
        global
      }
    }

    def scalacOptions_2_11(strict: Boolean): Seq[String] = {
      val global = Seq(
        "-Ypartial-unification",
        "-Ywarn-unused-import")

      if (strict)
        global :+ "-Ydelambdafy:method"
      else
        global
    }

    def scalacOptions_2_12(strict: Boolean): Seq[String] = Seq("-target:jvm-1.8")

    def scalacOptions_2_13(strict: Boolean): Seq[String] = {
      val numCPUs = java.lang.Runtime.getRuntime.availableProcessors()
      Seq(
        s"-Ybackend-parallelism", numCPUs.toString,
        "-Wunused:imports",
        "-Wdead-code",
        "-Wnumeric-widen",
        "-Wvalue-discard")
    }

    val scalacOptionsRemoved_2_13: Seq[String] =
      Seq(
        "-Yno-adapted-args",
        "-Ywarn-unused-import",
        "-Ywarn-value-discard",
        "-Ywarn-numeric-widen",
        "-Ywarn-dead-code",
        "-Xfuture")

    val headerLicenseSettings: Seq[Def.Setting[_]] = Seq(
      headerLicense := Some(HeaderLicense.ALv2("2020", "Precog Data")),
      licenses += (("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0"))),
      checkHeaders := {
        if ((headerCreate in Compile).value.nonEmpty) sys.error("headers not all present")
      })

    lazy val commonBuildSettings: Seq[Def.Setting[_]] = Seq(
      outputStrategy := Some(StdoutOutput),
      autoCompilerPlugins := true,
      autoAPIMappings := true,

      addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.0" cross CrossVersion.full),
      addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),

      // default to true
      scalacStrictMode := true,

      scalacOptions ++= {
        val strict = scalacStrictMode.value

        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, 13)) =>
            val mainline = scalacOptions_2_10(strict) ++
              scalacOptions_2_11(strict) ++
              scalacOptions_2_12(strict) ++
              scalacOptions_2_13(strict)

            mainline.filterNot(scalacOptionsRemoved_2_13.contains)

          case Some((2, 12)) => scalacOptions_2_10(strict) ++ scalacOptions_2_11(strict) ++ scalacOptions_2_12(strict)

          case Some((2, 11)) => scalacOptions_2_10(strict) ++ scalacOptions_2_11(strict)

          case _ => scalacOptions_2_10(strict)
        }
      },

      scalacOptions --= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, n)) if n >= 13 => Some("-Ypartial-unification")
          case _ => None
        }
      },

      scalacOptions ++= {
        if (githubIsWorkflowBuild.value && scalacStrictMode.value)
          Seq("-Xfatal-warnings")
        else
          Seq()
      },

      scalacOptions in (Test, console) --= Seq(
        "-Yno-imports",
        "-Ywarn-unused-import"),

      scalacOptions in (Compile, doc) -= "-Xfatal-warnings",

      unsafeEvictionsCheck := unsafeEvictionsCheckTask.value,
    ) ++ headerLicenseSettings

    lazy val commonPublishSettings: Seq[Def.Setting[_]] = Seq(
      licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),

      publishAsOSSProject := true,
      performMavenCentralSync := false,

      synchronizeWithSonatypeStaging := {},
      releaseToMavenCentral := {},
      autoAPIMappings := true,

      developers := List(
        Developer(
          id = "precog",
          name = "Precog Inc.",
          email = "contact@precog.com",
          url = new URL("http://precog.com")
        )))

    lazy val githubActionsSettings: Seq[Def.Setting[_]] = Seq(
      githubWorkflowSbtCommand := s"$$SBT",

      githubWorkflowEnv := Map(
        "SBT" -> "./sbt",
        "REPO_SLUG" -> s"$${{ github.repository }}",
        "ENCRYPTION_PASSWORD" -> s"$${{ secrets.ENCRYPTION_PASSWORD }}",
        "GITHUB_ACTOR" -> "precog-bot",
        "GITHUB_TOKEN" -> s"$${{ secrets.GITHUB_TOKEN }}"),

      // we don't want to redundantly build other pushed branches
      githubWorkflowTargetBranches := Seq("master", "backport/v*"),

      githubWorkflowBuildPreamble +=
        WorkflowStep.Sbt(
          List("transferCommonResources", "exportSecretsForActions"),
          name = Some("Common sbt setup"),
          cond = Some("env.ENCRYPTION_PASSWORD != null")),

      githubWorkflowBuild := WorkflowStep.Sbt(List("ci")),

      githubWorkflowPublishPreamble ++= Seq(
        WorkflowStep.Use(
          "actions",
          "setup-ruby",
          1,
          name = Some("Install Ruby"),
          params = Map("ruby-version" -> "2.6")),

        WorkflowStep.Sbt(
          List("transferCommonResources", "transferPublishAndTagResources", "exportSecretsForActions"),
          name = Some("Common sbt setup")),

        WorkflowStep.Run(List("./scripts/commonSetup"))),

      githubWorkflowPublish := WorkflowStep.Run(
        List(s"./scripts/publishAndTag $${{ github.repository }}"),
        name = Some("Publish artifacts and create tag")),

      githubWorkflowPublishTargetBranches += RefPredicate.StartsWith(Ref.Branch("backport/v")),

      githubWorkflowAddedJobs += WorkflowJob(
        "auto-merge",
        "Auto Merge",
        List(
          WorkflowStep.Checkout,
          WorkflowStep.SetupScala,
          WorkflowStep.Use(
            "actions",
            "setup-ruby",
            1,
            name = Some("Install Ruby"),
            params = Map("ruby-version" -> "2.6")),
          WorkflowStep.Sbt(
            List("transferCommonResources", "exportSecretsForActions"),
            name = Some("Common sbt setup")),
          WorkflowStep.Run(List("./scripts/commonSetup")),
          WorkflowStep.ComputeVar("CLONE_DIR", "mktemp -d /tmp/precog-bump.XXXXXXXX"),
          WorkflowStep.ComputeVar("PR_NUMBER", s"echo $$GITHUB_REF | cut -d'/' -f3"),
          WorkflowStep.Run(List("./scripts/checkAndAutoMerge"))),
        cond = Some("github.event_name == 'pull_request' && contains(github.head_ref, 'version-bump')"),
        needs = List("build"),
        scalas = List(scalaVersion.value)))

    implicit final class ProjectSyntax(val self: Project) {
      def evictToLocal(envar: String, subproject: String, test: Boolean = false): Project = {
        val eviction = sys.env.get(envar).map(file).filter(_.exists()) map { f =>
          foundLocalEvictions += ((envar, subproject))

          val ref = ProjectRef(f, subproject)
          self.dependsOn(if (test) ref % "test->test;compile->compile" else ref)
        }

        eviction.getOrElse(self)
      }
    }
  }

  protected val autoImporter: autoImport
  import autoImporter._

  override def globalSettings: scala.Seq[Def.Setting[_]] =
    Seq(
      internalPublishAsOSSProject := false,

      concurrentRestrictions := {
        val oldValue = (concurrentRestrictions in Global).value
        val maxTasks = 2
        if (githubIsWorkflowBuild.value)
        // Recreate the default rules with the task limit hard-coded:
          Seq(Tags.limitAll(maxTasks), Tags.limit(Tags.ForkedTestGroup, 1))
        else
          oldValue
      },

      // Tasks tagged with `ExclusiveTest` should be run exclusively.
      concurrentRestrictions += Tags.exclusive(ExclusiveTest),

      // UnsafeEvictions default settings
      unsafeEvictionsFatal := false,
      unsafeEvictionsConf := Seq.empty,
      unsafeEvictionsCheck / evictionWarningOptions := EvictionWarningOptions.full
        .withWarnEvictionSummary(true)
        .withInfoAllEvictions(false))

  override def buildSettings: scala.Seq[Def.Setting[_]] =
    githubActionsSettings ++
    addCommandAlias("ci", "; checkHeaders; test") ++
    {
      val vf = file(VersionsPath)
      if (vf.exists())
        Seq(managedVersions := ManagedVersions(vf.toPath))
      else
        Seq()
    } ++
    Seq(
      organization := "com.precog",

      organizationName := "Precog",
      organizationHomepage := Some(url("https://precog.com")),

      resolvers := Seq(Resolver.sonatypeRepo("releases")),

      checkLocalEvictions := {
        if (foundLocalEvictions.nonEmpty) {
          sys.error(s"found active local evictions: ${foundLocalEvictions.mkString("[", ", ", "]")}; publication is disabled")
        }
      },

      trickleDbURI := "https://github.com/precog/build-metadata.git",
      trickleRepositoryName := Project.normalizeModuleID(uri(trickleRepositoryURI.value).getPath.substring(1)),
      trickleRepositoryURI := scmInfo.value.map(_.browseUrl).orElse(homepage.value).getOrElse {
        sys.error("Set 'ThisBuild / trickleRepositoryURI' to the github page of this project")
      }.toString,
      trickleGitConfig := {
        import sbttrickle.git.GitConfig
        (sys.env.get("GITHUB_ACTOR"), sys.env.get("GITHUB_TOKEN")) match {
          case (Some(user), Some(password)) => GitConfig(trickleDbURI.value, user, password)
          case _                            => GitConfig(trickleDbURI.value)
        }
      },

      transferPublishAndTagResources / aggregate := false,
      transferPublishAndTagResources := {
        val baseDir = (ThisBuild / baseDirectory).value

        transferScripts(
          "core",
          baseDir,
          "publishAndTag",
          "readVersion",
          "isRevision")

        transferToBaseDir(
          "core",
          baseDir,
          "signing-secret.pgp.enc")
      },

      transferCommonResources / aggregate := false,
      transferCommonResources := {
        val baseDir = (ThisBuild / baseDirectory).value

        transferScripts(
          "core",
          baseDir,
          "checkAndAutoMerge",
          "commonSetup",
          "discordTravisPost",
          "listLabels",
          "closePR")

        transferToBaseDir("core", baseDir, "common-secrets.yml.enc")
      },

      secrets := Seq(file("common-secrets.yml.enc")),

      exportSecretsForActions := {
        val log = streams.value.log
        val plogger = ProcessLogger(log.info(_), log.error(_))

        if (sys.env.get("ENCRYPTION_PASSWORD").isEmpty) {
          sys.error("$ENCRYPTION_PASSWORD not set")
        }

        val yaml = new Yaml

        secrets.value foreach { file =>
          if (file.exists()) {
            val decrypted = s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in $file -d""" !! plogger
            val parsed = yaml.load[Any](decrypted)
              .asInstanceOf[java.util.Map[String, String]]
              .asScala
              .toMap   // yolo

            parsed foreach {
              case (key, value) =>
                println(s"::add-mask::$value")
                println(s"::set-env name=$key::$value")
            }
          }
        }
      },

      decryptSecret / aggregate := false,
      decryptSecret := crypt("-d", _.stripSuffix(".enc")).evaluated,

      encryptSecret / aggregate := false,
      encryptSecret := crypt("-e", _ + ".enc").evaluated)

  def crypt(operation: String, destFile: String => String): Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log

    if (sys.env.get("ENCRYPTION_PASSWORD").isEmpty) {
      log.error("ENCRYPTION_PASSWORD not set")
      sys.error("$ENCRYPTION_PASSWORD not set")
    }

    val file = fileParser(baseDirectory.value).parsed
    val output = destFile(file.getPath)
    val exitCode =
      runWithLogger(s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in $file -out $output $operation""", log)
    if (exitCode != 0) {
      log.error(s"openssl exited with status $exitCode")
      sys.error(s"openssl exited with status $exitCode")
    } else {
      file.delete()
    }
  }

  private def runWithLogger(command: String, log: Logger, merge: Boolean = false, workingDir: Option[File] = None): Int = {
    val plogger = ProcessLogger(log.info(_), if (merge) log.info(_) else log.error(_))
    Process(command, workingDir) ! plogger
  }

  def unsafeEvictionsCheckTask: Initialize[Task[UpdateReport]] = Def.task {
    val currentProject = thisProjectRef.value.project
    val module = ivyModule.value
    val isFatal = unsafeEvictionsFatal.value
    val conf = unsafeEvictionsConf.value
    val ewo = (evictionWarningOptions in unsafeEvictionsCheck).value
    val report = (updateFull tag(Tags.Update, Tags.Network)).value
    val log = streams.value.log
    precog.UnsafeEvictions.check(currentProject, module, isFatal, conf, ewo, report, log)
  }

  private def isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

  private def transfer(src: String, dst: File, permissions: Set[PosixFilePermission] = Set()) = {
    val src2 = getClass.getClassLoader.getResourceAsStream(src)
    IO.transfer(src2, dst)

    if (!isWindows()) {
      Files.setPosixFilePermissions(
        dst.toPath,
        (Files.getPosixFilePermissions(dst.toPath).asScala ++ permissions).asJava)
    }
  }

  protected def transferToBaseDir(prefix: String, baseDir: File, srcs: String*): Unit =
    srcs.foreach(src => transfer(prefix + "/" + src, baseDir / src))

  protected def transferScripts(prefix: String, baseDir: File, srcs: String*): Unit =
    srcs.foreach(src => transfer(prefix + "/" + src, baseDir / "scripts" / src, Set(OWNER_EXECUTE)))

  override def projectSettings: scala.Seq[Def.Setting[_]] =
    AutomateHeaderPlugin.projectSettings ++
    commonBuildSettings ++
    commonPublishSettings ++
    Seq(
      Global / internalPublishAsOSSProject := (Global / internalPublishAsOSSProject).value || publishAsOSSProject.value,

      version := {
        import scala.sys.process._

        val currentVersion = version.value
        if (!githubIsWorkflowBuild.value)
          currentVersion + "-" + "git rev-parse HEAD".!!.substring(0, 7)
        else
          currentVersion
      },

      unsafeEvictionsFatal := githubIsWorkflowBuild.value,
      unsafeEvictionsConf += (UnsafeEvictions.IsOrg("com.precog") -> VersionNumber.SecondSegment),
      update := {
        unsafeEvictionsCheck.value
        update.value
      },

      resolvers ++= {
        if (!publishAsOSSProject.value)
          Seq(Resolver.bintrayRepo("precog-inc", "maven-private"))
        else
          Seq.empty
      },

      // TODO: self-check, to run on PRs
      // TODO: dry mode on not-a-build
      // TODO: cluster datasources/destinations
      // TODO: set trickleGitCommitMessage

      trickleUpdateDependencies := {
        val log = streams.value.log
        val outdatedDependencies = trickleOutdatedDependencies.value
        val dir = (ThisBuild / baseDirectory).value.toPath
        val managedVersions = ManagedVersions(dir.resolve(VersionsPath))

        def getChange(isRevision: Boolean, isBreaking: Boolean): String =
          if (isRevision) "revision"
          else if (isBreaking) "breaking"
          else "feature"

        var isRevision = true
        var isBreaking = false
        var hasErrors = false

        outdatedDependencies.map {
          case ModuleUpdateData(_, _, newRevision, dependencyRepository, _) => (newRevision, dependencyRepository)
        } foreach {
          case (newRevision, dependencyRepository) =>
            managedVersions.get(dependencyRepository) match {
              case Some(currentRevision) =>
                val currentVersion = VersionNumber(currentRevision)
                val newVersion = VersionNumber(newRevision)
                val testRevision = VersionNumber.SecondSegment.isCompatible(currentVersion, newVersion)
                val testBreaking = !VersionNumber.SemVer.isCompatible(currentVersion, newVersion)
                isRevision &&= testRevision
                isBreaking ||= testBreaking
                log.info(s"Updated ${getChange(testRevision, testBreaking)} $dependencyRepository $currentVersion -> $newRevision")
              case None                  =>
                // TODO: use scalafix to change build.sbt
                hasErrors ||= true
                log.error(s"$dependencyRepository not present on $VersionsPath")
                log.error(s"""Fix build.sbt by replacing the version of affected artifacts with 'managedVersions.value("$dependencyRepository")'""")
            }

            managedVersions(dependencyRepository) = newRevision
        }

        log.info(s"version: ${getChange(isRevision, isBreaking)}")

        if (hasErrors) sys.error("Unmanaged dependencies found!")
      },

      trickleCreatePullRequest := { repository =>
        val previous = trickleCreatePullRequest.value
        val author = trickleRepositoryName.value
        previous(repository)
        new AutoBump(repository, sys.env("GITHUB_TOKEN")).createPullRequest(author, sLog.value)
      })
}

