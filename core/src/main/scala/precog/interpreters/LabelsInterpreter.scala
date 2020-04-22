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
import github4s.GHResponse
import github4s.algebras.Issues
import github4s.domain.{Label, Pagination}
import precog.algebras.Labels

class LabelsInterpreter[F[_] : Sync](issues: Issues[F]) extends Labels[F] {
  def listLabels(
      owner: String,
      repo: String,
      number: Int,
      pagination: Option[Pagination],
      headers: Map[String, String])
      : F[GHResponse[List[Label]]] = issues.listLabels(owner, repo, number, pagination, headers)

  def addLabels(
      owner: String,
      repo: String,
      number: Int,
      labels: List[String],
      headers: Map[String, String])
      : F[GHResponse[List[Label]]] = issues.addLabels(owner, repo, number, labels, headers)

  def removeLabel(
      owner: String,
      repo: String,
      number: Int,
      label: String,
      headers: Map[String, String])
      : F[GHResponse[List[Label]]] = issues.removeLabel(owner, repo, number, label, headers)
}
