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

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import cats.{Monad, Order}
import fs2.{Chunk, Stream}
import github4s.{GHError, GHResponse}
import github4s.algebras.Issues
import github4s.domain._
import precog.algebras._
import precog.domain._
import sbt.util.Logger

import scala.collection.immutable.Seq
import scala.util.Try
import scala.util.matching.Regex

object AutoBump {

  sealed trait Warnings extends Product with Serializable {
    def warn[F[_]: Sync](log: Logger): F[Unit]
  }

  object Warnings {

    case object NoLabel extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("Change label not found!")
        log.warn("Either the repository is already up-to-date, or it needs a custom trickleUpdateDependencies")
      }
    }

    case object UpdateError extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("was unable to run `sbt update` following the trickle application")
        log.warn("this may mean that the some artifacts are not yet propagated; skipping")
      }
    }

    case object NoChangesError extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("git-commit exited with error")
        log.warn("this usually means the target repository was *already* at the latest version but hasn't published yet")
        log.warn("you should check for a stuck trickle PR on that repository")
      }
    }

    case object PushError extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("git-push exited with error")
        log.warn("this usually means some other repository updated the pull request before this one")
      }
    }

    final case class NotOldest(maybeOldest: Option[PullRequestDraft], draft: PullRequestDraft) extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        maybeOldest match {
          case Some(oldest) =>
            log.warn(f"pull request ${draft.number}%d is newer than existing pull request ${oldest.number}%d")
            log.warn("this usually means two or more repositories finished build at the same time,")
            log.warn("and some other repository beat this one to pull request creation.")
          case None         =>
            log.warn(f"pull request ${draft.number}%d was not found")
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
    def unpack: F[Either[GHError, A]] = response.map(_.result)
  }

  val AutoBumpLabel = ":robot:"
  val PullRequestFilters: List[PRFilter] = List(PRFilterOpen, PRFilterSortCreated, PRFilterOrderAsc, PRFilterBase("master"))
  val LinkRelation: Regex = """<(.*?)>; rel="(\w+)"""".r
  val PerPage = 100

  def autoBumpCommitTitle(author: String): String = f"Applied dependency updates by $author%s"

  def autoPage[F[_]: Sync, T](
      first: Pagination)(
      call: Pagination => F[GHResponse[List[T]]])
      : Stream[F, T] = {
    val chunker: Option[Pagination] => F[Option[(Chunk[T], Option[Pagination])]] = {
      case Some(pagination) =>
        call(pagination)
          .map(res => res.result.map(Chunk.seq(_) -> nextPage(getRelations(res.headers))))
          .rethrow
          .map(Option(_))
      case None             =>
        Sync[F].pure(None)
    }
    Stream.unfoldChunkEval(Option(first))(chunker)
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
  def extractLabel(lines: List[String]): Either[Warnings, ChangeLabel] = {
    lines collectFirst {
      case ChangeLabel(label) => label
    } match {
      case Some(label) => label.asRight
      case None        => Warnings.NoLabel.asLeft
    }
  }


  /** Extract updated versions from trickleUpdateDependencies log */
  def extractChanges(lines: List[String]): List[String] = {
    lines.filter(_ contains "Updated ") map { line =>
      line.substring(line.indexOf("Updated "))
        .replaceFirst("(breaking|feature|revision)", "**$1**")
        .replaceFirst("->", "→")
        .replaceAll("""(\d+\.\d+\.\d+(?:-[a-f0-9]+)?)""", "`$1`")
    }
  }

  def getBranch[F[_]: Sync](pullRequest: Option[PullRequestDraft]): F[(String, String)] = Sync[F].delay {
    pullRequest
      .map("" -> _.head.get.ref)
      .getOrElse("-b" -> f"trickle/version-bump-${System.currentTimeMillis()}%d")
  }

  def isAutoBump(pullRequest: PullRequestDraft, labels: List[Label]): Boolean = {
    pullRequest.head.exists(_.ref.startsWith("trickle/")) && labels.exists(_.name == AutoBumpLabel)
  }

  /** Use SBT environment variable, but, if relative path, check for existence or fallback */
  def getSbt[F[_]: Sync](dir: File): F[String] = Sync[F].delay {
    val fallback = "sbt"
    sys.env.get("SBT") match {
      case Some(path) if new File(path).isAbsolute => path
      case Some(path) if path.contains('/')        =>
        val file = new File(dir, path)
        if (file.exists() && file.canExecute()) path
        else fallback
      case Some(path)                              => path
      case None                                    => fallback
    }
  }
}

class AutoBump(
    authorRepository: String,
    owner: String,
    repoSlug: String,
    cloningURL: String,
    log: Logger) {
  import AutoBump._

  // TODO: check whether PR is mergeable?
  /**
   * Finds primary open autobump pull request, if one exists.
   *
   * @return Oldest autobump pull request, by order of creation
   */
  def getOldestAutoBumpPullRequest[F[_]: Sync: DraftPullRequests: Issues]: F[Option[PullRequestDraft]] = {
    getPullRequests
      .evalFilter(pullRequest => getLabels(pullRequest.number).map(labels => isAutoBump(pullRequest, labels)))
      .head
      .compile
      .toList
      .map(_.headOption)
  }

  def getPullRequests[F[_]: Sync: DraftPullRequests]: Stream[F, PullRequestDraft] = {
    autoPage(Pagination(1, PerPage)) { pagination =>
      DraftPullRequests[F].listPullRequests(owner, repoSlug, PullRequestFilters, Some(pagination))
    }
  }

  // FIXME: current github4s api does not support Some(pagination)
  def getLabels[F[_]: Sync: Issues](pr: Int): F[List[Label]] = {
    Issues[F].listLabels(owner, repoSlug, pr).map(_.result).rethrow
  }

  def draftPullRequest[F[_]: Sync: DraftPullRequests: Issues](
      authorRepository: String,
      branchName: String,
      changes: String)
      : F[PullRequestDraft] = {
    for {
      pr <- DraftPullRequests[F]
        .draftPullRequest(
          owner,
          repoSlug,
          NewPullRequestData(
            autoBumpCommitTitle(authorRepository),
            f"This PR brought to you by sbt-trickle via **$authorRepository%s**. Have a nice day!\n\n## Changes\n\n$changes%s"),
          branchName,
          "master")
        .map(_.result)
        .rethrow
      _ <- assignLabel(AutoBumpLabel, pr)
    } yield pr
  }

  def assignLabel[F[_]: Sync: Issues](label: String, pullRequest: PullRequestDraft): F[Unit] = {
    Issues[F]
      .addLabels(owner, repoSlug, pullRequest.number, List(label))
      .map(_.result)
      .rethrow
      .void
  }

  def markReady[F[_]: Sync: DraftPullRequests](pullRequest: PullRequestDraft): F[Unit] = {
    DraftPullRequests[F]
      .markReadyForReview(owner, repoSlug, pullRequest.node_id)
      .map(_.result)
      .rethrow
      .ensure(new RuntimeException(f"Failed to mark pull request ${pullRequest.number}%d ready for review"))(x => x)
      .void
  }

  def close[F[_]: DraftPullRequests](pullRequest: PullRequestDraft, title: String): F[GHResponse[PullRequestDraft]] = {
    val requestUpdate = PullRequestUpdate(title = Some(title), state = Some("closed"))
    DraftPullRequests[F].updatePullRequest(owner, repoSlug, pullRequest.number, requestUpdate)
  }

  def deleteBranch[F[_]: Sync: References](pullRequest: PullRequestDraft): F[Unit] = {
    (pullRequest.head map { base =>
      val branch = f"refs/heads/${base.ref}%s"
      References[F].deleteReference(owner, repoSlug, branch).map(_.result).rethrow
    }).sequence.void
  }

  def removeLabel[F[_]: Sync: Issues](pullRequest: PullRequestDraft, label: String): F[List[Label]] = {
    Issues[F].removeLabel(owner, repoSlug, pullRequest.number, label).map(_.result).rethrow
  }

  def tryUpdateDependencies[F[_]: Sync: DraftPullRequests: Issues: Runner]
      : F[Either[Warnings, (File, String, Option[PullRequestDraft], List[String], ChangeLabel)]] = {
    for {
      path <- Sync[F].delay(Files.createTempDirectory("sbt-precog"))
      dir = path.toFile
      _ <- Runner[F].stderrToStdout !
        f"git clone --depth 1 --no-single-branch $cloningURL%s ${dir.getPath}%s"
      oldestPullRequest <- getOldestAutoBumpPullRequest
      (flag, branchName) <- getBranch(oldestPullRequest)
      runner = Runner[F].cd(dir)
      _ <- runner.stderrToStdout ! f"git checkout $flag%s $branchName%s"
      sbt <- getSbt(dir)
      lines <- runner ! f"$sbt%s trickleUpdateDependencies"
      changes = extractChanges(lines)
      maybeLabel = extractLabel(lines)
    } yield maybeLabel.map(label => (dir, branchName, oldestPullRequest, changes, label))
  }

  def verifyUpdateDependencies[F[_]: Sync: Runner](dir: File): F[Either[Warnings, Unit]] = {
    val runner = Runner[F].cd(dir)
    for {
      sbt <- getSbt(dir)
      _ <- runner ! f"$sbt%s trickleIsUpToDate"
      updateResult <- (runner.stderrToStdout ! f"$sbt%s update").attempt
    } yield updateResult.leftMap(_ => Warnings.UpdateError).void
  }

  // TODO: add changes to commit message?
  def tryCommit[F[_]: Sync: Runner](dir: File): F[Either[Warnings, Unit]] = {
    val runner = Runner[F]
      .cd(dir)
      .stderrToStdout
      .withEnv(
        "GIT_AUTHOR_NAME" -> f"Precog Bot ($authorRepository%s)",
        "GIT_AUTHOR_EMAIL" -> "bot@precog.com",
        "GIT_COMMITTER_NAME" -> f"Precog Bot ($authorRepository%s)",
        "GIT_COMMITTER_EMAIL" -> "bot@precog.com")
    for {
      _ <- runner ! "git add ."
      result <- (runner ! Seq("git", "commit", "-m", autoBumpCommitTitle(authorRepository))).void.attempt
    } yield result.leftMap(_ => Warnings.NoChangesError)
  }

  def tryPush[F[_]: Sync: Runner](dir: File, branchName: String): F[Either[Warnings, Unit]] = {
    val runner = Runner[F].cd(dir).stderrToStdout
    (runner ! f"git push origin $branchName%s")
      .void
      .attempt
      .map(_.leftMap(_ => Warnings.PushError))
  }

  def ifOldest[F[_]: Sync: DraftPullRequests](pullRequest: PullRequestDraft): F[Either[Warnings, PullRequestDraft]] = for {
    _ <- markReady(pullRequest)
    _ <- Sync[F].delay(log.info(f"Marked $owner%s/$repoSlug%s#${pullRequest.number}%d ready for review"))
  } yield pullRequest.asRight[Warnings]

  def ifNotOldest[F[_]: Sync: DraftPullRequests: References](
      maybeOldest: Option[PullRequestDraft],
      pullRequest: PullRequestDraft)
      : F[Either[Warnings, PullRequestDraft]] = {
    val issue = maybeOldest.map(o => f"preceded by #${o.number}%d").getOrElse("not found")
    for {
      _ <- close(pullRequest, f"${pullRequest.title}%s ($issue%s)")
      _ <- Sync[F].delay(log.info(f"Closed $owner%s/$repoSlug%s#${pullRequest.number}%d ($issue%s)"))
      _ <- deleteBranch(pullRequest)
      _ <- Sync[F].delay(log.info(f"Removed branch ${pullRequest.base.map(_.ref)}%s from $owner%s/$repoSlug%s"))
    } yield Warnings.NotOldest(maybeOldest, pullRequest).asLeft[PullRequestDraft]
  }

  // TODO: Update pull request description?
  def createOrUpdatePullRequest[F[_]: Sync: DraftPullRequests: Issues: References](
    branchName: String,
    changes: List[String],
    changeLabel: ChangeLabel,
    maybePullRequest: Option[PullRequestDraft])
  : F[Either[Warnings, PullRequestDraft]] = {
    for {
      pullRequest <- (maybePullRequest fold {
        draftPullRequest(authorRepository, branchName, changes.map("- " + _).mkString("\n"))
          .flatTap(pullRequest => Sync[F].delay(log.info(f"Opened ${pullRequest.html_url}%s")))
      })(Sync[F].pure)
      labels <- getLabels(pullRequest.number)
      prChangeLabels = labels.flatMap(label => ChangeLabel(label.name)).toSet
      highestChange = (prChangeLabels + changeLabel).max
      lowerChanges = (prChangeLabels - highestChange).toList
      _ <- if (prChangeLabels.contains(highestChange)) Sync[F].unit else assignLabel(highestChange.label, pullRequest)
      _ <- lowerChanges.traverse(change => removeLabel(pullRequest, change.label))
      oldestPullRequest <- getOldestAutoBumpPullRequest
      res <- if (oldestPullRequest.exists(_.number == pullRequest.number)) ifOldest(pullRequest)
      else ifNotOldest(oldestPullRequest, pullRequest)
    } yield res
  }

  def createPullRequest[F[_]: Sync: DraftPullRequests: Issues: References: Runner](): F[Boolean] = {
    val app = for {
      (dir, branchName, maybePullRequest, changes, label) <- EitherT(tryUpdateDependencies)
      _ <- EitherT(verifyUpdateDependencies(dir))
      _ <- EitherT(tryCommit(dir))
      _ <- EitherT(tryPush(dir, branchName))
      pullRequest <- EitherT(createOrUpdatePullRequest(branchName, changes, label, maybePullRequest))
    } yield pullRequest

    app.leftSemiflatMap(_.warn(log)).value.map(_.isRight)
  }
}
