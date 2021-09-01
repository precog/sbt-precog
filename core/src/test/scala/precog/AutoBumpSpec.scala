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
import scala.collection.immutable
import scala.concurrent.duration.TimeUnit
import scala.language.experimental.macros
import scala.sys.process.ProcessLogger

import cats.data.StateT
import cats.effect.Clock
import cats.effect.IO
import cats.implicits._
import github4s.GHError.NotFoundError
import github4s.GHError.UnprocessableEntityError
import github4s.GHResponse
import github4s.domain._
import org.specs2.ScalaCheck
import org.specs2.execute.ResultImplicits
import org.specs2.main.CommandLine
import org.specs2.matcher.MatcherMacros
import org.specs2.mutable.Specification
import precog.AutoBump.ChangeLabel
import precog.AutoBump.ChangeLabel.Revision
import precog.AutoBump.UpdateBranch
import precog.AutoBump.Warnings.NoChangesError
import precog.AutoBump.Warnings.NotOldest
import precog.AutoBump.Warnings.PushError
import precog.AutoBump.Warnings.UpdateError
import precog.algebras.Runner.RunnerConfig
import precog.algebras._
import precog.domain._

class AutoBumpSpec(params: CommandLine)
    extends Specification
    with ScalaCheck
    with ResultImplicits
    with MatcherMacros {
  import AutoBumpSpec._

  "getOldestAutoBumpPullRequest" should {
    val getOldest = autobump.getOldestAutoBumpPullRequest[Test]

    "find autobump pull requests" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump", "", "trickle/branch", "master", "OPEN", true)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump"))
    }

    "get oldest autobump pull request" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump 1", "", "trickle/branch", "master", "OPEN", false)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "trickle/branch", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", false)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump 1"))
    }

    "ignore pull requests not to master" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/branch",
          "trickle/master",
          "OPEN",
          true)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "trickle/branch", "dev", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", true)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump 3"))
    }

    "ignore pull requests not from 'trickle/'" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump 1", "", "trickle", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "branch", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", true)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump 3"))
    }

    "ignore pull requests without autobump label" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump 1", "", "trickle/branch", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "trickle/branch", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", true)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump 2"))
    }

    "ignore closed pull requests" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump 1", "", "trickle/branch", "master", "CLOSED", true)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "trickle/branch", "master", "MERGED", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", true)

      val result = getOldest.runA(env).unsafeRunSync()

      result must beSome(matchA[PullRequestDraft].title("Auto Bump 3"))
    }
  }

  "draftPullRequest" should {
    val updateBranch =
      UpdateBranch(
        Runner.DefaultConfig,
        "trickle/branch",
        None,
        List("Something old, something new"),
        Some(Revision))
    val draft = autobump.draftPullRequest[Test](updateBranch)

    "create a pull request as draft" in {
      val env = TestEnv.empty

      val result = draft.runS(env).unsafeRunSync()

      result.prs((owner, repoSlug)) must haveSize(1)
      result.prs((owner, repoSlug)).head must matchA[PullRequestDraft].draft(true)
    }

    "create a pull request that is found by getOldestAutoBumpPullRequest" in {
      val env = TestEnv.empty
      val getOldest = autobump.getOldestAutoBumpPullRequest[Test]

      val (draftedPR, oldestPR) =
        draft.flatMap(draftedPR => getOldest.map(draftedPR -> _)).runA(env).unsafeRunSync()

      oldestPR must beSome(
        matchA[PullRequestDraft].body(beSome(endingWith("Something old, something new"))))
      draftedPR mustEqual oldestPR.get
    }
  }

  "draftPullRequest with no label" should {
    val updateBranch =
      UpdateBranch(
        Runner.DefaultConfig,
        "trickle/branch",
        None,
        List("Something old, something new"),
        None)
    val draft = autobump.draftPullRequest[Test](updateBranch)

    "create a pull request as draft" in {
      val env = TestEnv.empty

      val result = draft.runS(env).unsafeRunSync()

      result.prs((owner, repoSlug)) must haveSize(1)
      result.prs((owner, repoSlug)).head must matchA[PullRequestDraft].draft(true)
    }

    "create a pull request that is found by getOldestAutoBumpPullRequest" in {
      val env = TestEnv.empty
      val getOldest = autobump.getOldestAutoBumpPullRequest[Test]

      val (draftedPR, oldestPR) =
        draft.flatMap(draftedPR => getOldest.map(draftedPR -> _)).runA(env).unsafeRunSync()

      oldestPR must beSome(
        matchA[PullRequestDraft].body(beSome(endingWith("Something old, something new"))))
      draftedPR mustEqual oldestPR.get
    }
  }

  "tryUpdateDependencies" should {
    val successRunner = TestRunner("updateDependencies").responding {
      case ("git" :: _, _) => Nil
      case ("sbt" :: "trickleUpdateDependencies" :: _, _) =>
        """
          |[INFO] Updated revision org-repo-1 1.0.0 -> 1.0.1
          |[INFO] Updated feature org-repo-2 2.0.1 -> 2.1.0
          |[INFO] Updated breaking org-repo-3 3.1.2 -> 4.1.1
          |[INFO] version: breaking""".stripMargin.split('\n').toList
    }

    val successRunnerNoLabel = TestRunner("updateDependencies").responding {
      case ("git" :: _, _) => Nil
      case ("sbt" :: "trickleUpdateDependencies" :: _, _) =>
        """
          |[INFO] Updated revision org-repo-1 1.0.0 -> 1.0.1
          |[INFO] Updated feature org-repo-2 2.0.1 -> 2.1.0
          |[INFO] Updated breaking org-repo-3 3.1.2 -> 4.1.1""".stripMargin.split('\n').toList
    }

    val successRunnerWithSbt = TestRunner("localSbt").responding {
      case (List("test", "-x", "./sbt"), _) => Nil
      case ("git" :: _, _) => Nil
      case ("./sbt" :: "trickleUpdateDependencies" :: _, _) =>
        """
          |[INFO] Updated revision org-repo-1 1.0.0 -> 1.0.1
          |[INFO] Updated feature org-repo-2 2.0.1 -> 2.1.0
          |[INFO] Updated breaking org-repo-3 3.1.2 -> 4.1.1
          |[INFO] version: breaking""".stripMargin.split('\n').toList
    }

    val noUpdatesRunner = TestRunner("noUpdates").responding {
      case ("git" :: _, _) => Nil
      case ("sbt" :: _, _) => Nil
    }

    "update dependencies" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val state = tryUpdate.runS(env).unsafeRunSync()

      state.cmds must contain(
        allOf(
          ("", "mkdir sbt-precog-updateDependencies"),
          ("sbt-precog-updateDependencies", "sbt trickleUpdateDependencies")))
    }

    "extract label and changes when updating dependencies" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val result = tryUpdate.runA(env).unsafeRunSync()

      result must matchA[UpdateBranch]
        .label(Some(ChangeLabel.Breaking))
        .changes(List(
          "Updated **revision** org-repo-1 `1.0.0` → `1.0.1`",
          "Updated **feature** org-repo-2 `2.0.1` → `2.1.0`",
          "Updated **breaking** org-repo-3 `3.1.2` → `4.1.1`"
        ))
    }

    "extract no label when no label is produced" in {
      implicit val runner: Runner[Test] = successRunnerNoLabel
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val result = tryUpdate.runA(env).unsafeRunSync()

      result must matchA[UpdateBranch]
        .label(None)
        .changes(List(
          "Updated **revision** org-repo-1 `1.0.0` → `1.0.1`",
          "Updated **feature** org-repo-2 `2.0.1` → `2.1.0`",
          "Updated **breaking** org-repo-3 `3.1.2` → `4.1.1`"
        ))
    }

    "not fail if dependencies have already been updated" in {
      implicit val runner: Runner[Test] = noUpdatesRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      tryUpdate.runA(env).unsafeRunSync().label must beNone
    }

    "fetch all branches when cloning" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val state = tryUpdate.runS(env).unsafeRunSync()

      state.cmds must contain(
        ("sbt-precog-updateDependencies", "git clone --depth 1 --no-single-branch toClone ."))
    }

    "checkout existing branch if there's an open PR" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(owner, repoSlug, "Auto Bump 1", "", "trickle/branch", "master", "OPEN", false)
        .withPR(owner, repoSlug, "Auto Bump 2", "", "trickle/branch", "master", "OPEN", true)
        .withPR(owner, repoSlug, "Auto Bump 3", "", "trickle/branch", "master", "OPEN", false)
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val result = tryUpdate.runA(env).unsafeRunSync()

      result must matchA[UpdateBranch].pullRequest(
        beSome(matchA[PullRequestDraft].title("Auto Bump 1")))
    }

    "checkout new branch if there isn't an open PR" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val (state, result) = tryUpdate.run(env).unsafeRunSync()

      state must matchA[TestEnv].cmds(
        contain(("sbt-precog-updateDependencies", "git checkout -b trickle/version-bump-1")))
      result must matchA[UpdateBranch].pullRequest(beNone)
    }

    "return a runner in the checked out branch folder" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate = autobump.tryUpdateDependencies[Test](Runner.DefaultConfig)

      val result = tryUpdate.runA(env).unsafeRunSync()

      result must matchA[UpdateBranch].runnerConf(
        matchA[RunnerConfig].cwd(beSome(new File("sbt-precog-updateDependencies"))))
    }

    "use local sbt if SBT is set to './sbt'" in {
      implicit val runner: Runner[Test] = successRunnerWithSbt
      val env = TestEnv.empty
      val tryUpdate =
        autobump.tryUpdateDependencies[Test](Runner.DefaultConfig.withEnv("SBT" -> "./sbt"))

      val state = tryUpdate.runS(env).unsafeRunSync()

      state.cmds must contain(("sbt-precog-localSbt", "./sbt trickleUpdateDependencies"))
    }

    "fallback to 'sbt' if SBT is set to './sbt' but it does not exist" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val tryUpdate =
        autobump.tryUpdateDependencies[Test](Runner.DefaultConfig.withEnv("SBT" -> "./sbt"))

      val state = tryUpdate.runS(env).unsafeRunSync()

      state.cmds must contain(
        ("sbt-precog-updateDependencies", "sbt trickleUpdateDependencies"))
    }
  }

  "verifyUpdateDependencies" should {
    val updateBranch = UpdateBranch(
      Runner.DefaultConfig,
      "trickle/branch-to-push",
      None,
      Nil,
      Some(ChangeLabel.Revision))
    val updateBranchNoLabel =
      UpdateBranch(Runner.DefaultConfig, "trickle/branch-to-push", None, Nil, None)

    "be right if nothing fails" in {
      implicit val runner: Runner[Test] = TestRunner("noUpdates").responding {
        case ("sbt" :: _, _) => Nil
      }
      val env = TestEnv.empty
      val verifyUpdate = autobump.verifyUpdateDependencies[Test](updateBranch)

      val result = verifyUpdate.runA(env).unsafeRunSync()

      result must beRight
    }

    "be right with no label" in {
      implicit val runner: Runner[Test] = TestRunner("noUpdates").responding {
        case ("sbt" :: _, _) => Nil
      }
      val env = TestEnv.empty
      val verifyUpdate = autobump.verifyUpdateDependencies[Test](updateBranchNoLabel)

      val result = verifyUpdate.runA(env).unsafeRunSync()

      result must beRight
    }

    "return UpdateError if there are missing dependencies" in {
      implicit val runner: Runner[Test] = TestRunner("noUpdates").responding {
        case (List("sbt", "trickleIsUpToDate"), _) => Nil
      }
      val env = TestEnv.empty
      val verifyUpdate = autobump.verifyUpdateDependencies[Test](updateBranch)

      val result = verifyUpdate.runA(env).unsafeRunSync()

      result must beLeft(UpdateError)
    }

    "fail if project versions are not up-to-date" in {
      implicit val runner: Runner[Test] = TestRunner("noUpdates").responding {
        case (List("sbt", "update"), _) => Nil
      }
      val env = TestEnv.empty
      val verifyUpdate = autobump.verifyUpdateDependencies[Test](updateBranch)

      val result = verifyUpdate.runA(env).attempt.unsafeRunSync()

      result must beLeft
    }
  }

  "tryCommit" should {
    val updateBranch = UpdateBranch(
      Runner.DefaultConfig,
      "trickle/branch-to-push",
      None,
      Nil,
      Some(ChangeLabel.Revision))
    val updateBranchNoLabel =
      UpdateBranch(Runner.DefaultConfig, "trickle/branch-to-push", None, Nil, None)

    "commit changes with the bot as the author and this repo in the commit title" in {
      implicit val runner: Runner[Test] = TestRunner("commit").responding {
        case ("git" :: _, envVars) =>
          assert(envVars("GIT_AUTHOR_NAME") == "Precog Bot (thisRepo)")
          assert(envVars("GIT_AUTHOR_EMAIL") == "bot@precog.com")
          assert(envVars("GIT_COMMITTER_NAME") == "Precog Bot (thisRepo)")
          assert(envVars("GIT_COMMITTER_EMAIL") == "bot@precog.com")
          Nil
      }
      val env = TestEnv.empty
      val tryCommit = autobump.tryCommit[Test](updateBranch)

      val (state, result) = tryCommit.run(env).unsafeRunSync()

      result must beRight
      state.cmds must contain(beLike[(String, String)] {
        case (_, cmd) => cmd must beMatching("git commit -m .*thisRepo.*")
      })
    }

    "commit changes with no label" in {
      implicit val runner: Runner[Test] = TestRunner("commit").responding {
        case ("git" :: _, envVars) =>
          assert(envVars("GIT_AUTHOR_NAME") == "Precog Bot (thisRepo)")
          assert(envVars("GIT_AUTHOR_EMAIL") == "bot@precog.com")
          assert(envVars("GIT_COMMITTER_NAME") == "Precog Bot (thisRepo)")
          assert(envVars("GIT_COMMITTER_EMAIL") == "bot@precog.com")
          Nil
      }
      val env = TestEnv.empty
      val tryCommit = autobump.tryCommit[Test](updateBranchNoLabel)

      val (state, result) = tryCommit.run(env).unsafeRunSync()

      result must beRight
      state.cmds must contain(beLike[(String, String)] {
        case (_, cmd) => cmd must beMatching("git commit -m .*thisRepo.*")
      })
    }

    "return NoChangesError if committing fails" in {
      implicit val runner: Runner[Test] = TestRunner("commit").responding {
        case (List("git", "add", "."), _) => Nil
      }
      val env = TestEnv.empty
      val tryCommit = autobump.tryCommit[Test](updateBranch)

      val result = tryCommit.runA(env).unsafeRunSync()

      result must beLeft(NoChangesError)
    }
  }

  "tryPush" should {
    val updateBranch = UpdateBranch(
      Runner.DefaultConfig,
      "trickle/branch-to-push",
      None,
      Nil,
      Some(ChangeLabel.Revision))
    val updateBranchNoLabel =
      UpdateBranch(Runner.DefaultConfig, "trickle/branch-to-push", None, Nil, None)

    "push branch to origin" in {
      implicit val runner: Runner[Test] = TestRunner("push").responding {
        case ("git" :: _, _) => Nil
      }
      val env = TestEnv.empty
      val tryCommit = autobump.tryPush[Test](updateBranch)

      val (state, result) = tryCommit.run(env).unsafeRunSync()

      result must beRight
      state.cmds must contain(("", "git push origin trickle/branch-to-push"))
    }

    "push branch with no label" in {
      implicit val runner: Runner[Test] = TestRunner("push").responding {
        case ("git" :: _, _) => Nil
      }
      val env = TestEnv.empty
      val tryCommit = autobump.tryPush[Test](updateBranchNoLabel)

      val (state, result) = tryCommit.run(env).unsafeRunSync()

      result must beRight
      state.cmds must contain(("", "git push origin trickle/branch-to-push"))
    }

    "return PushError on failure" in {
      implicit val runner: Runner[Test] = TestRunner("push fail")
      val env = TestEnv.empty
      val tryCommit = autobump.tryPush[Test](updateBranch)

      val result = tryCommit.runA(env).unsafeRunSync()

      result must beLeft(PushError)
    }
  }

  "createOrUpdatePullRequest" should {
    "create pull request and mark as ready if none exists" in {
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/branch-to-create",
        None,
        Nil,
        Some(ChangeLabel.Revision))
      val env = TestEnv.empty
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val (state, result) = createOrUpdate.run(env).unsafeRunSync()

      result must beRight(
        matchA[PullRequestDraft]
          .number(1)
          .head(beSome(matchA[PullRequestBase].ref("trickle/branch-to-create")))
          .base(beSome(matchA[PullRequestBase].ref("master")))
          .draft(beFalse))

      state.labels.get((owner, repoSlug, 1)) must beSome(
        contain(
          matchA[Label].name(ChangeLabel.Revision.label),
          matchA[Label].name(AutoBump.AutoBumpLabel)))

      state.prs.get((owner, repoSlug)).flatMap(_.headOption) must beSome(
        matchA[PullRequestDraft].draft(beFalse))
    }

    "create pull request with no label" in {
      val updateBranch =
        UpdateBranch(Runner.DefaultConfig, "trickle/branch-to-create", None, Nil, None)
      val env = TestEnv.empty
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val (state, result) = createOrUpdate.run(env).unsafeRunSync()

      result must beRight(
        matchA[PullRequestDraft]
          .number(1)
          .head(beSome(matchA[PullRequestBase].ref("trickle/branch-to-create")))
          .base(beSome(matchA[PullRequestBase].ref("master")))
          .draft(beFalse))

      state.labels.get((owner, repoSlug, 1)) must beSome(
        contain(matchA[Label].name(AutoBump.AutoBumpLabel)))

      state.prs.get((owner, repoSlug)).flatMap(_.headOption) must beSome(
        matchA[PullRequestDraft].draft(beFalse))
    }

    "update change label and mark pull request as ready if oldest" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, ChangeLabel.Revision.label)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 2",
          "",
          "trickle/merged-branch",
          "master",
          "MERGED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 3",
          "",
          "trickle/existing-branch",
          "master",
          "OPEN",
          true)
      val existingPR = env.prs((owner, repoSlug)).find(_.state == "OPEN")
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/existing-branch",
        existingPR,
        Nil,
        Some(ChangeLabel.Feature))
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val (state, result) = createOrUpdate.run(env).unsafeRunSync()

      result must beRight(matchA[PullRequestDraft].title("Auto Bump 3"))

      state.labels.get((owner, repoSlug, 3)) must beSome(
        contain(
          matchA[Label].name(ChangeLabel.Feature.label),
          matchA[Label].name(AutoBump.AutoBumpLabel)))

      state.labels.get((owner, repoSlug, 3)) must beSome(
        not(contain(matchA[Label].name(ChangeLabel.Revision.label))))

      state.prs((owner, repoSlug)).find(_.title == "Auto Bump 3") must beSome(
        matchA[PullRequestDraft].draft(beFalse))
    }

    "update and mark pull request with no label as ready if oldest" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 2",
          "",
          "trickle/merged-branch",
          "master",
          "MERGED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 3",
          "",
          "trickle/existing-branch",
          "master",
          "OPEN",
          true)
      val existingPR = env.prs((owner, repoSlug)).find(_.state == "OPEN")
      val updateBranch =
        UpdateBranch(Runner.DefaultConfig, "trickle/existing-branch", existingPR, Nil, None)
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val (state, result) = createOrUpdate.run(env).unsafeRunSync()

      result must beRight(matchA[PullRequestDraft].title("Auto Bump 3"))

      state.labels.get((owner, repoSlug, 3)) must beSome(
        contain(matchA[Label].name(AutoBump.AutoBumpLabel)))

      state.prs((owner, repoSlug)).find(_.title == "Auto Bump 3") must beSome(
        matchA[PullRequestDraft].draft(beFalse))
    }

    "return NotOldest if another pull request is the oldest" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, ChangeLabel.Revision.label)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 2",
          "",
          "trickle/older-branch",
          "master",
          "OPEN",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 3",
          "",
          "trickle/existing-branch",
          "master",
          "OPEN",
          true)
      val olderPR = env.prs((owner, repoSlug)).find(_.title == "Auto Bump 2")
      val existingPR = env.prs((owner, repoSlug)).find(_.title == "Auto Bump 3")
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/existing-branch",
        existingPR,
        Nil,
        Some(ChangeLabel.Revision))
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val result = createOrUpdate.runA(env).unsafeRunSync()

      result must beLeft(NotOldest(olderPR, existingPR.get))
    }

    "return NotOldest if no pull request is the oldest" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
      val existingPR = env.prs((owner, repoSlug)).find(_.title == "Auto Bump")
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/existing-branch",
        existingPR,
        Nil,
        Some(ChangeLabel.Revision))
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val result = createOrUpdate.runA(env).unsafeRunSync()

      result must beLeft(NotOldest(None, existingPR.get))
    }

    "close pull request and delete branch if not oldest" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, ChangeLabel.Revision.label)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 2",
          "",
          "trickle/older-branch",
          "master",
          "OPEN",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 3",
          "",
          "trickle/existing-branch",
          "master",
          "OPEN",
          true)
      val existingPR = env.prs((owner, repoSlug)).find(_.title == "Auto Bump 3")
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/existing-branch",
        existingPR,
        Nil,
        Some(ChangeLabel.Revision))
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val state = createOrUpdate.runS(env).unsafeRunSync()

      state.prs((owner, repoSlug)).find(_.number == 3).get.state mustEqual "CLOSED"
      state.refs((owner, repoSlug)) must not(contain("trickle/existing-branch"))
    }

    "not fail if pull request has been closed and branch deleted" in {
      val env = TestEnv
        .empty
        .withLabel(owner, repoSlug, 1, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 1, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 2, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 2, ChangeLabel.Revision.label)
        .withLabel(owner, repoSlug, 3, AutoBump.AutoBumpLabel)
        .withLabel(owner, repoSlug, 3, ChangeLabel.Revision.label)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 1",
          "",
          "trickle/closed-branch",
          "master",
          "CLOSED",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 2",
          "",
          "trickle/older-branch",
          "master",
          "OPEN",
          true)
        .withPR(
          owner,
          repoSlug,
          "Auto Bump 3",
          "",
          "trickle/existing-branch",
          "master",
          "OPEN",
          true)
      val existingPR = env.prs((owner, repoSlug)).find(_.title == "Auto Bump 3")
      val updateBranch = UpdateBranch(
        Runner.DefaultConfig,
        "trickle/existing-branch",
        existingPR,
        Nil,
        Some(ChangeLabel.Revision))
      val createOrUpdate = autobump.createOrUpdatePullRequest[Test](updateBranch)

      val state1 = createOrUpdate.runS(env).unsafeRunSync()
      val result = createOrUpdate.runA(state1).attempt.unsafeRunSync()

      result must beRight
    }
  }

  "createPullRequest" should {
    val successRunner = TestRunner("updateDependencies").responding {
      case ("git" :: _, _) => Nil
      case ("sbt" :: "trickleUpdateDependencies" :: _, _) =>
        """
          |[INFO] Updated revision org-repo-1 1.0.0 -> 1.0.1
          |[INFO] Updated feature org-repo-2 2.0.1 -> 2.1.0
          |[INFO] Updated breaking org-repo-3 3.1.2 -> 4.1.1
          |[INFO] version: breaking""".stripMargin.split('\n').toList
      case ("sbt" :: _, _) => Nil
    }

    "create pull requests that it identifies as its own" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val createPR = autobump.createPullRequest[Test](Runner.DefaultConfig)
      val getOldest = autobump.getOldestAutoBumpPullRequest[Test]

      val (state1, success) = createPR.run(env).unsafeRunSync()
      val result = getOldest.runA(state1).unsafeRunSync()

      success must beTrue
      result must beSome
    }

    "create pull requests with a version label" in {
      implicit val runner: Runner[Test] = successRunner
      val env = TestEnv.empty
      val createPR = autobump.createPullRequest[Test](Runner.DefaultConfig)

      val state = createPR.runS(env).unsafeRunSync()

      state.labels.get((owner, repoSlug, 1)) must beSome(
        contain(
          matchA[Label].name(ChangeLabel.Breaking.label),
          matchA[Label].name(AutoBump.AutoBumpLabel)))
    }
  }

}

object AutoBumpSpec {
  type Test[A] = StateT[IO, TestEnv, A]

  val log = sbt.util.Logger.Null
  val owner = "thatOrg"
  val repoSlug = "thatRepo"
  val autobump = new AutoBump("thisRepo", owner, repoSlug, "toClone", log)

  implicit val clockTest: Clock[Test] = new Clock[Test] {

    override def realTime(unit: TimeUnit): Test[Long] = StateT { env =>
      val newState = env.copy(time = env.time + 1)
      IO.pure(newState -> newState.time)
    }

    override def monotonic(unit: TimeUnit): Test[Long] = StateT { env =>
      val newState = env.copy(time = env.time + 1)
      IO.pure(newState -> newState.time)
    }
  }

  implicit val draftPullRequestsTest: DraftPullRequests[Test] = new DraftPullRequests[Test] {
    override def draftPullRequest(
        owner: String,
        repo: String,
        newPullRequest: NewPullRequest,
        head: String,
        base: String,
        maintainerCanModify: Option[Boolean],
        headers: Map[String, String]): Test[GHResponse[PullRequestDraft]] = {
      StateT { env =>
        val NewPullRequestData(title, body) = newPullRequest
        val newEnv = env.withPR(owner, repo, title, body, head, base, "OPEN", true)
        val result = newEnv.prs((owner, repo)).head
        IO.pure(newEnv -> GHResponse(result.asRight, 200, Map.empty))
      }
    }

    override def listPullRequests(
        owner: String,
        repo: String,
        filters: List[PRFilter],
        pagination: Option[Pagination],
        headers: Map[String, String]): Test[GHResponse[List[PullRequestDraft]]] = {
      StateT.inspect { env =>
        val all = env.prs.getOrElse((owner, repo), Nil)
        val filtered = filters.foldLeft(all) {
          case (prs, PRFilterOpen) => prs.filter(_.state == "OPEN")
          case (prs, PRFilterClosed) => prs.filterNot(_.state == "OPEN")
          case (prs, PRFilterHead(ref)) => prs.filter(_.head.exists(_.ref == ref))
          case (prs, PRFilterBase(ref)) => prs.filter(_.base.exists(_.ref == ref))
          case (prs, PRFilterSortCreated) => prs.sortBy(_.created_at)
          case (prs, PRFilterSortUpdated) => prs.sortBy(_.updated_at)
          case (prs, _) => prs
        }
        val sorted =
          if (filters.contains(PRFilterOrderDesc)) filtered.reverse
          else filtered
        GHResponse(sorted.asRight, 200, Map.empty)
      }
    }

    override def updatePullRequest(
        owner: String,
        repo: String,
        number: Int,
        fields: PullRequestUpdate,
        headers: Map[String, String]): Test[GHResponse[PullRequestDraft]] = {
      StateT { env =>
        val prs = env.prs.getOrElse((owner, repo), Nil)
        val updated = prs map { pr =>
          if (pr.number == number) {
            val pr1 = fields
              .base
              .map(ref => pr.copy(base = pr.base.map(_.copy(ref = ref))))
              .getOrElse(pr)
            val pr2 = pr1.copy(body = fields.body)
            val pr3 =
              fields.maintainer_can_modify.map(flag => pr2.copy(locked = !flag)).getOrElse(pr2)
            val pr4 = fields.state.map(state => pr3.copy(state = state)).getOrElse(pr3)
            val pr5 = fields.title.map(title => pr4.copy(title = title)).getOrElse(pr4)
            pr5
          } else pr
        }
        val newEnv = env.copy(prs = env.prs.updated((owner, repo), updated))
        val result = updated
          .find(_.number == number)
          .toRight(NotFoundError(s"$owner/$repo#$number not found", ""))
        IO.pure(newEnv -> GHResponse(result, 200, Map.empty))
      }
    }

    override def markReadyForReview(
        owner: String,
        repo: String,
        id: String,
        headers: Map[String, String]): Test[GHResponse[Boolean]] = {
      StateT { env =>
        val prs = env.prs.getOrElse((owner, repo), Nil) map { pr =>
          if (pr.node_id != id) pr
          else pr.copy(draft = false)
        }
        val newEnv = env.copy(prs = env.prs.updated((owner, repo), prs))
        val result = prs.find(_.node_id == id).exists(!_.draft)
        IO.pure(newEnv -> GHResponse(result.asRight, 200, Map.empty))
      }
    }
  }

  implicit val labelsTest: Labels[Test] = new Labels[Test] {
    override def listLabels(
        owner: String,
        repo: String,
        number: Int,
        pagination: Option[Pagination],
        headers: Map[String, String]): Test[GHResponse[List[Label]]] = {
      StateT.inspect { env =>
        val labelList = env.labels.getOrElse((owner, repo, number), Nil)
        GHResponse(labelList.asRight, 200, Map.empty)
      }
    }

    override def addLabels(
        owner: String,
        repo: String,
        number: Int,
        labels: List[String],
        headers: Map[String, String]): Test[GHResponse[List[Label]]] = {
      StateT { env =>
        val newEnv = labels.foldLeft(env) {
          case (currEnv, label) => currEnv.withLabel(owner, repo, number, label)
        }
        val result = newEnv.labels((owner, repo, number))
        IO.pure(newEnv -> GHResponse(result.asRight, 200, Map.empty))
      }
    }

    override def removeLabel(
        owner: String,
        repo: String,
        number: Int,
        label: String,
        headers: Map[String, String]): Test[GHResponse[List[Label]]] = {
      StateT { env =>
        val result = env.labels.getOrElse((owner, repo, number), Nil).filterNot(_.name == label)
        val newEnv = env.copy(labels = env.labels.updated((owner, repo, number), result))
        IO.pure(newEnv -> GHResponse(result.asRight, 200, Map.empty))
      }
    }
  }

  implicit val referencesTest: References[Test] = new References[Test] {
    override def deleteReference(
        owner: String,
        repo: String,
        ref: String,
        headers: Map[String, String]): Test[GHResponse[Unit]] = {
      StateT { env =>
        IO.delay {
          assert(ref.startsWith("refs/heads/"))
          val branch = ref.substring("refs/heads/".length)
          env.refs.get((owner, repo)) match {
            case Some(refs) if refs(branch) =>
              val newEnv = env.copy(refs = env.refs.updated((owner, repo), refs - branch))
              newEnv -> GHResponse(().asRight, 200, Map.empty)
            case _ =>
              env -> GHResponse(
                UnprocessableEntityError("Reference does not exist", Nil).asLeft,
                422,
                Map.empty)
          }
        }
      }
    }
  }

  case class TestRunner(
      suffix: String,
      config: RunnerConfig = Runner.DefaultConfig,
      cmdResp: PartialFunction[(List[String], Map[String, String]), List[String]] =
        PartialFunction.empty)
      extends Runner[Test] {

    override def withConfig(config: RunnerConfig): Runner[Test] = {
      copy(config = config)
    }

    def responding(
        pf: PartialFunction[(List[String], Map[String, String]), List[String]]): Runner[Test] =
      copy(cmdResp = pf)

    override def cdTemp(prefix: String): Test[RunnerConfig] = {
      StateT { env =>
        val tempDir = s"$prefix-$suffix"
        val cwd = config.cwd.map(_.getPath).getOrElse("")
        val newEnv = env.copy(cmds = (cwd, s"mkdir $tempDir") :: env.cmds)
        val result = config.cd(new File(tempDir))
        IO.pure(newEnv -> result)
      }
    }

    override def !!(command: String): Test[List[String]] = this ! command

    override def !!(command: immutable.Seq[String]): Test[List[String]] = this ! command

    override def !(command: String): Test[List[String]] = {
      this ! command.split("""\s+""").toVector
    }

    override def !(command: immutable.Seq[String]): Test[List[String]] = {
      StateT { env =>
        val cwd = config.cwd.map(_.getPath).getOrElse("")
        val newEnv = env.copy(cmds = (cwd, command.mkString(" ")) :: env.cmds)
        IO.delay(newEnv -> cmdResp((command.toList, config.env)))
      }
    }

    override def ?(command: immutable.Seq[String], processLogger: ProcessLogger): Test[Int] = {
      StateT { env =>
        val cwd = config.cwd.map(_.getPath).getOrElse("")
        val newEnv = env.copy(cmds = (cwd, command.mkString(" ")) :: env.cmds)
        val result = if (cmdResp.isDefinedAt((command.toList, config.env))) 0 else 1
        IO.delay(newEnv -> result)
      }
    }
  }
}
