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

package precog.algebras

import github4s.GithubResponses.GHResponse
import github4s.domain.{NewPullRequest, PRFilter, Pagination}
import precog.domain._

trait PullRequests[F[_]] extends github4s.algebras.PullRequests[F] {
  /** Create pull request as draft. */
  def draftPullRequest(owner: String,
                       repo: String,
                       newPullRequest: NewPullRequest,
                       head: String,
                       base: String,
                       maintainerCanModify: Option[Boolean] = Some(true),
                       headers: Map[String, String] = Map.empty): F[GHResponse[PullRequestDraft]]

  /** List both draft and non-draft pull requests, but return their draft flag. */
  def listDraftPullRequests(owner: String,
                            repo: String,
                            filters: List[PRFilter] = Nil,
                            pagination: Option[Pagination] = None,
                            headers: Map[String, String] = Map()): F[GHResponse[List[PullRequestDraft]]]

  def updatePullRequest(owner: String,
                        repo: String,
                        number: Int,
                        fields: PullRequestUpdate,
                        headers: Map[String, String] = Map()): F[GHResponse[PullRequestDraft]]

  /**
   * Mark pull request as ready to review, removing its "draft" status.
   * @return True if successful.
   */
  def markReadyForReview(owner: String, repo: String, id: String, headers: Map[String, String] = Map()): F[GHResponse[Boolean]]
}
