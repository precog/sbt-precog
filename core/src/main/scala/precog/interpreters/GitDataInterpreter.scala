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

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import github4s.GithubResponses.GHResponse
import github4s.domain.{Ref, RefAuthor, RefCommit, RefInfo, Tag, TreeData, TreeResult}
import github4s.http.HttpClient
import precog.algebras.GitData

class GitDataInterpreter [F[_] : Sync](
    impl: github4s.algebras.GitData[F],
    client: HttpClient[F],
    accessToken: Option[String])
    extends GitData[F] {
  def deleteReference(owner: String, repo: String, ref: String, headers: Map[String, String]): F[GHResponse[Unit]] = {
    for {
      _ <- implicitly[Sync[F]].delay(assert(ref.matches("refs/(heads|tags)/\\w+"), s"Invalid reference $ref"))
      res <- client.delete(accessToken, s"repos/$owner/$repo/git/$ref")
    } yield res
  }

  def getReference(owner: String, repo: String, ref: String, headers: Map[String, String]): F[GHResponse[NonEmptyList[Ref]]] = {
    impl.getReference(owner, repo, ref, headers)
  }

  def createReference(owner: String, repo: String, ref: String, sha: String, headers: Map[String, String]): F[GHResponse[Ref]] = {
    impl.createReference(owner, repo, ref, sha, headers)
  }

  def updateReference(
      owner: String,
      repo: String,
      ref: String,
      sha: String,
      force: Boolean,
      headers: Map[String, String])
      : F[GHResponse[Ref]] = {
    impl.updateReference(owner, repo, ref, sha, force, headers)
  }

  def getCommit(owner: String, repo: String, sha: String, headers: Map[String, String]): F[GHResponse[RefCommit]] = {
    impl.getCommit(owner, repo, sha, headers)
  }

  def createCommit(
      owner: String,
      repo: String,
      message: String,
      tree: String,
      parents: List[String],
      author: Option[RefAuthor],
      headers: Map[String, String])
      : F[GHResponse[RefCommit]] = {
    impl.createCommit(owner, repo, message, tree, parents, author, headers)
  }

  def createBlob(
      owner: String,
      repo: String,
      content: String,
      encoding: Option[String],
      headers: Map[String, String])
      : F[GHResponse[RefInfo]] = {
    impl.createBlob(owner, repo, content, encoding, headers)
  }

  def getTree(
      owner: String,
      repo: String,
      sha: String,
      recursive: Boolean,
      headers: Map[String, String])
      : F[GHResponse[TreeResult]] = {
    impl.getTree(owner, repo, sha, recursive, headers)
  }

  def createTree(
      owner: String,
      repo: String,
      baseTree: Option[String],
      treeDataList: List[TreeData],
      headers: Map[String, String])
      : F[GHResponse[TreeResult]] = {
    impl.createTree(owner, repo, baseTree, treeDataList, headers)
  }

  def createTag(
      owner: String,
      repo: String,
      tag: String,
      message: String,
      objectSha: String,
      objectType: String,
      author: Option[RefAuthor],
      headers: Map[String, String])
      : F[GHResponse[Tag]] = {
    impl.createTag(owner, repo, tag, message, objectSha, objectType, author, headers)
  }
}
