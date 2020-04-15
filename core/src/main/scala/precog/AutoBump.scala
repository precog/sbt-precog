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

import org.http4s.Uri

import cats.{Monad, Order}
import cats.data.EitherT
import cats.effect.IO.contextShift
import cats.effect.{ContextShift, IO, Sync}
import cats.implicits._
import github4s.GithubResponses.{GHException, GHResponse, GHResult}
import github4s.domain._
import precog.domain.{PullRequestDraft, PullRequestUpdate}
import sbt.{Logger, url}
import sbttrickle.metadata.OutdatedRepository
import fs2.{Chunk, Stream}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.util.matching.Regex

object AutoBump {
  sealed trait Warnings extends Product with Serializable {
    def warn(log: Logger): IO[Unit]
  }
  object Warnings {
    case object UpdateError extends Warnings {
      def warn(log: Logger): IO[Unit] = IO {
        log.warn("was unable to run `sbt update` following the trickle application")
        log.warn("this may mean that the some artifacts are not yet propagated; skipping")
      }
    }
    case object NoChangesError extends Warnings {
      def warn(log: Logger): IO[Unit] = IO {
        log.warn("git-commit exited with error")
        log.warn("this usually means the target repository was *already* at the latest version but hasn't published yet")
        log.warn("you should check for a stuck trickle PR on that repository")
      }
    }
    case object PushError extends Warnings {
      def warn(log: Logger): IO[Unit] = IO {
        log.warn("git-push exited with error")
        log.warn("this usually means some other repository updated the pull request before this one")
      }
    }
    final case class NotOldest(maybeOldest: Option[PullRequestDraft], draft: PullRequestDraft) extends Warnings {
      def warn(log: Logger): IO[Unit] = IO {
        maybeOldest match {
          case Some(oldest) =>
            log.warn(s"pull request ${draft.number} is newer than existing pull request ${oldest.number}")
            log.warn("this usually means two or more repositories finished build at the same time,")
            log.warn("and some other repository beat this one to pull request creation.")
          case None =>
            log.warn(s"pull request ${draft.number} was not found")
            log.warn("this usually means that it was merged before we could update it")
            log.warn("check that it was closed manually or merged")
        }
      }
    }
  }

  sealed abstract class ChangeLabel(val label: String) extends Product with Serializable
  object ChangeLabel {
    case object Revision extends ChangeLabel("version: revision")
    case object Feature extends ChangeLabel("version: feature")
    case object Breaking extends ChangeLabel("version: breaking")

    val values: List[ChangeLabel] = List(Revision, Feature, Breaking)
    val order: Map[ChangeLabel, Int] = values.zipWithIndex.toMap
    implicit val ordering: Order[ChangeLabel] = Order.by(order)
    val fromString: Map[String, ChangeLabel] = values.map(change => change.label -> change).toMap
    val labelPattern: Regex = values.map(_.label).mkString("|").r

    def apply(label: String): Option[ChangeLabel] = {
      fromString.get(label)
    }

    def unapply(arg: String): Option[ChangeLabel] =
      labelPattern.findFirstIn(arg).flatMap(apply)
  }

  implicit class UnpackSyntax[F[_]: Monad, A](response: F[GHResponse[A]]) {
    def unpack: F[Either[GHException, A]] = EitherT(response).map(_.result).value
  }

  val AutoBumpLabel = ":robot:"
  val PullRequestFilters: List[PRFilter] = List(PRFilterOpen, PRFilterSortCreated, PRFilterOrderAsc, PRFilterBase("master"))
  val LinkRelation: Regex = """<(.*?)>; rel="(\w+)"""".r
  val PerPage = 100

  def autoBumpCommitTitle(author: String): String = s"Applied dependency updates by $author"

  def autoPage[F[_]: Sync, T](
      first: Pagination)(
      call: Pagination => F[Either[GHException, GHResult[List[T]]]])
      : Stream[F, T] = {
    val chunker = call.andThen(_.rethrow.map(res => nextPage(getRelations(res.headers)).map(Chunk.seq(res.result) -> _)))
    Stream.unfoldChunkEval(first)(chunker)
  }

  def nextPage(relations: Map[String, (Int, Int)]): Option[Pagination] = {
    relations.get("next").map((Pagination.apply _).tupled)
  }

  /** Decodes github's "Link" header into a map */
  def getRelations(headers: Map[String, String]): Map[String, (Int, Int)] = {
    val relations = for {
      linkValue <- headers collect { case (header, value) if header.toLowerCase == "link" => value }
      LinkRelation(url, relation) <- LinkRelation.findAllMatchIn(linkValue)
      uri <- Uri.fromString(url).toSeq
      page <- uri.params.get("page")
      pageNum <- Try(page.toInt).toOption
      perPage <- uri.params.get("per_page").orElse(Some(PerPage.toString)) // Add a default, just in case
      perPageNum <- Try(perPage.toInt).toOption
    } yield (relation, (pageNum, perPageNum))
    relations.toMap
  }

  /** Extract change label from trickleUpdateDependencies log */
  def extractLabel(lines: List[String]): ChangeLabel = {
    lines collectFirst {
      case ChangeLabel(label) => label
    } getOrElse sys.error("Change label not found!")
  }

  /** Extract updated versions from trickleUpdateDependencies log */
  def extractChanges(lines: List[String]): List[String] = {
    lines.filter(_ contains "Updated ").map(line => line.substring(line.indexOf("Updated ")))
  }

  def getBranch(pullRequest: Option[PullRequestDraft]): IO[(String, String)] = IO {
    pullRequest
      .map("" -> _.head.get.ref)
      .getOrElse("-b" -> s"trickle/version-bump-${System.currentTimeMillis()}")
  }

  def isAutoBump(pullRequest: PullRequestDraft, labels: List[Label]): Boolean = {
    pullRequest.head.exists(_.ref.startsWith("trickle/")) && labels.exists(_.name == AutoBumpLabel)
  }
}

class AutoBump(authorRepository: String, repository: OutdatedRepository, token: String, log: Logger) {
  import AutoBump._

  assert(url(repository.url).getHost == "github.com")

  implicit private val IOContextShift: ContextShift[IO] = contextShift(global)
  val github: Github[IO] = Github[IO](Some(token))
  val (owner, repoSlug) = repository.ownerAndRepository.getOrElse(sys.error(s"invalid url ${repository.url}"))
  val sbt: String = sys.env.getOrElse("SBT", "sbt")
  val authenticated = s"https://_:$token@github.com/$owner/$repoSlug"

  // TODO: check whether PR is mergeable?
  /**
   * Finds primary open autobump pull request, if one exists.
   *
   * @return Oldest autobump pull request, by order of creation
   */
  def getOldestAutoBumpPullRequest: IO[Option[PullRequestDraft]] = {
    getPullRequests
      .evalFilter(pullRequest => getLabels(pullRequest.number).map(labels => isAutoBump(pullRequest, labels)))
      .head
      .compile
      .toList
      .map(_.headOption)
  }

  def getPullRequests: Stream[IO, PullRequestDraft] = {
    autoPage(Pagination(1, PerPage)) { pagination =>
      github.pullRequests.listDraftPullRequests(owner, repoSlug, PullRequestFilters, Some(pagination))
    }
  }

  // FIXME: current github4s api does not support Some(pagination)
  def getLabels(pr: Int): IO[List[Label]] = {
    github.issues.listLabels(owner, repoSlug, pr).rethrow.map(_.result)
  }

  def draftPullRequest(authorRepository: String, branchName: String, changes: String): IO[PullRequestDraft] = {
    for {
      response <- github
        .pullRequests
        .draftPullRequest(
          owner,
          repoSlug,
          NewPullRequestData(
            autoBumpCommitTitle(authorRepository),
            s"This PR brought to you by sbt-trickle via $authorRepository. Changes:\n\n$changes"),
          branchName,
          "master")
        .rethrow
      pr = response.result
      _ <- assignLabel(AutoBumpLabel, pr).rethrow
    } yield pr
  }

  def assignLabel(label: String, pullRequest: PullRequestDraft): IO[GHResponse[List[Label]]] = {
    github
      .issues
      .addLabels(owner, repoSlug, pullRequest.number, List(label))
  }

  def markReady(pullRequest: PullRequestDraft): IO[Unit] = {
    github
      .pullRequests
      .markReadyForReview(owner, repoSlug, pullRequest.node_id)
      .rethrow
      .ensure(new RuntimeException(s"Failed to mark pull request ${pullRequest.number} ready for review"))(_.result)
      .void
  }

  def close(pullRequest: PullRequestDraft, title: String): IO[GHResponse[PullRequestDraft]] = {
    val requestUpdate = PullRequestUpdate(title = Some(title), state = Some("closed"))
    github.pullRequests.updatePullRequest(owner, repoSlug, pullRequest.number, requestUpdate)
  }

  def deleteBranch(pullRequest: PullRequestDraft): IO[Unit] = {
    (pullRequest.head map { base =>
      val branch = s"refs/heads/${base.ref}"
      github.gitData.deleteReference(owner, repoSlug, branch).rethrow
    }).sequence.void
  }

  def removeLabel(pullRequest: PullRequestDraft, label: String): IO[GHResult[List[Label]]] = {
    github.issues.removeLabel(owner, repoSlug, pullRequest.number, label).rethrow
  }

  def tryUpdateDependencies: IO[Either[Warnings, (File, String, Option[PullRequestDraft], List[String], ChangeLabel)]] = {
    for {
      dir <- IO(Files.createTempDirectory("sbt-precog"))
      dirFile = dir.toFile
      _ <- Runner[IO](log) ! s"git clone --depth 1 $authenticated ${dirFile.getPath}"
      oldestPullRequest <- getOldestAutoBumpPullRequest
      (flag, branchName) <- getBranch(oldestPullRequest)
      runner = Runner[IO](log).cd(dirFile)
      _ <- runner ! s"git checkout $flag $branchName"
      lines <- runner ! s"$sbt trickleUpdateDependencies"
      changes = extractChanges(lines)
      label = extractLabel(lines)
      _ <- runner ! s"$sbt trickleIsUpToDate"
      updateResult <- (runner.stderrToStdout ! s"$sbt update").attempt
    } yield updateResult.bimap(_ => Warnings.UpdateError, _ => (dirFile, branchName, oldestPullRequest, changes, label))
  }

  // TODO: add changes to commit message?
  def tryCommit(dirFile: File): IO[Either[Warnings, Unit]] = {
    val runner = Runner[IO](log)
      .cd(dirFile)
      .stderrToStdout
      .withEnv(
        "GIT_AUTHOR_NAME" -> s"Precog Bot ($authorRepository)",
        "GIT_AUTHOR_EMAIL" -> "bot@precog.com",
        "GIT_COMMITTER_NAME" -> s"Precog Bot ($authorRepository)",
        "GIT_COMMITTER_EMAIL" -> "bot@precog.com")
    for {
      _ <- runner ! s"git add ."
      result <- (runner ! Seq("git", "commit", "-m", autoBumpCommitTitle(authorRepository))).void.attempt
    } yield result.leftMap(_ => Warnings.NoChangesError)
  }

  def tryPush(dirFile: File, branchName: String): IO[Either[Warnings, Unit]] = {
    val runner = Runner[IO](log).cd(dirFile).stderrToStdout
    (runner ! s"git push origin $branchName")
      .void
      .attempt
      .map(_.leftMap(_ => Warnings.PushError))
  }

  def ifOldest(pullRequest: PullRequestDraft): IO[Either[Warnings, PullRequestDraft]] = for {
    _ <- markReady(pullRequest)
    _ <- IO(log.info(s"Marked $owner/$repoSlug#${pullRequest.number} ready for review"))
  } yield pullRequest.asRight[Warnings]

  def ifNotOldest(
      maybeOldest: Option[PullRequestDraft],
      pullRequest: PullRequestDraft)
      : IO[Either[Warnings, PullRequestDraft]] = {
    val issue = maybeOldest.map(o => s"preceded by #$o").getOrElse("not found")
    for {
      _ <- close(pullRequest, s"${pullRequest.title} ($issue)")
      _ <- IO(log.info(s"Closed $owner/$repoSlug#${pullRequest.number} ($issue)"))
      _ <- deleteBranch(pullRequest)
      _ <- IO(log.info(s"Removed branch ${pullRequest.base.map(_.ref)} from $owner/$repoSlug"))
    } yield Warnings.NotOldest(maybeOldest, pullRequest).asLeft[PullRequestDraft]
  }

  // TODO: Update pull request description?
  def createOrUpdatePullRequest(
    branchName: String,
    changes: List[String],
    changeLabel: ChangeLabel,
    maybePullRequest: Option[PullRequestDraft])
  : IO[Either[Warnings, PullRequestDraft]] = {
    for {
      pullRequest <- (maybePullRequest fold {
        draftPullRequest(authorRepository, branchName, changes.mkString("\n"))
          .flatTap(pullRequest => IO(log.info(s"Opened $owner/$repoSlug#${pullRequest.number}")))
      })(IO.pure)
      labels <- getLabels(pullRequest.number)
      prChangeLabels = labels.flatMap(label => ChangeLabel(label.name)).toSet
      highestChange = (prChangeLabels + changeLabel).max
      lowerChanges = (prChangeLabels - highestChange).toList
      _ <- if (prChangeLabels.contains(highestChange)) IO.unit else assignLabel(highestChange.label, pullRequest)
      _ <- lowerChanges.traverse(change => removeLabel(pullRequest, change.label))
      oldestPullRequest <- getOldestAutoBumpPullRequest
      res <- if (oldestPullRequest.exists(_.number == pullRequest.number)) ifOldest(pullRequest)
      else ifNotOldest(oldestPullRequest, pullRequest)
    } yield res
  }

  def createPullRequest(): IO[Boolean] = {
    val app = for {
      (dir, branchName, maybePullRequest, changes, label) <- EitherT(tryUpdateDependencies)
      _ <- EitherT(tryCommit(dir))
      _ <- EitherT(tryPush(dir, branchName))
      pullRequest <- EitherT(createOrUpdatePullRequest(branchName, changes, label, maybePullRequest))
    } yield pullRequest

    app.leftSemiflatMap(_.warn(log)).value.map(_.isRight)
  }
}
