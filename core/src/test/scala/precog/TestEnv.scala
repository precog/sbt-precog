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

import github4s.domain.{Label, PullRequestBase}
import precog.domain.PullRequestDraft

case class TestEnv(
    issuesCounter: Int,
    prs: Map[(String, String), List[PullRequestDraft]],
    labels: Map[(String, String, Int), List[Label]],
    refs: Map[(String, String), Set[String]],
    cmds: List[(String, String)],
    time: Long) {

  def withPR(
      owner: String,
      repo: String,
      title: String,
      body: String,
      from: String,
      to: String,
      state: String,
      draft: Boolean)
      : TestEnv = {
    val prList = prs.getOrElse((owner, repo), Nil)
    val pr = PullRequestDraft(
      issuesCounter, issuesCounter, s"node_$issuesCounter", state, title, Some(body), false, draft,
      s"https://github.com/$owner/$repo", issuesCounter.toString, None, None, None, None,
      Some(PullRequestBase(Some(to), to, (issuesCounter + 1).toString, None, None)),
      Some(PullRequestBase(Some(from), from, issuesCounter.toString, None, None)),
      None, None)
    val refSet = refs.getOrElse((owner, repo), Set.empty)
    copy(
      prs = prs.updated((owner, repo), pr :: prList), issuesCounter = issuesCounter + 1,
      refs = refs.updated((owner, repo), refSet + to + from))
  }

  def withLabel(owner: String, repo: String, id: Int, name: String): TestEnv = {
    val labelList = labels.getOrElse((owner, repo, id), Nil)
    val label = Label(Some(id), name, "", "", None)
    copy(labels = labels.updated((owner, repo, id), label :: labelList))
  }
}

object TestEnv {
  val empty: TestEnv =
    TestEnv(1, Map.empty, Map.empty, Map.empty, Nil, 0)
}
