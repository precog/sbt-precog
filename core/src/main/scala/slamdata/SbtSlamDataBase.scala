/*
 * Copyright 2014–2020 SlamData Inc.
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

package slamdata

import sbt._, Keys._
import sbt.Def.Initialize
import sbt.complete.DefaultParsers.fileParser

import de.heikoseeberger.sbtheader.AutomateHeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._

import _root_.io.crashbox.gpg.SbtGpg

import sbtghactions.GitHubActionsPlugin, GitHubActionsPlugin.autoImport._

import org.yaml.snakeyaml.Yaml

import sbttrickle.TricklePlugin, TricklePlugin.autoImport._
import sbttrickle.metadata.{ModuleUpdateData, OutdatedRepository}

import scala.{sys, Boolean, None, Some, StringContext}
import scala.collection.immutable.{Set, Seq}
import scala.collection.JavaConverters._
import scala.sys.process._

import java.io.File
import java.lang.{String, System}
import java.nio.file.attribute.PosixFilePermission, PosixFilePermission.OWNER_EXECUTE
import java.nio.file.Files

abstract class SbtSlamDataBase extends AutoPlugin {
  private[this] val AutobumpPrTitle = "Applied dependency updates"

  private var foundLocalEvictions: Set[(String, String)] = Set()

  override def requires =
    plugins.JvmPlugin &&
    GitHubActionsPlugin &&
    SbtGpg &&
    TricklePlugin

  override def trigger = allRequirements

  class autoImport extends SbtSlamDataKeys {
    val VersionsPath = ".versions.json"
    val BothScopes = "test->test;compile->compile"

    // Exclusive execution settings
    lazy val ExclusiveTests = config("exclusive") extend Test

    val ExclusiveTest = Tags.Tag("exclusive-test")

    def exclusiveTasks(tasks: Scoped*) =
      tasks.flatMap(inTask(_)(tags := Seq((ExclusiveTest, 1))))

    def scalacOptions_2_10(strict: Boolean) = {
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

    def scalacOptions_2_11(strict: Boolean) = {
      val global = Seq(
        "-Ypartial-unification",
        "-Ywarn-unused-import")

      if (strict)
        global :+ "-Ydelambdafy:method"
      else
        global
    }

    def scalacOptions_2_12(strict: Boolean) = Seq("-target:jvm-1.8")

    def scalacOptions_2_13(strict: Boolean) = {
      val numCPUs = java.lang.Runtime.getRuntime.availableProcessors()
      Seq(
        s"-Ybackend-parallelism", numCPUs.toString,
        "-Wunused:imports",
        "-Wdead-code",
        "-Wnumeric-widen",
        "-Wvalue-discard")
    }

    val scalacOptionsRemoved_2_13 =
      Seq(
        "-Yno-adapted-args",
        "-Ywarn-unused-import",
        "-Ywarn-value-discard",
        "-Ywarn-numeric-widen",
        "-Ywarn-dead-code",
        "-Xfuture")

    val headerLicenseSettings = Seq(
      headerLicense := Some(HeaderLicense.ALv2("2014–2020", "SlamData Inc.")),
      licenses += (("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0"))),
      checkHeaders := {
        if ((headerCreate in Compile).value.nonEmpty) sys.error("headers not all present")
      })

    lazy val commonBuildSettings = Seq(
      outputStrategy := Some(StdoutOutput),
      autoCompilerPlugins := true,
      autoAPIMappings := true,

      addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.3" cross CrossVersion.full),
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

    lazy val commonPublishSettings = Seq(
      licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),

      publishAsOSSProject := true,
      performMavenCentralSync := false,

      synchronizeWithSonatypeStaging := {},
      releaseToMavenCentral := {},
      autoAPIMappings := true,

      developers := List(
        Developer(
          id = "slamdata",
          name = "SlamData Inc.",
          email = "contact@slamdata.com",
          url = new URL("http://slamdata.com")
        )))

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

  override def globalSettings = Seq(
    concurrentRestrictions in Global := {
      val oldValue = (concurrentRestrictions in Global).value
      val maxTasks = 2
      if (githubIsWorkflowBuild.value)
      // Recreate the default rules with the task limit hard-coded:
        Seq(Tags.limitAll(maxTasks), Tags.limit(Tags.ForkedTestGroup, 1))
      else
        oldValue
    },

    // Tasks tagged with `ExclusiveTest` should be run exclusively.
    concurrentRestrictions in Global += Tags.exclusive(ExclusiveTest),

    // UnsafeEvictions default settings
    unsafeEvictionsFatal := false,
    unsafeEvictionsConf := Seq.empty,
    evictionWarningOptions in unsafeEvictionsCheck := EvictionWarningOptions.full
      .withWarnEvictionSummary(true)
      .withInfoAllEvictions(false),
  )

  override def buildSettings =
    addCommandAlias("ci", "; checkHeaders; test") ++
    {
      val vf = file(VersionsPath)
      if (vf.exists())
        Seq(managedVersions := ManagedVersions(vf.toPath))
      else
        Seq()
    } ++
    Seq(
      organization := "com.slamdata",

      organizationName := "SlamData Inc.",
      organizationHomepage := Some(url("http://slamdata.com")),

      resolvers := Seq(
        Resolver.sonatypeRepo("releases"),
        Resolver.bintrayRepo("slamdata-inc", "maven-public")),

      checkLocalEvictions := {
        if (!foundLocalEvictions.isEmpty) {
          sys.error(s"found active local evictions: ${foundLocalEvictions.mkString("[", ", ", "]")}; publication is disabled")
        }
      },

      trickleDbURI := "https://github.com/slamdata/build-metadata.git",
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

        if (!sys.env.get("ENCRYPTION_PASSWORD").isDefined) {
          sys.error("$ENCRYPTION_PASSWORD not set")
        }

        val yaml = new Yaml

        secrets.value foreach { file =>
          if (file.exists()) {
            val decrypted = s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in ${file} -d""" !! plogger
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
      decryptSecret := {
        if (!sys.env.get("ENCRYPTION_PASSWORD").isDefined) {
          sys.error("$ENCRYPTION_PASSWORD not set")
        }

        val file = fileParser(baseDirectory.value).parsed
        val log = streams.value.log
        val ecode =
          runWithLogger(s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in ${file} -out ${file.getPath().replaceAll("\\.enc$", "")} -d""", log)

        if (ecode != 0) {
          sys.error(s"openssl exited with status $ecode")
        } else {
          file.delete()
        }
      },

      encryptSecret / aggregate := false,
      encryptSecret := {
        if (!sys.env.get("ENCRYPTION_PASSWORD").isDefined) {
          sys.error("$ENCRYPTION_PASSWORD not set")
        }

        val file = fileParser(baseDirectory.value).parsed
        val log = streams.value.log
        val ecode =
          runWithLogger(s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in ${file} -out ${file}.enc""", log)

        if (ecode != 0) {
          sys.error(s"openssl exited with status $ecode")
        } else {
          file.delete()
        }
      },

      // TODO make this suck less
      trickleGithubIsAutobumpPullRequest := { pr =>
        pr.title == AutobumpPrTitle &&
          pr.base.exists(_.ref == "master") &&
          pr.head.exists(_.ref.startsWith("trickle/"))
      })

  private def runWithLogger(command: String, log: Logger, merge: Boolean = false, workingDir: Option[File] = None): Int = {
    val plogger = ProcessLogger(log.info(_), if (merge) log.info(_) else log.error(_))
    Process(command, workingDir) ! plogger
  }

  private def runWithLoggerSeq(command: Seq[String], log: Logger, merge: Boolean, workingDir: Option[File], env: (String, String)*): Int = {
    val plogger = ProcessLogger(log.info(_), if (merge) log.info(_) else log.error(_))
    Process(command, workingDir, env: _*) ! plogger
  }

  def unsafeEvictionsCheckTask: Initialize[Task[UpdateReport]] = Def.task {
    val currentProject = thisProjectRef.value.project
    val module = ivyModule.value
    val isFatal = unsafeEvictionsFatal.value
    val conf = unsafeEvictionsConf.value
    val ewo = (evictionWarningOptions in unsafeEvictionsCheck).value
    val report = (updateFull tag(Tags.Update, Tags.Network)).value
    val log = streams.value.log
    slamdata.UnsafeEvictions.check(currentProject, module, isFatal, conf, ewo, report, log)
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

  protected def transferToBaseDir(prefix: String, baseDir: File, srcs: String*) =
    srcs.foreach(src => transfer(prefix + "/" + src, baseDir / src))

  protected def transferScripts(prefix: String, baseDir: File, srcs: String*) =
    srcs.foreach(src => transfer(prefix + "/" + src, baseDir / "scripts" / src, Set(OWNER_EXECUTE)))

  override def projectSettings =
    AutomateHeaderPlugin.projectSettings ++
    commonBuildSettings ++
    commonPublishSettings ++
    Seq(
      version := {
        import scala.sys.process._

        val currentVersion = version.value
        if (!githubIsWorkflowBuild.value)
          currentVersion + "-" + "git rev-parse HEAD".!!.substring(0, 7)
        else
          currentVersion
      },

      unsafeEvictionsFatal := githubIsWorkflowBuild.value,
      unsafeEvictionsConf += (UnsafeEvictions.IsOrg("com.slamdata") -> VersionNumber.SecondSegment),
      update := {
        unsafeEvictionsCheck.value
        update.value
      },

      resolvers ++= {
        if (!publishAsOSSProject.value)
          Seq(Resolver.bintrayRepo("slamdata-inc", "maven-private"))
        else
          Seq.empty
      },

      trickleCreatePullRequest := {
        val prior = trickleCreatePullRequest.value
        val log = sLog.value

        { (repo: OutdatedRepository) =>
          import cats.effect.{ContextShift, IO}

          import github4s.Github
          import github4s.GithubResponses.GHResult
          import github4s.domain.NewPullRequestData

          import scala.concurrent.ExecutionContext.Implicits.global

          implicit val cs: ContextShift[IO] = IO.contextShift(global)

          prior(repo)

          val authenticated = {
            val uri = new URI(repo.url)
            s"${uri.getScheme}://${sys.env("GITHUB_ACTOR")}:${sys.env("GITHUB_TOKEN")}@${uri.getHost}${uri.getPath}"
          }

          val dir = Files.createTempDirectory("sbt-slamdata")
          val dirFile = dir.toFile

          if (runWithLogger(s"git clone --depth 1 $authenticated ${dirFile.getPath}", log, merge = true) != 0) {
            sys.error("git-clone exited with error")
          }

          val branchName = s"trickle/version-bump-${System.currentTimeMillis()}"
          if (runWithLogger(s"git checkout -b $branchName", log, merge = true, workingDir = Some(dirFile)) != 0) {
            sys.error("git-checkout exited with error")
          }

          val managed = ManagedVersions(dir.resolve(VersionsPath))
          var isRevision = true
          var isBreaking = false
          repo.updates foreach {
            case ModuleUpdateData(_, _, newRevision, dependencyRepo, _) =>
              managed.get(dependencyRepo) foreach { oldVStr =>
                val vOld = VersionNumber(oldVStr)
                val vNew = VersionNumber(newRevision)
                isRevision &&= VersionNumber.SecondSegment.isCompatible(vOld, vNew)
                isBreaking ||= !VersionNumber.SemVer.isCompatible(vOld, vNew)
              }

              managed(dependencyRepo) = newRevision
          }

          val change =
            if (isRevision) "revision"
            else if (isBreaking) "breaking"
            else "feature"

          val dependencyCheck = repo.updates
            .map(u => s"${u.dependency.organization}:${u.dependency.name}:${u.newRevision}")
            .mkString("trickleCheckVersion ", " ", "")

          if (runWithLoggerSeq(Seq("sbt", dependencyCheck), log, merge=true, Some(dirFile)) != 0) {
            sys.error(s"repository ${repo.repository} did not apply versions file correctly")
          }

          if (runWithLoggerSeq(Seq("sbt", "update"), log, merge=true, Some(dirFile)) != 0) {
            log.warn("was unable to run `sbt update` following the trickle application")
            log.warn("this may mean that the some artifacts are not yet propagated; skipping")
          } else {
            if (runWithLogger(s"git add $VersionsPath", log, merge = true, workingDir = Some(dirFile)) != 0) {
              sys.error("git-add exited with error")
            }

            val commitECode = runWithLoggerSeq(
              Seq("git", "commit", "-m", AutobumpPrTitle),
              log,
              true,
              Some(dirFile),
              "GIT_AUTHOR_NAME" -> "SlamData Bot",
              "GIT_AUTHOR_EMAIL" -> "bot@slamdata.com",
              "GIT_COMMITTER_NAME" -> "SlamData Bot",
              "GIT_COMMITTER_EMAIL" -> "bot@slamdata.com")

            if (commitECode != 0) {
              log.warn("git-commit exited with error")
              log.warn("this usually means the target repository was *already* at the latest version but hasn't published yet")
              log.warn("you should check for a stuck trickle PR on that repository")
            } else {
              if (runWithLogger(s"git push origin $branchName", log, merge = true, workingDir = Some(dirFile)) != 0) {
                sys.error("git-push exited with error")
              }

              val (owner, repoSlug) = repo.ownerAndRepository.getOrElse(sys.error(s"invalid url ${repo.url}"))

              val createPrF = Github[IO](sys.env.get("GITHUB_TOKEN"))
                .pullRequests
                .createPullRequest(
                  owner,
                  repoSlug,
                  NewPullRequestData(AutobumpPrTitle, "This PR brought to you by sbt-trickle. Please do come again!"),   // TODO
                  branchName,
                  "master",
                  Some(true))

              def assignLabelList(pr: Int) = Github[IO](sys.env.get("GITHUB_TOKEN"))
                .issues
                .addLabels(owner, repoSlug, pr, List(s"version: $change"))

              val createAndLabelPr = for {
                response <- createPrF
                result <- IO.fromEither(response)
                GHResult(pullRequest, _, _) = result
                _ <- assignLabelList(pullRequest.number)
              } yield pullRequest

              createAndLabelPr.attempt.unsafeRunSync.fold(
                throw _,
                r => log.info(s"Opened $owner/$repoSlug#${r.number}"))
            }
          }
        }
      })
}

