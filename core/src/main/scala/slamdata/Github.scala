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

import java.util.concurrent.TimeUnit.MILLISECONDS

import cats.effect.ConcurrentEffect
import github4s.algebras.{PullRequests => _, GitData => _, _}
import github4s.interpreters.{PullRequestsInterpreter => _, GitDataInterpreter => _, _}
import github4s.http.HttpClient
import precog.algebras._
import precog.interpreters._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class Github[F[_]: ConcurrentEffect](accessToken: Option[String], timeout: Option[Duration])(implicit ec: ExecutionContext) {

  implicit val client: HttpClient[F] = new HttpClient[F](timeout.getOrElse(Duration(1000L, MILLISECONDS)))
  implicit val at: Option[String] = accessToken

  val pullRequests: PullRequests[F] = new PullRequestsInterpreter[F](new github4s.interpreters.PullRequestsInterpreter[F])
  val users: Users[F]                    = new UsersInterpreter[F]
  val repos: Repositories[F]             = new RepositoriesInterpreter[F]
  val auth: Auth[F]                      = new AuthInterpreter[F]
  val gists: Gists[F]                    = new GistsInterpreter[F]
  val issues: Issues[F]                  = new IssuesInterpreter[F]
  val activities: Activities[F]          = new ActivitiesInterpreter[F]
  val gitData: GitData[F]                = new GitDataInterpreter[F](new github4s.interpreters.GitDataInterpreter[F])
  val organizations: Organizations[F]    = new OrganizationsInterpreter[F]
  val teams: Teams[F]                    = new TeamsInterpreter[F]
}

object Github {

  def apply[F[_]: ConcurrentEffect](accessToken: Option[String] = None, timeout: Option[Duration] = None)
                                   (implicit ec: ExecutionContext): Github[F] =
    new Github[F](accessToken, timeout)

}
