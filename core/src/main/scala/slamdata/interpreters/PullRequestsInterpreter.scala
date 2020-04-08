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

package precog.interpreters

import cats.effect.Sync
import cats.implicits._
import github4s.GithubResponses.GHResponse
import github4s.domain._
import github4s.http.HttpClient
import graphql.codegen.GraphQLQuery
import graphql.codegen.markReadyForReview.MarkForReview
import precog.algebras._
import precog.domain._


class PullRequestsInterpreter[F[_] : Sync](impl: github4s.algebras.PullRequests[F])
                                          (implicit client: HttpClient[F], accessToken: Option[String])
    extends PullRequests[F] {
  import PullRequestsInterpreter._

  val draftHeader: (String, String) = "Accept" -> "application/vnd.github.shadow-cat-preview+json"

  def draftPullRequest(owner: String,
                       repo: String,
                       newPullRequest: NewPullRequest,
                       head: String,
                       base: String,
                       maintainerCanModify: Option[Boolean],
                       headers: Map[String, String]): F[GHResponse[PullRequestDraft]] = {
    val draftHeaders: Map[String, String] = headers + draftHeader
    val data: DraftPullRequest = newPullRequest match {
      case NewPullRequestData(title, body) => DraftPullRequestData(title, head, base, body, maintainerCanModify)
      case NewPullRequestIssue(issue)      => DraftPullRequestIssue(issue, head, base, maintainerCanModify)
    }
    client
      .post[DraftPullRequest, PullRequestDraft](accessToken, s"repos/$owner/$repo/pulls", draftHeaders, data)
  }

  def listDraftPullRequests(owner: String,
                            repo: String,
                            filters: List[PRFilter],
                            pagination: Option[Pagination],
                            headers: Map[String, String]): F[GHResponse[List[PullRequestDraft]]] = {
    val draftHeaders: Map[String, String] = headers + draftHeader
    client.get[List[PullRequestDraft]](
      accessToken, s"repos/$owner/$repo/pulls", draftHeaders, filters.map(_.tupled).toMap, pagination)
  }

  def updatePullRequest(owner: String,
                        repo: String,
                        number: Int,
                        fields: PullRequestUpdate,
                        headers: Map[String, String]): F[GHResponse[PullRequestDraft]] = {
    val draftHeaders: Map[String, String] = headers + draftHeader
    client.patch[PullRequestUpdate, PullRequestDraft](accessToken, s"repos/$owner/$repo/pulls/$number", draftHeaders, fields)
  }

  def markReadyForReview(owner: String,
                         repo: String,
                         id: String,
                         headers: Map[String, String]): F[GHResponse[Boolean]] = {
    val draftHeaders: Map[String, String] = headers + draftHeader
    val data = new GithubQuery(MarkForReview, MarkForReview.Variables(id))
    client
      .post[GithubQuery[MarkForReview.type], GithubResponse[MarkForReview.type]](accessToken, "graphql", draftHeaders, data)
      .map(_.map(r => r.copy(result = r.result.data.markPullRequestReadyForReview.exists(_.pullRequest.exists(!_.isDraft)))))
  }

  def getPullRequest(owner: String, repo: String, number: Int, headers: Map[String, String]): F[GHResponse[PullRequest]] =
    impl.getPullRequest(owner, repo, number, headers)

  def listPullRequests(owner: String,
                       repo: String,
                       filters: List[PRFilter],
                       pagination: Option[Pagination],
                       headers: Map[String, String]): F[GHResponse[List[PullRequest]]] =
    impl.listPullRequests(owner, repo, filters, pagination, headers)

  def listFiles(owner: String,
                repo: String,
                number: Int,
                pagination: Option[Pagination],
                headers: Map[String, String]): F[GHResponse[List[PullRequestFile]]] =
    impl.listFiles(owner, repo, number, pagination, headers)

  def createPullRequest(owner: String,
                        repo: String,
                        newPullRequest: NewPullRequest,
                        head: String,
                        base: String,
                        maintainerCanModify: Option[Boolean],
                        headers: Map[String, String]): F[GHResponse[PullRequest]] =
    impl.createPullRequest(owner, repo, newPullRequest, head, base, maintainerCanModify, headers)

  def listReviews(owner: String,
                  repo: String,
                  pullRequest: Int,
                  pagination: Option[Pagination],
                  headers: Map[String, String]): F[GHResponse[List[PullRequestReview]]] =
    impl.listReviews(owner, repo, pullRequest, pagination, headers)

  def getReview(owner: String,
                repo: String,
                pullRequest: Int,
                review: Int,
                headers: Map[String, String]): F[GHResponse[PullRequestReview]] =
    impl.getReview(owner, repo, pullRequest, review, headers)

}

object PullRequestsInterpreter {
  import io.circe._
  import io.circe.generic.auto._
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import io.circe.syntax._
  import github4s.Decoders._

  /** Representation of the json data github graphql queries expect. */
  case class GithubQuery[A <: GraphQLQuery](query: A, variables: A#Variables)
  object GithubQuery {
    implicit def encodeGraphQLQuery[A <: GraphQLQuery]: Encoder[A] = Encoder.encodeString.contramap[A](_.document.renderCompact)
    implicit def encodeGithubQuery[A <: GraphQLQuery](implicit varsEnc: Encoder[A#Variables]): Encoder[GithubQuery[A]] =
      deriveEncoder[GithubQuery[A]]
  }

  /** Representation of the json data returned by github graphql queries. */
  case class GithubResponse[A <: GraphQLQuery](data: A#Data)
  object GithubResponse {
    implicit def decodeGithubResponse[A <: GraphQLQuery](implicit dataDec: Decoder[A#Data]): Decoder[GithubResponse[A]] =
      deriveDecoder[GithubResponse[A]]
  }

  implicit val encodeDraftPullRequest: Encoder[DraftPullRequest] = Encoder.instance {
    case d: DraftPullRequestData  => d.asJson
    case d: DraftPullRequestIssue => d.asJson
  }

  implicit val decoderPullRequest: Decoder[PullRequestDraft] = deriveDecoder[PullRequestDraft]

  implicit val encodePullRequestUpdate: Encoder[PullRequestUpdate] = deriveEncoder[PullRequestUpdate]
}
