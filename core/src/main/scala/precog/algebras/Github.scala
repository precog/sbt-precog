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

import github4s.algebras._

trait Github[F[_]] {

  def draftPullRequests: DraftPullRequests[F]

  def labels: Labels[F]

  def references: References[F]

  def pullRequests: PullRequests[F]

  def users: Users[F]

  def repos: Repositories[F]

  def auth: Auth[F]

  def gists: Gists[F]

  def issues: github4s.algebras.Issues[F]

  def activities: Activities[F]

  def gitData: GitData[F]

  def organizations: Organizations[F]

  def teams: Teams[F]
}

object Github {
  def apply[F[_]](implicit github: Github[F]): Github[F] = github
}
