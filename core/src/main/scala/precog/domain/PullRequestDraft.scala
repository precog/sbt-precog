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

package precog.domain

import github4s.domain.PullRequest
import github4s.domain.PullRequestBase
import github4s.domain.User

final case class PullRequestDraft(
    id: Int,
    number: Int,
    node_id: String,
    state: String,
    title: String,
    body: Option[String],
    locked: Boolean,
    draft: Boolean,
    html_url: String,
    created_at: String,
    updated_at: Option[String],
    closed_at: Option[String],
    merged_at: Option[String],
    merge_commit_sha: Option[String],
    base: Option[PullRequestBase],
    head: Option[PullRequestBase],
    user: Option[User],
    assignee: Option[User])

object PullRequestDraft {
  implicit class ToPullRequestSyntax(pr: PullRequestDraft) {
    def toPullRequest: PullRequest = PullRequest(
      id = pr.id,
      number = pr.number,
      state = pr.state,
      title = pr.title,
      body = pr.body,
      locked = pr.locked,
      html_url = pr.html_url,
      created_at = pr.created_at,
      updated_at = pr.updated_at,
      closed_at = pr.closed_at,
      merged_at = pr.merged_at,
      merge_commit_sha = pr.merge_commit_sha,
      base = pr.base,
      head = pr.head,
      user = pr.user,
      assignee = pr.assignee
    )
  }
}

sealed trait DraftPullRequest extends Product with Serializable {
  def head: String
  def base: String
  def maintainer_can_modify: Option[Boolean]
  def draft: Boolean
}

object DraftPullRequest {

  final case class DraftPullRequestData(
      title: String,
      head: String,
      base: String,
      body: String,
      maintainer_can_modify: Option[Boolean] = Some(true),
      draft: Boolean = true)
      extends DraftPullRequest

  final case class DraftPullRequestIssue(
      issue: Int,
      head: String,
      base: String,
      maintainer_can_modify: Option[Boolean] = Some(true),
      draft: Boolean = true)
      extends DraftPullRequest

}

final case class PullRequestUpdate(
    title: Option[String] = None,
    body: Option[String] = None,
    state: Option[String] = None,
    base: Option[String] = None,
    maintainer_can_modify: Option[Boolean] = None)
