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

package precog.algebras

import github4s.GHResponse
import github4s.domain.Label
import github4s.domain.Pagination

trait Labels[F[_]] {

  /**
   * List the labels assigned to an Issue
   *
   * @param owner
   *   of the repo
   * @param repo
   *   name of the repo
   * @param number
   *   Issue number
   * @param headers
   *   optional user headers to include in the request
   * @return
   *   a GHResponse with the list of labels for the Issue.
   */
  def listLabels(
      owner: String,
      repo: String,
      number: Int,
      pagination: Option[Pagination] = None,
      headers: Map[String, String] = Map()
  ): F[GHResponse[List[Label]]]

  /**
   * Add the specified labels to an Issue
   *
   * @param owner
   *   of the repo
   * @param repo
   *   name of the repo
   * @param number
   *   Issue number
   * @param labels
   *   the list of labels to add to the issue
   * @param headers
   *   optional user headers to include in the request
   * @return
   *   a GHResponse with the list of labels added to the Issue.
   */
  def addLabels(
      owner: String,
      repo: String,
      number: Int,
      labels: List[String],
      headers: Map[String, String] = Map()
  ): F[GHResponse[List[Label]]]

  /**
   * Remove the specified label from an Issue
   *
   * @param owner
   *   of the repo
   * @param repo
   *   name of the repo
   * @param number
   *   Issue number
   * @param label
   *   the name of the label to remove from the issue
   * @param headers
   *   optional user headers to include in the request
   * @return
   *   a GHResponse with the list of labels removed from the Issue.
   */
  def removeLabel(
      owner: String,
      repo: String,
      number: Int,
      label: String,
      headers: Map[String, String] = Map()
  ): F[GHResponse[List[Label]]]

}

object Labels {
  def apply[F[_]](implicit labels: Labels[F]): Labels[F] = labels
}
