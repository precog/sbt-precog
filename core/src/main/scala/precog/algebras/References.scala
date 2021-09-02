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

trait References[F[_]] {

  /**
   * Delete a Reference
   *
   * The ref in the URL must be fully qualified. For example, the call to delete `master` branch
   * will be `refs/heads/master`.
   *
   * @param owner
   *   of the repo
   * @param repo
   *   name of the repo
   * @param ref
   *   The name of the fully qualified reference (ie: refs/heads/master). If it doesn't start
   *   with 'refs' and have at least two slashes, it will be rejected.
   * @param headers
   *   optional user headers to include in the request
   * @return
   *   a GHResponse with the Ref
   */
  def deleteReference(
      owner: String,
      repo: String,
      ref: String,
      headers: Map[String, String] = Map()): F[GHResponse[Unit]]
}

object References {
  def apply[F[_]](implicit references: References[F]): References[F] = references
}
