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
import github4s.GHResponse
import github4s.http.HttpClient
import precog.algebras.References

class ReferencesInterpreter [F[_] : Sync](
    client: HttpClient[F],
    accessToken: Option[String])
    extends References[F] {
  def deleteReference(owner: String, repo: String, ref: String, headers: Map[String, String]): F[GHResponse[Unit]] = {
    for {
      _ <- Sync[F].delay(assert(ref.matches("refs/(heads|tags)/.+"), f"Invalid reference $ref%s"))
      res <- client.delete(accessToken, f"repos/$owner%s/$repo%s/git/$ref%s")
    } yield res
  }
}
