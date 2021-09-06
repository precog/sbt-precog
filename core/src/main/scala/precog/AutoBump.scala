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
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.collection.immutable.Seq
import scala.sys.process.ProcessLogger
import scala.util.Try
import scala.util.matching.Regex

import cats.Monad
import cats.Order
import cats.data.EitherT
import cats.effect.Clock
import cats.effect.Sync
import cats.implicits._
import fs2.Chunk
import fs2.Stream
import github4s.GHError
import github4s.GHResponse
import github4s.domain._
import org.http4s.Uri
import precog.algebras.Runner.RunnerConfig
import precog.algebras._
import precog.domain._
import sbt.util.Logger

object AutoBump {

  final case class UpdateBranch(
      runnerConf: RunnerConfig,
      name: String,
      pullRequest: Option[PullRequestDraft],
      changes: List[String],
      label: Option[ChangeLabel])

  final case class MarkReadyFailedException(pr: Int)
      extends RuntimeException(f"Failed to mark pull request $pr%d ready for review")

  final case class GHException(ghError: GHError, statusCode: Int, location: String)
      extends RuntimeException(s"[$location:$statusCode] ${ghError.getMessage()}", ghError)

  implicit class RethrowGHErrorSyntax[F[_]: Sync, A](command: F[GHResponse[A]]) {
    def rethrowGHError(location: String): F[A] = {
      command.map(res => res.result.leftMap(GHException(_, res.statusCode, location))).rethrow
    }
  }

  sealed trait Warnings extends Product with Serializable {
    def warn[F[_]: Sync](log: Logger): F[Unit]
  }

  object Warnings {

    case object NoLabel extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("Change label not found!")
        log.warn(
          "Either the repository is already up-to-date, or it needs a custom trickleUpdateDependencies")
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
        log.warn(
          "this usually means the target repository was *already* at the latest version but hasn't published yet")
        log.warn("you should check for a stuck trickle PR on that repository")
      }
    }

    case object PushError extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        log.warn("git-push exited with error")
        log.warn(
          "this usually means some other repository updated the pull request before this one")
      }
    }

    final case class NotOldest(maybeOldest: Option[PullRequestDraft], draft: PullRequestDraft)
        extends Warnings {
      def warn[F[_]: Sync](log: Logger): F[Unit] = Sync[F].delay {
        maybeOldest match {
          case Some(oldest) =>
            log.warn(
              f"pull request ${draft.number}%d is newer than existing pull request ${oldest.number}%d")
            log.warn(
              "this usually means two or more repositories finished build at the same time,")
            log.warn("and some other repository beat this one to pull request creation.")
          case None =>
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
    val fromString: Map[String, ChangeLabel] =
      values.map(change => change.label -> change).toMap
    val labelPattern: Regex = values.map(_.label).mkString("|").r

    def apply(label: String): Option[ChangeLabel] = {
      fromString.get(label)
    }

    def unapply(arg: String): Option[ChangeLabel] =
      labelPattern.findFirstIn(arg).flatMap(apply)
  }

  val AutoBumpLabel = ":robot:"
  val PullRequestFilters: List[PRFilter] =
    List(PRFilterOpen, PRFilterSortCreated, PRFilterOrderAsc, PRFilterBase("master"))
  val LinkRelation: Regex = """<(.*?)>; rel="(\w+)"""".r
  val PerPage = 100

  def autoBumpCommitTitle(author: String): String = f"Applied dependency updates by $author%s"

  def autoPage[F[_]: Sync, T](first: Pagination, location: String)(
      call: Pagination => F[GHResponse[List[T]]]): Stream[F, T] = {
    val chunker: Option[Pagination] => F[Option[(Chunk[T], Option[Pagination])]] = {
      case Some(pagination) =>
        call(pagination)
          .map(res =>
            res
              .result
              .bimap(
                GHException(_, res.statusCode, location),
                Chunk.seq(_) -> nextPage(getRelations(res.headers))))
          .rethrow
          .map(Option(_))
      case None =>
        Sync[F].pure(None)
    }
    Stream.unfoldChunkEval(Option(first))(chunker)
  }

  def nextPage(relations: Map[String, (Int, Int)]): Option[Pagination] = {
    relations.get("next").map((Pagination.apply _).tupled)
  }

  /**
   * Decodes github's "Link" header into a map
   */
  def getRelations(headers: Map[String, String]): Map[String, (Int, Int)] = {
    val relations = for {
      linkValue <- headers collect {
        case (header, value) if header.toLowerCase == "link" => value
      }
      LinkRelation(url, relation) <- LinkRelation.findAllMatchIn(linkValue)
      uri <- Uri.fromString(url).toSeq
      page <- uri.params.get("page")
      pageNum <- Try(page.toInt).toOption
      perPage <- uri
        .params
        .get("per_page")
        .orElse(Some(PerPage.toString)) // Add a default, just in case
      perPageNum <- Try(perPage.toInt).toOption
    } yield (relation, (pageNum, perPageNum))
    relations.toMap
  }

  /**
   * Extract change label from trickleUpdateDependencies log
   */
  def extractLabel(lines: List[String]): Option[ChangeLabel] =
    lines collectFirst { case ChangeLabel(label) => label }

  /**
   * Extract updated versions from trickleUpdateDependencies log
   */
  def extractChanges(lines: List[String]): List[String] = {
    lines.filter(_ contains "Updated ") map { line =>
      line
        .substring(line.indexOf("Updated "))
        .replaceFirst("(breaking|feature|revision)", "**$1**")
        .replaceFirst("->", "→")
        .replaceAll("""(\d+\.\d+\.\d+(?:-[a-f0-9]+)?)""", "`$1`")
    }
  }

  def getBranch[F[_]: Sync: Clock](
      pullRequest: Option[PullRequestDraft]): F[(String, String)] = {
    Clock[F].realTime(MILLISECONDS) map { time =>
      pullRequest.map("" -> _.head.get.ref).getOrElse("-b" -> f"trickle/version-bump-$time%d")
    }
  }

  def isAutoBump(pullRequest: PullRequestDraft, labels: List[Label]): Boolean = {
    pullRequest.head.exists(_.ref.startsWith("trickle/")) && labels.exists(
      _.name == AutoBumpLabel)
  }

  /**
   * Use SBT environment variable, but, if relative path, check for existence or fallback
   */
  def getSbt[F[_]: Monad: Runner](runnerConf: RunnerConfig): F[String] = {
    val fallback = "sbt"
    runnerConf.env.get("SBT") match {
      case Some(path) if new File(path).isAbsolute => Monad[F].point(path)
      case Some(path) if path.contains('/') =>
        for {
          res <- Runner[F](runnerConf) ? (Seq("test", "-x", path), ProcessLogger(_ => ()))
        } yield if (res == 0) path else fallback
      case Some(path) => Monad[F].point(path)
      case None => Monad[F].point(fallback)
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
   * @return
   *   Oldest autobump pull request, by order of creation
   */
  def getOldestAutoBumpPullRequest[F[_]: Sync: DraftPullRequests: Labels]
      : F[Option[PullRequestDraft]] = {
    getPullRequests
      .evalFilter(pullRequest =>
        getLabels(pullRequest.number)
          .compile
          .toList
          .map(labels => isAutoBump(pullRequest, labels)))
      .head
      .compile
      .toList
      .map(_.headOption)
  }

  def getPullRequests[F[_]: Sync: DraftPullRequests]: Stream[F, PullRequestDraft] = {
    autoPage(Pagination(1, PerPage), "getPullRequests") { pagination =>
      DraftPullRequests[F].listPullRequests(
        owner,
        repoSlug,
        PullRequestFilters,
        Some(pagination))
    }
  }

  def getLabels[F[_]: Sync: Labels](pr: Int): Stream[F, Label] = {
    autoPage(Pagination(1, PerPage), "getLabels") { pagination =>
      Labels[F].listLabels(owner, repoSlug, pr, Some(pagination))
    }
  }

  def draftPullRequest[F[_]: Sync: DraftPullRequests: Labels](
      updateBranch: UpdateBranch): F[PullRequestDraft] = {
    val changeLog = updateBranch.changes.map("- " + _).mkString("\n")
    val description =
      f"""This PR brought to you by sbt-trickle via **$authorRepository**. Have a nice day!
         |
         |## Changes
         |
         |$changeLog""".stripMargin
    for {
      pr <- DraftPullRequests[F]
        .draftPullRequest(
          owner,
          repoSlug,
          NewPullRequestData(autoBumpCommitTitle(authorRepository), description),
          updateBranch.name,
          "master")
        .rethrowGHError("draftPullRequest")
      _ <- assignLabel(AutoBumpLabel, pr)
    } yield pr
  }

  def assignLabel[F[_]: Sync: Labels](label: String, pullRequest: PullRequestDraft): F[Unit] = {
    Labels[F]
      .addLabels(owner, repoSlug, pullRequest.number, List(label))
      .rethrowGHError("assignLabel")
      .void
  }

  def markReady[F[_]: Sync: DraftPullRequests](pullRequest: PullRequestDraft): F[Unit] = {
    DraftPullRequests[F]
      .markReadyForReview(owner, repoSlug, pullRequest.node_id)
      .rethrowGHError("markReady")
      .ensure(MarkReadyFailedException(pullRequest.number))(isDraft => isDraft)
      .void
  }

  def close[F[_]: DraftPullRequests](
      pullRequest: PullRequestDraft,
      title: String): F[GHResponse[PullRequestDraft]] = {
    val requestUpdate = PullRequestUpdate(title = Some(title), state = Some("CLOSED"))
    DraftPullRequests[F].updatePullRequest(owner, repoSlug, pullRequest.number, requestUpdate)
  }

  def deleteBranch[F[_]: Sync: References](pullRequest: PullRequestDraft): F[Unit] = {
    (pullRequest.head map { prHead =>
      val branch = f"refs/heads/${prHead.ref}%s"
      References[F].deleteReference(owner, repoSlug, branch).rethrowGHError("deleteBranch")
    }).sequence.void
  }

  def removeLabel[F[_]: Sync: Labels](
      pullRequest: PullRequestDraft,
      label: String): F[List[Label]] = {
    Labels[F]
      .removeLabel(owner, repoSlug, pullRequest.number, label)
      .rethrowGHError("removeLabel")
  }

  def tryUpdateDependencies[F[_]: Sync: Clock: Runner: DraftPullRequests: Labels](
      runnerConf: RunnerConfig): F[UpdateBranch] = {
    for {
      atTemp <- Runner[F](runnerConf).cdTemp("sbt-precog")
      runner = Runner[F](atTemp)
      _ <- runner !! f"git clone --depth 1 --no-single-branch $cloningURL%s ."
      oldestPullRequest <- getOldestAutoBumpPullRequest
      (flag, branchName) <- getBranch(oldestPullRequest)
      _ <- runner !! f"git checkout $flag%s $branchName%s"
      sbt <- getSbt[F](atTemp)
      lines <- runner ! f"$sbt%s trickleUpdateDependencies"
      changes = extractChanges(lines)
    } yield UpdateBranch(atTemp, branchName, oldestPullRequest, changes, extractLabel(lines))
  }

  def verifyUpdateDependencies[F[_]: Sync: Runner](
      updateBranch: UpdateBranch): F[Either[Warnings, Unit]] = {
    val runner = Runner[F](updateBranch.runnerConf)
    for {
      sbt <- getSbt[F](updateBranch.runnerConf)
      _ <- runner ! f"$sbt%s trickleIsUpToDate"
      updateResult <- (runner !! f"$sbt%s update").attempt
    } yield updateResult.leftMap(_ => Warnings.UpdateError).void
  }

  // TODO: add changes to commit message?
  def tryCommit[F[_]: Sync: Runner](updateBranch: UpdateBranch): F[Either[Warnings, Unit]] = {
    val runner = Runner[F](
      updateBranch
        .runnerConf
        .withEnv(
          "GIT_AUTHOR_NAME" -> f"Precog Bot ($authorRepository%s)",
          "GIT_AUTHOR_EMAIL" -> "bot@precog.com",
          "GIT_COMMITTER_NAME" -> f"Precog Bot ($authorRepository%s)",
          "GIT_COMMITTER_EMAIL" -> "bot@precog.com"
        ))
    for {
      _ <- runner !! "git add ."
      result <- (runner !! Seq("git", "commit", "-m", autoBumpCommitTitle(authorRepository)))
        .void
        .attempt
    } yield result.leftMap(_ => Warnings.NoChangesError)
  }

  def tryPush[F[_]: Sync: Runner](updateBranch: UpdateBranch): F[Either[Warnings, Unit]] = {
    (Runner[F](updateBranch.runnerConf) !! f"git push origin ${updateBranch.name}%s")
      .void
      .attempt
      .map(_.leftMap(_ => Warnings.PushError))
  }

  def ifOldest[F[_]: Sync: DraftPullRequests](
      pullRequest: PullRequestDraft): F[Either[Warnings, PullRequestDraft]] = for {
    _ <- markReady(pullRequest)
    _ <- Sync[F].delay(
      log.info(f"Marked $owner%s/$repoSlug%s#${pullRequest.number}%d ready for review"))
  } yield pullRequest.copy(draft = false).asRight[Warnings]

  def ifNotOldest[F[_]: Sync: DraftPullRequests: References](
      maybeOldest: Option[PullRequestDraft],
      pullRequest: PullRequestDraft): F[Either[Warnings, PullRequestDraft]] = {
    val issue = maybeOldest.map(o => f"preceded by #${o.number}%d").getOrElse("not found")
    for {
      _ <- close(pullRequest, f"${pullRequest.title}%s ($issue%s)")
      _ <- Sync[F].delay(
        log.info(f"Closed $owner%s/$repoSlug%s#${pullRequest.number}%d ($issue%s)"))
      _ <- deleteBranch(pullRequest).void recover {
        case e: GHException =>
          if (e.statusCode == 204) ()
          else log.warn(s"Error deleting branch: $e")
      }
      _ <- Sync[F].delay(
        log.info(f"Removed branch ${pullRequest.head.map(_.ref)}%s from $owner%s/$repoSlug%s"))
    } yield Warnings.NotOldest(maybeOldest, pullRequest).asLeft[PullRequestDraft]
  }

  def getOrCreatePullRequest[F[_]: Sync: DraftPullRequests: Labels](
      updateBranch: UpdateBranch): F[PullRequestDraft] = {
    updateBranch
      .pullRequest
      .fold {
        draftPullRequest(updateBranch).flatTap(pullRequest =>
          Sync[F].delay(log.info(f"Opened ${pullRequest.html_url}%s")))
      }(Sync[F].pure)
  }

  // TODO: Update pull request description?
  def createOrUpdatePullRequest[F[_]: Sync: DraftPullRequests: Labels: References](
      updateBranch: UpdateBranch): F[Either[Warnings, PullRequestDraft]] = {
    for {
      pullRequest <- getOrCreatePullRequest(updateBranch)
      labels <- getLabels(pullRequest.number).compile.toList
      prChangeLabels = labels.flatMap(label => ChangeLabel(label.name)).toSet
      maybeHighestChange = updateBranch
        .label
        .map(prChangeLabels + _)
        .getOrElse(prChangeLabels)
        .toList
        .maximumOption
      lowerChanges = maybeHighestChange.map(prChangeLabels - _).getOrElse(Set.empty).toList
      _ <- maybeHighestChange match {
        case Some(highestChange) if !prChangeLabels.contains(highestChange) =>
          assignLabel(highestChange.label, pullRequest)
        case _ =>
          Sync[F].unit
      }
      _ <- lowerChanges.traverse(change => removeLabel(pullRequest, change.label))
      oldestPullRequest <- getOldestAutoBumpPullRequest
      res <-
        if (oldestPullRequest.exists(_.number == pullRequest.number)) ifOldest(pullRequest)
        else ifNotOldest(oldestPullRequest, pullRequest)
    } yield res
  }

  def createPullRequest[F[_]: Sync: Clock: Runner: DraftPullRequests: Labels: References](
      runnerConf: RunnerConfig): F[Boolean] = {
    val app = for {
      updateBranch <- EitherT.right[Warnings](tryUpdateDependencies(runnerConf))
      _ <- EitherT(verifyUpdateDependencies(updateBranch))
      _ <- EitherT(tryCommit(updateBranch))
      _ <- EitherT(tryPush(updateBranch))
      pullRequest <- EitherT(createOrUpdatePullRequest(updateBranch))
    } yield pullRequest

    app.leftSemiflatMap(_.warn(log)).value.map(_.isRight)
  }
}
