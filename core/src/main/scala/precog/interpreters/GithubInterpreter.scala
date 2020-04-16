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

import java.util.concurrent.TimeUnit.SECONDS

import cats.effect.ConcurrentEffect
import github4s.algebras.{GitData => _, PullRequests => _, _}
import github4s.http.HttpClient
import github4s.interpreters.{GitDataInterpreter => _, PullRequestsInterpreter => _, _}
import precog.algebras._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

class GithubInterpreter[F[_]: ConcurrentEffect](
                                                 accessToken: Option[String],
                                                 timeout: Option[Duration])(
                                                 implicit ec: ExecutionContext) extends Github[F] {

  def defaultTimeout: FiniteDuration = Duration(1L, SECONDS)

  implicit val client: HttpClient[F] = new HttpClient[F](timeout.getOrElse(defaultTimeout))
  implicit val at: Option[String] = accessToken

  override val pullRequests: PullRequests[F]      = new PullRequestsInterpreter[F](
    new github4s.interpreters.PullRequestsInterpreter[F],
    client,
    accessToken)
  override val users: Users[F]                    = new UsersInterpreter[F]
  override val repos: Repositories[F]             = new RepositoriesInterpreter[F]
  override val auth: Auth[F]                      = new AuthInterpreter[F]
  override val gists: Gists[F]                    = new GistsInterpreter[F]
  override val issues: Issues[F]                  = new IssuesInterpreter[F]
  override val activities: Activities[F]          = new ActivitiesInterpreter[F]
  override val gitData: GitData[F]                = new GitDataInterpreter[F](
    new github4s.interpreters.GitDataInterpreter[F],
    client,
    accessToken)
  override val organizations: Organizations[F]    = new OrganizationsInterpreter[F]
  override val teams: Teams[F]                    = new TeamsInterpreter[F]
}

object GithubInterpreter {

  def apply[F[_]: ConcurrentEffect](
      accessToken: Option[String] = None,
      timeout: Option[Duration] = None)(
      implicit ec: ExecutionContext)
      : GithubInterpreter[F] =
    new GithubInterpreter[F](accessToken, timeout)

}
