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

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._
import scala.util.matching.Regex

import _root_.io.crashbox.gpg.SbtGpg
import cats.effect.Clock
import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.IO.contextShift
import de.heikoseeberger.sbtheader.AutomateHeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import io.github.davidgregory084.TpolecatPlugin
import org.http4s.client.asynchttpclient.AsyncHttpClient
import precog.algebras.DraftPullRequests
import precog.algebras.Github
import precog.algebras.Labels
import precog.algebras.References
import precog.algebras.Runner
import precog.interpreters.GithubInterpreter
import precog.interpreters.SyncRunner
import sbt.Def.Initialize
import sbt.Keys._
import sbt.complete.DefaultParsers.fileParser
import sbt.io.{IO => SIO}
import sbt.util.Logger
import sbt.{Logger => _, _}
import sbtghactions.GenerativeKeys._
import sbtghactions.GitHubActionsPlugin
import sbtghactions.GitHubActionsPlugin.autoImport._
import sbtghactions.JavaSpec
import sbtghactions.Ref
import sbtghactions.RefPredicate
import sbtghactions.UseRef
import sbtghactions.WorkflowJob
import sbtghactions.WorkflowStep
import sbttrickle.TricklePlugin
import sbttrickle.TricklePlugin.autoImport._
import sbttrickle.metadata.ModuleUpdateData

abstract class SbtPrecogBase extends AutoPlugin {
  private var foundLocalEvictions: Set[(String, String)] = Set()
  val VersionsPath = ".versions.json"

  val RevisionLabel = "version: revision"
  val FeatureLabel = "version: feature"
  val BreakingLabel = "version: breaking"
  val ReleaseLabel = "version: release"

  private lazy val internalPublishAsOSSProject =
    settingKey[Boolean]("Internal proxy to lift the scoping on publishAsOSSProject")

  override def requires =
    plugins.JvmPlugin &&
      GitHubActionsPlugin &&
      SbtGpg &&
      TpolecatPlugin &&
      TricklePlugin

  override def trigger = allRequirements

  class autoImport extends SbtPrecogKeys {
    val BothScopes = "test->test;compile->compile"

    // Exclusive execution settings
    lazy val ExclusiveTests = config("exclusive") extend Test

    val ExclusiveTest = Tags.Tag("exclusive-test")

    def exclusiveTasks(tasks: Scoped*) =
      tasks.flatMap(inTask(_)(tags := Seq((ExclusiveTest, 1))))

    val scalafmtSettings: Seq[Def.Setting[_]] = Seq(
      SettingKey[Unit]("scalafmtGenerateConfig") := {
        // writes to file once when build is loaded
        SIO.write(
          file(".scalafmt-common.conf"),
          resourceContents("/core/scalafmt-common.conf")
        )
        if (!file(".scalafmt.conf").exists())
          SIO.write(file(".scalafmt.conf"), resourceContents("/core/scalafmt.conf"))
      })

    val headerLicenseSettings: Seq[Def.Setting[_]] = Seq(
      headerLicense := Some(HeaderLicense.ALv2("2021", "Precog Data")),
      licenses += (("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0")))
    )

    lazy val commonBuildSettings: Seq[Def.Setting[_]] = Seq(
      outputStrategy := Some(StdoutOutput),
      autoCompilerPlugins := true,
      autoAPIMappings := true,
      addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
      addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),

      // default to true
      scalacStrictMode := true,
      // This option shows the category of the warning which makes it easy to silence a warning with @nowarn
      // E.g. this warning
      // [unused-params @ tectonic.json.SkipBenchmarks.projectBarKeyFromUgh10k.bh] parameter value bh in method projectBarKeyFromUgh10k is never used
      // can be silenced with
      // @nowarn("cat=unused-params")
      scalacOptions ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, 13)) => Seq("-Wconf:any:warning-verbose")
          case Some((2, 12)) => Seq()
          case v => sys.error(s"Unsupported scala version: $v")
        }
      },
      scalacOptions --= {
        val strict = scalacStrictMode.value
        if (strict) Seq() else Seq("-Xfatal-warnings")
      },
      Compile / doc / scalacOptions -= "-Xfatal-warnings",
      unsafeEvictionsCheck := unsafeEvictionsCheckTask.value
    ) ++ scalafmtSettings ++ headerLicenseSettings

    lazy val commonPublishSettings: Seq[Def.Setting[_]] = Seq(
      licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
      publishAsOSSProject := true,
      performMavenCentralSync := false,
      autoAPIMappings := true,
      developers := List(
        Developer(
          id = "precog",
          name = "Precog Data",
          email = "support@precog.com",
          url = new URL("http://precog.com")
        ))
    )

    lazy val githubActionsSettings: Seq[Def.Setting[_]] = Seq(
      githubWorkflowSbtCommand := s"$$SBT",
      githubWorkflowJavaVersions := Seq(
        JavaSpec(JavaSpec.Distribution.Temurin, "17")
      ),
      githubWorkflowEnv := Map(
        "SBT" -> "sbt",
        "REPO_SLUG" -> s"$${{ github.repository }}",
        "ENCRYPTION_PASSWORD" -> s"$${{ secrets.ENCRYPTION_PASSWORD }}",
        "GITHUB_ACTOR" -> "precog-bot",
        "GITHUB_TOKEN" -> s"$${{ secrets.PRECOG_GITHUB_TOKEN }}"
      ),

      // we don't want to redundantly build other pushed branches
      githubWorkflowTargetBranches := Seq("master", "main", "backport/v*"),
      githubWorkflowPREventTypes += sbtghactions.PREventType.ReadyForReview,
      githubWorkflowBuildPreamble +=
        WorkflowStep.Sbt(
          List("transferCommonResources"),
          name = Some("Common sbt setup"),
          cond = Some("env.ENCRYPTION_PASSWORD != null")),
      githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("ci"))),
      githubWorkflowPublishPreamble ++= Seq(
        WorkflowStep.Sbt(
          List("transferCommonResources", "transferPublishAndTagResources"),
          name = Some("Common sbt setup")),
        WorkflowStep.Run(List("./scripts/commonSetup"))
      ),
      githubWorkflowPublish := Seq(
        WorkflowStep.Run(
          List(s"./scripts/publishAndTag $${{ github.repository }}"),
          name = Some("Publish artifacts and create tag"))),
      githubWorkflowPublishTargetBranches ++= Seq(
        RefPredicate.StartsWith(Ref.Branch("backport/v")),
        RefPredicate.Equals(Ref.Branch("main")),
        RefPredicate.Equals(Ref.Branch("master"))),

      // TODO this needs to be fixed... somehow
      githubWorkflowAddedJobs += WorkflowJob(
        "auto-merge",
        "Auto Merge",
        List(
          WorkflowStep.Use(
            name = Some("Merge"),
            id = Some("merge"),
            ref = UseRef.Public("actions", "github-script", "v6"),
            params = Map(
              "script" -> s"""  github.rest.pulls.merge({
                             |    owner: context.repo.owner,
                             |    repo: context.repo.repo,
                             |    pull_number: $${{ github.event.pull_request.number }},
                             |  }); """.stripMargin,
              "github-token" -> "${{ secrets.PRECOG_GITHUB_TOKEN }}"
            )
          )
        ),
        cond = Some(
          "github.event_name == 'pull_request' && contains(github.head_ref, 'version-bump') && contains(github.event.pull_request.labels.*.name, 'version: revision')"),
        needs = List("build")
      ),

      // Make sure that the right labels are applied for PRs targetting main branches
      githubWorkflowAddedJobs += WorkflowJob(
        "check-labels",
        "Check Labels",
        List(
          WorkflowStep.Use(
            name = Some("Check PR labels"),
            ref = UseRef.Docker("agilepathway/pull-request-label-checker", "v1.4.30"),
            params = Map(
              "one_of" -> s"$BreakingLabel,$FeatureLabel,$RevisionLabel,$ReleaseLabel",
              "none_of" -> ":stop_sign:",
              "repo_token" -> s"$${{ env.GITHUB_TOKEN }}"
            )
          )
        ),
        cond = Some("github.event_name == 'pull_request' && !github.event.pull_request.draft")
      ),

      // Check whether we need to version bump
      // We version bump when:
      //    - it's a push to the main/master branch
      //    - the push has an associated PR
      //    - the PR has a version label attached
      // This job outputs the new version
      githubWorkflowAddedJobs += WorkflowJob(
        "next-version",
        "Next version",
        List(
          // We have to replicate the checkout step ourselves to add the token to
          // overcome the github limitation of commmits triggered by workflows
          // not triggerring additional workflows
          // https://github.com/marketplace/actions/git-auto-commit#commits-made-by-this-action-do-not-trigger-new-workflow-runs
          WorkflowStep.Use(
            UseRef.Public("actions", "checkout", "v3"),
            name = Some("Checkout current branch (fast)"),
            params = Map(
              "token" -> s"$${{ secrets.PRECOG_GITHUB_TOKEN }}"
            )
          ),
          // Get current version
          WorkflowStep.Run(
            List(
              "echo \"CURRENT_VERSION=$(cat version.sbt | awk '{ gsub(/\"/, \"\", $5); print $5 }')\" >> $GITHUB_OUTPUT"
            ),
            id = Some("current_version"),
            name = Some("Get current version")
          ),
          // get for associated PR and construct the next versionw
          WorkflowStep.Use(
            name = Some("Compute next version"),
            id = Some("compute_next_version"),
            ref = UseRef.Public("actions", "github-script", "v6"),
            params = Map(
              "script" -> s"""  const currentVersion = '$${{steps.current_version.outputs.CURRENT_VERSION}}'
                             |  const parsedVersion = currentVersion.split(".")
                             |  var major = Number(parsedVersion[0])
                             |  var minor = Number(parsedVersion[1])
                             |  var patch = Number(parsedVersion[2])
                             |
                             |  const prResponse = await github.rest.repos.listPullRequestsAssociatedWithCommit({
                             |    owner: context.repo.owner,
                             |    repo: context.repo.repo,
                             |    commit_sha: context.sha
                             |  })
                             |
                             |  console.log(context)
                             |
                             |  const prs = prResponse.data
                             |
                             |  if (prs === undefined) {
                             |    throw new Error("Could not fetch PRs for commit: status " + prs.status)
                             |  } else if (prs.length > 1) {
                             |    throw new Error("Cannot determine version increment required as there is more than one PR associated with the commit: " + context.sha)
                             |  } else if (prs.length === 0) {
                             |    throw new Error("Cannot determine version increment required as there are no PRs associated with the commit: " + context.sha)
                             |  } 
                             |
                             |  const pr = prs[0]
                             |
                             |  for (const label of pr.labels) {
                             |     if (label.name === 'version: revision') {
                             |        patch = patch + 1
                             |        break
                             |     } else if (label.name === 'version: feature') {
                             |        patch = 0
                             |        minor = minor + 1
                             |        break
                             |     } else if (label.name === 'version: breaking') {
                             |        major = major + 1
                             |        minor = 0
                             |        patch = 0
                             |        break
                             |     } else if (label.name === 'version: release') {
                             |        major = major + 1
                             |        minor = 0
                             |        patch = 0
                             |        break
                             |     }
                             |  }
                             |
                             |  const nextVersion = major + '.' + minor + '.' + patch
                             |   
                             |  if (nextVersion === currentVersion) {
                             |    throw new Error("Could not detect the version label on PR " + pr.number + " (obtained via association to commit " + context.sha + ")")
                             |  }
                             |  
                             |  console.log("Setting the next version to " + nextVersion)
                             | 
                             |  var body = ""
                             |  if (pr.body === undefined || pr.body === null || pr.body === "") {
                             |    body = ""
                             |  } else {
                             |    body = "\\n" + pr.body.replaceAll("\\r\\n", "\\n")
                             |  }
                             |  
                             |  // set outputs for 
                             |  const result = {
                             |    nextVersion: nextVersion,
                             |    commitMessage: "Version release: " + nextVersion + "\\n\\n" + pr.title + body
                             |  }
                             |  return result""".stripMargin
            )
          ),
          WorkflowStep.Run(
            name = Some("Modify version"),
            id = Some("modify_version"),
            commands = List(
              s"""echo 'ThisBuild / version := "$${{fromJson(steps.compute_next_version.outputs.result).nextVersion}}"' > version.sbt"""
            )
          ),
          WorkflowStep.Use(
            name = Some("Commit changes"),
            ref = UseRef.Public("stefanzweifel", "git-auto-commit-action", "v4"),
            params = Map(
              "commit_message" -> s"$${{fromJson(steps.compute_next_version.outputs.result).commitMessage}}",
              "commit_user_name" -> "precog-bot",
              "commit_user_email" -> "bot@precog.com"
            )
          )
        ),
        // We check that it's a push. We don't need to check for whether the branch
        // is right because the whole workflow is set to only run on either pull requests or
        // pushes to main/master, so a check that it's a push is enough
        //
        // Also, don't trigger a version bump on version bump commits or else we'll
        // just infinitely bump
        cond = Some(
          "github.event_name == 'push' && !startsWith(github.event.head_commit.message, 'Version release')")
      ),
      githubWorkflowPublishCond ~= { condMaybe =>
        val extraCondition =
          """startsWith(github.event.head_commit.message, 'Version release')"""
        condMaybe.map(cond => s"$cond && $extraCondition").orElse(Some(extraCondition))
      },
      githubWorkflowGeneratedCI := {
        githubWorkflowGeneratedCI.value map { job =>
          if (job.id == "build")
            job.copy(cond = {
              val dontRunOnDraftPRs =
                "!(github.event_name == 'pull_request' && github.event.pull_request.draft)"
              // If we are in a situation in which a version bump commit is gonna get generated - don't bother running
              // the 'build' job.
              //
              // I.e. if a push was made to main/master and it isn't a `Version release` - don't run
              val dontRunBeforeVersionBump =
                "!(github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master') && !startsWith(github.event.head_commit.message, 'Version release'))"
              Some(s"$dontRunOnDraftPRs && $dontRunBeforeVersionBump")
            })
          else
            job
        }
      }
    )

    implicit final class ProjectSyntax(val self: Project) {
      def evictToLocal(envVar: String, subproject: String, test: Boolean = false): Project = {
        val eviction = sys.env.get(envVar).map(file).filter(_.exists()) map { f =>
          foundLocalEvictions += ((envVar, subproject))

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
      unsafeEvictionsCheck / evictionWarningOptions := EvictionWarningOptions
        .full
        .withWarnEvictionSummary(true)
        .withInfoAllEvictions(false)
    )

  override def buildSettings: scala.Seq[Def.Setting[_]] =
    githubActionsSettings ++
      addCommandAlias("ci", ";headerCheckAll ;scalafmtCheckAll ;scalafmtSbtCheck ;test") ++
      addCommandAlias("prePR", ";clean ;scalafmtAll ;scalafmtSbt ;headerCreateAll") ++
      Seq(
        organization := "com.precog",
        organizationName := "Precog",
        organizationHomepage := Some(url("https://precog.com")),
        managedVersions := ManagedVersions(
          ((LocalRootProject / baseDirectory).value / VersionsPath).toPath),
        resolvers := Resolver.sonatypeOssRepos("releases"),
        checkLocalEvictions := {
          if (foundLocalEvictions.nonEmpty) {
            sys.error(
              s"found active local evictions: ${foundLocalEvictions.mkString("[", ", ", "]")}; publication is disabled")
          }
        },
        trickleDbURI := "https://github.com/precog/build-metadata.git",
        trickleRepositoryName := Project.normalizeModuleID(
          uri(trickleRepositoryURI.value).getPath.substring(1)),
        trickleRepositoryURI := scmInfo
          .value
          .map(_.browseUrl)
          .orElse(homepage.value)
          .getOrElse {
            sys.error(
              "Set 'ThisBuild / trickleRepositoryURI' to the github page of this project")
          }
          .toString,
        trickleGitConfig := {
          import sbttrickle.git.GitConfig
          val baseConf = sys.env.get("GITHUB_TOKEN") match {
            case Some(password) => GitConfig(trickleDbURI.value, "_", password)
            case _ => GitConfig(trickleDbURI.value)
          }
          if (githubIsWorkflowBuild.value) baseConf
          else baseConf.withDontPush
        },
        transferPublishAndTagResources / aggregate := false,
        transferPublishAndTagResources := {
          val baseDir = (ThisBuild / baseDirectory).value

          transferScripts("core", baseDir, "publishAndTag", "readVersion", "isRevision")

          transferToBaseDir("core", baseDir, "signing-secret.pgp.enc")
        },
        transferCommonResources / aggregate := false,
        transferCommonResources := {
          val baseDir = (ThisBuild / baseDirectory).value

          transferScripts("core", baseDir, "commonSetup")

          transferToBaseDir("core", baseDir, "common-secrets.yml.enc")
        },
        secrets := Seq(file("common-secrets.yml.enc")),
        decryptSecret / aggregate := false,
        decryptSecret := crypt("-d", _.stripSuffix(".enc")).evaluated,
        encryptSecret / aggregate := false,
        encryptSecret := crypt("-e", _ + ".enc").evaluated
      )

  private def resourceContents(resourceName: String): String =
    Option(getClass.getResource(resourceName)) match {
      case None =>
        sys.error(s"Resource $resourceName not found")
      case Some(res) =>
        sbt.io.Using.urlInputStream(res) { stream =>
          SIO.readStream(stream, StandardCharsets.UTF_8)
        }
    }

  def crypt(operation: String, destFile: String => String): Initialize[InputTask[Unit]] =
    Def.inputTask {
      val log = streams.value.log

      if (!sys.env.contains("ENCRYPTION_PASSWORD")) {
        log.error("ENCRYPTION_PASSWORD not set")
        sys.error("$ENCRYPTION_PASSWORD not set")
      }

      val file = fileParser(baseDirectory.value).parsed
      val output = destFile(file.getPath)
      val exitCode =
        runWithLogger(
          s"""openssl aes-256-cbc -pass env:ENCRYPTION_PASSWORD -md sha1 -in $file -out $output $operation""",
          log)
      if (exitCode != 0) {
        log.error(s"openssl exited with status $exitCode")
        sys.error(s"openssl exited with status $exitCode")
      } else {
        file.delete()
        ()
      }
    }

  private def runWithLogger(
      command: String,
      log: Logger,
      merge: Boolean = false,
      workingDir: Option[File] = None): Int = {
    val plogger = ProcessLogger(log.info(_), if (merge) log.info(_) else log.error(_))
    Process(command, workingDir) ! plogger
  }

  def unsafeEvictionsCheckTask: Initialize[Task[UpdateReport]] = Def.task {
    val currentProject = thisProjectRef.value.project
    val module = ivyModule.value
    val isFatal = unsafeEvictionsFatal.value
    val conf = unsafeEvictionsConf.value
    val ewo = (unsafeEvictionsCheck / evictionWarningOptions).value
    val report = (updateFull tag (Tags.Update, Tags.Network)).value
    val log = streams.value.log
    precog.UnsafeEvictions.check(currentProject, module, isFatal, conf, ewo, report, log)
  }

  private def isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

  private def transfer(
      src: String,
      dst: File,
      permissions: Set[PosixFilePermission] = Set()) = {
    val src2 = getClass.getClassLoader.getResourceAsStream(src)
    sbt.io.IO.transfer(src2, dst)

    if (!isWindows()) {
      Files.setPosixFilePermissions(
        dst.toPath,
        (Files.getPosixFilePermissions(dst.toPath).asScala ++ permissions).asJava)
    }
  }

  protected def transferToBaseDir(prefix: String, baseDir: File, srcs: String*): Unit =
    srcs.foreach(src => transfer(prefix + "/" + src, baseDir / src))

  protected def transferScripts(prefix: String, baseDir: File, srcs: String*): Unit =
    srcs.foreach(src =>
      transfer(prefix + "/" + src, baseDir / "scripts" / src, Set(OWNER_EXECUTE)))

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
        unsafeEvictionsConf += (UnsafeEvictions.IsOrg(
          "com.precog") -> VersionNumber.SecondSegment),
        update := {
          val _ = unsafeEvictionsCheck.value
          update.value
        },

        // TODO: self-check, to run on PRs
        // TODO: cluster datasources/destinations
        trickleGitUpdateMessage := s"${trickleRepositoryName.value} ${version.value}",
        trickleUpdateDependencies := {
          val log = streams.value.log
          val repository = trickleRepositoryName.value
          val outdatedDependencies = trickleOutdatedDependencies.value
          managedVersions.?.value map { versions =>
            updateDependencies(repository, outdatedDependencies, versions, log)
          } getOrElse sys.error(
            s"No version management file found; please create $VersionsPath")
        },
        trickleCreatePullRequest := { repository =>
          assert(
            url(repository.url).getHost == "github.com",
            s"Unexpected host on ${repository.url}")

          val previous = trickleCreatePullRequest.value
          val author = trickleRepositoryName.value
          val log = sLog.value
          val token = sys.env.getOrElse("GITHUB_TOKEN", sys.error("GITHUB_TOKEN not found"))

          implicit val IOContextShift: ContextShift[IO] = contextShift(global)

          val program = AsyncHttpClient.resource[IO]().use { client =>
            val github: Github[IO] = GithubInterpreter[IO](client, Some(token))
            implicit val draftPullRequests: DraftPullRequests[IO] = github.draftPullRequests
            implicit val labels: Labels[IO] = github.labels
            implicit val references: References[IO] = github.references
            implicit val clock: Clock[IO] = Clock.create

            implicit val runner: Runner[IO] = SyncRunner[IO](log)
            val runnerConfig =
              Runner.DefaultConfig.hide(token).withEnv(sys.env.filterKeys(_ == "SBT").toSeq: _*)
            val (owner, repoSlug) = repository
              .ownerAndRepository
              .getOrElse(sys.error(f"invalid repository url ${repository.url}%s"))
            val cloningURL = f"https://_:$token%s@github.com/$owner%s/$repoSlug%s"

            previous(repository)
            new AutoBump(author, owner, repoSlug, cloningURL, log)
              .createPullRequest[IO](runnerConfig)
          }

          program.unsafeRunSync()
        }
      )

  /**
   * Which repositories that will always bump dependencies as a revision PR
   */
  val RevisionRepositories: Regex =
    raw"""^precog-(?:quasar-(?:datasource|destination|plugin)-.+|sdbe|onprem|electron)$$""".r

  val OnlyRevisionRepositories: Regex =
    raw"""^precog-(?:slamx)$$""".r

  def updateDependencies(
      repository: String,
      outdatedDependencies: Set[ModuleUpdateData],
      versions: ManagedVersions,
      log: Logger): Unit = {
    def getChange(isRevision: Boolean, isBreaking: Boolean): String =
      if (isRevision) "revision"
      else if (isBreaking) "breaking"
      else "feature"

    var isRevision = true
    var isBreaking = false
    var hasErrors = false

    outdatedDependencies map {
      case ModuleUpdateData(_, _, newRevision, dependencyRepository, _) =>
        (newRevision, dependencyRepository)
    } foreach {
      case (newRevision, dependencyRepository) =>
        versions.get(dependencyRepository) match {
          case Some(currentRevision) =>
            val currentVersion = VersionNumber(currentRevision)
            val newVersion = VersionNumber(newRevision)
            val testRevision =
              VersionNumber.SecondSegment.isCompatible(currentVersion, newVersion)
            val testBreaking = !VersionNumber.SemVer.isCompatible(currentVersion, newVersion)
            isRevision &&= testRevision
            isBreaking ||= testBreaking
            log.info(
              s"Updated ${getChange(testRevision, testBreaking)} $dependencyRepository $currentVersion -> " +
                s"$newRevision")

          case None =>
            // TODO: use scalafix to change build.sbt
            hasErrors ||= true
            log.error(s"$dependencyRepository not present on $VersionsPath")
            log.error(
              s"""Fix build.sbt by replacing the version of affected artifacts with 'managedVersions.value
                 |("$dependencyRepository")'""".stripMargin)
        }

        versions(dependencyRepository) = newRevision
    }

    val change = repository match {
      case RevisionRepositories() => Some("revision")
      case OnlyRevisionRepositories() =>
        Some(getChange(isRevision, isBreaking)).filter(_ == "revision")
      case _ => Some(getChange(isRevision, isBreaking))
    }

    change.foreach(c => log.info(s"version: $c"))

    if (hasErrors) sys.error("Unmanaged dependencies found!")
  }
}
