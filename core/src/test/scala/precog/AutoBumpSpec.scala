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

import java.io.File
import java.nio.file.{Files, Path}

import org.scalacheck.{Arbitrary, Gen}
import org.specs2.execute.ResultImplicits
import org.specs2.main.CommandLine
import org.specs2.mutable.Specification

import cats.effect.IO
import cats.implicits._
import github4s.GithubResponses.{GHException, GHResult, UnexpectedException}
import github4s.domain.{Label, Pagination, PullRequestBase}
import precog.domain.PullRequestDraft

class AutoBumpSpec(params: CommandLine) extends Specification with org.specs2.ScalaCheck with ResultImplicits {
  val log = sbt.util.LogExchange.logger("test")
  val tmpdir: Path = params.value("tmpdir") map { f =>
    val file = new File(f, getClass.getName.replace('.', '/'))
    file.mkdirs()
    file.toPath.pp("tmpdir: ")
  } getOrElse Files.createTempDirectory(getClass.getSimpleName).pp("tmpdir: ")


  "ChangeLabel" should {
    import AutoBump.ChangeLabel
    def abChangeLabelGen: Gen[ChangeLabel] = Gen.oneOf(ChangeLabel.values)
    implicit def abChangeLabel: Arbitrary[ChangeLabel] = Arbitrary(abChangeLabelGen)

    "include all values" in {
      ChangeLabel.values.toSet mustEqual Set(ChangeLabel.Revision, ChangeLabel.Feature, ChangeLabel.Breaking)
    }

    "implement equality" in prop { (a: ChangeLabel, b: ChangeLabel) =>
      a mustEqual a
      (a mustNotEqual b) <==> !a.eq(b)
      (a mustEqual b) <==> a.eq(b)
    }

    "deserialize" in {
      ChangeLabel("version: revision") must beSome(ChangeLabel.Revision)
      ChangeLabel("version: feature") must beSome(ChangeLabel.Feature)
      ChangeLabel("version: breaking") must beSome(ChangeLabel.Breaking)
    }

    "deserialize with pattern recognition" in prop { (prefix: String, cl: ChangeLabel, suffix: String) =>
      prefix + cl.label + suffix must beLike {
        case ChangeLabel(changeLabel) => cl mustEqual changeLabel
      }
    }

    "serialize" in {
      ChangeLabel.Revision.label mustEqual "version: revision"
      ChangeLabel.Feature.label mustEqual "version: feature"
      ChangeLabel.Breaking.label mustEqual "version: breaking"
    }

    "be ordered" in {
      Set(ChangeLabel.Revision, ChangeLabel.Feature).max mustEqual (ChangeLabel.Feature)
      Set(ChangeLabel.Revision, ChangeLabel.Breaking).max mustEqual (ChangeLabel.Breaking)
      Set(ChangeLabel.Feature, ChangeLabel.Breaking).max mustEqual (ChangeLabel.Breaking)
    }
  }

  "AutoBump Object" should {
    import AutoBump._
    "extract label" in {
      val lines =
        """
          |[info] Loading settings for project sbt-precog4454409616373722978-build from build.sbt,plugins.sbt ...
          |[info] Loading project definition from /tmp/sbt-precog4454409616373722978/project
          |[warn] There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction warnings.
          |[info] Compiling 2 Scala sources to /tmp/sbt-precog4454409616373722978/project/target/scala-2.12/sbt-1.0/classes ...
          |[info] Done compiling.
          |[info] Loading settings for project root from version.sbt,build.sbt ...
          |[info] looking for workflow definition in /tmp/sbt-precog4454409616373722978/.github/workflows
          |[info] Set current project to root (in build file:/tmp/sbt-precog4454409616373722978/)
          |[info] Updated revision precog-tectonic 11.0.16 -> 11.0.17
          |[info] version: revision
          |[success] Total time: 1 s, completed Apr 14, 2020 6:10:03 PM
          |""".stripMargin.split('\n').toList
      val label = extractLabel(lines).unsafeRunSync()

      label must beRight(ChangeLabel.Revision)
    }

    "warn if no label exists" in {
      val lines =
        """
          |[info] Loading global plugins from /Users/dcsobral/.sbt/1.0/plugins
          |[info] Loading settings for project sbt-precog8555711038951371949-build from plugins.sbt ...
          |[info] Loading project definition from
          | /private/var/folders/cl/3tsnm535351gs04s8b0v9qdm0000gn/T/sbt-precog8555711038951371949/project
          |[warn] There may be incompatibilities among your library dependencies; run 'evicted' to
          | see detailed eviction warnings.
          |[info] Loading settings for project sbt-precog8555711038951371949 from build.sbt ...
          |[info] Set current project to electron (in build
          | file:/private/var/folders/cl/3tsnm535351gs04s8b0v9qdm0000gn/T/sbt-precog8555711038951371949/)
          |[info] Set current project to electron (in build
          | file:/private/var/folders/cl/3tsnm535351gs04s8b0v9qdm0000gn/T/sbt-precog8555711038951371949/)
          |[info] Reapplying settings...
          |[info] Set current project to electron (in build
          | file:/private/var/folders/cl/3tsnm535351gs04s8b0v9qdm0000gn/T/sbt-precog8555711038951371949/)
          |[success] Total time: 3 s, completed Apr 15, 2020 12:25:43 AM
          |""".stripMargin.split('\n').toList

      val label = extractLabel(lines).unsafeRunSync()

      label must beLeft(Warnings.NoLabel)
    }

    "extract changes" in {
      val lines =
        """
          |[info] Loading settings for project sbt-precog4454409616373722978-build from build.sbt,plugins.sbt ...
          |[info] Loading project definition from /tmp/sbt-precog4454409616373722978/project
          |[warn] There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction warnings.
          |[info] Compiling 2 Scala sources to /tmp/sbt-precog4454409616373722978/project/target/scala-2.12/sbt-1.0/classes ...
          |[info] Done compiling.
          |[info] Loading settings for project root from version.sbt,build.sbt ...
          |[info] looking for workflow definition in /tmp/sbt-precog4454409616373722978/.github/workflows
          |[info] Set current project to root (in build file:/tmp/sbt-precog4454409616373722978/)
          |[info] Updated revision precog-tectonic 11.0.16 -> 11.0.17
          |[info] Updated revision precog-qdata 14.0.20 -> 14.0.23
          |[info] version: revision
          |[success] Total time: 1 s, completed Apr 14, 2020 6:10:03 PM
          |""".stripMargin.split('\n').toList
      val changes = extractChanges(lines)

      changes mustEqual List(
        "Updated revision precog-tectonic 11.0.16 -> 11.0.17",
        "Updated revision precog-qdata 14.0.20 -> 14.0.23")
    }

    "get next page" in {
      val headersWithNext = Map(
        "Link" ->
          """
            |<https://api.github.com/search/code?q=addClass+user%3Amozilla&page=2>; rel="next",
            |  <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=34>; rel="last"
            |""".stripMargin.split('\n').mkString
      )

      nextPage(getRelations(headersWithNext)) must beSome(Pagination(2, 100))

      val headersWithoutNext = Map(
        "Link" ->
          """
            |<https://api.github.com/search/code?q=addClass+user%3Amozilla&page=33>; rel="prev",
            |  <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=34>; rel="last"
            |""".stripMargin.split('\n').mkString
      )

      nextPage(getRelations(headersWithoutNext)) must beNone

      val headersWithPerPage = Map(
        "Link" ->
          """
            |<https://api.github.com/search/code?q=addClass+user%3Amozilla&per_page=50&page=2>;
            | rel="next",
            |  <https://api.github.com/search/code?q=addClass+user%3Amozilla&per_page=50&page=20>;
            |  rel="last"
            |""".stripMargin.split('\n').mkString
      )

      nextPage(getRelations(headersWithPerPage)) must beSome(Pagination(2,50))
    }

    "autopage" in {
      val list = (1 to 10).toList
      val chunker: Pagination => IO[Either[GHException, GHResult[List[Int]]]] = {
        case Pagination(page, perPage) =>
          val chunk = list.slice((page - 1) * perPage, page * perPage)
          val nextPage = page + 1
          val headers = if (list.size > page * perPage) Map(
            "Link" ->
              s"""
                 |<https://api.github.com/nothing/really?page=${nextPage}
                 |&per_page=${perPage}>; rel="next"
                 |""".stripMargin.split('\n').mkString
          ) else Map.empty[String, String]
          IO.pure(GHResult(chunk, 200, headers).asRight[GHException])
      }
      val stream = autoPage(Pagination(1, 3))(chunker)

      stream.compile.toList.unsafeRunSync() mustEqual List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    }

    "return error on failure when autopaging" in {
      val chunker: Pagination => IO[Either[GHException, GHResult[List[Int]]]] = {
        case Pagination(page, perPage) =>
          val chunk = (page until perPage).toList
          val nextPage = perPage + chunk.size + 4
          val headers = if (page < 10) Map(
            "Link" ->
              s"""
                 |<https://api.github.com/nothing/really?page=${perPage}
                 |&per_page=${nextPage}>; rel="next"
                 |""".stripMargin.split('\n').mkString
          ) else Map.empty[String, String]
          if (page > 1) IO.pure(UnexpectedException("test").asLeft)
          else IO.pure(GHResult(chunk, 200, headers).asRight)
      }
      val stream = autoPage(Pagination(1, 5))(chunker)

      stream.compile.toList.attempt.unsafeRunSync() must beLeft
    }

    "get existing pull request branch" in {
      val pr = PullRequestDraft(10, 5, "sha1", "open", "Test PR", None, false, false, "html",
        "yesterday", None, None, None, None,
        Some(PullRequestBase(None, "master", "sha3", None, None)),
        Some(PullRequestBase(None, "trickle/test", "sha2", None, None)),
        None, None)

      getBranch(Some(pr)).unsafeRunSync() mustEqual ("" -> "trickle/test")
    }

    "get new branch" in {
      getBranch(None).unsafeRunSync() must beLike {
        case ("-b", branch) => branch must beMatching("trickle/version-bump-\\d+".r)
      }

      val b1 = getBranch(None).unsafeRunSync()
      Thread.sleep(10)
      val b2 = getBranch(None).unsafeRunSync()
      b1 mustNotEqual b2
    }

    "identify autobump pull request" in {
      val pr = PullRequestDraft(10, 5, "sha1", "open", "Test PR", None, false, false, "html",
        "yesterday", None, None, None, None,
        Some(PullRequestBase(None, "master", "sha3", None, None)),
        Some(PullRequestBase(None, "trickle/test", "sha2", None, None)),
        None, None)

      def toLabel: String => Label =
        Label(None, _, "https://api.github.com/label/none", "#ff0000", None)

      val labels = List(ChangeLabel.Revision.label, AutoBumpLabel, "dev-verify")
        .map(toLabel)

      AutoBumpLabel mustEqual ":robot:"

      isAutoBump(pr, labels) must beTrue
      isAutoBump(pr, labels.filterNot(_.name == AutoBumpLabel)) must beFalse
      isAutoBump(pr, Nil) must beFalse
      isAutoBump(pr.copy(head = pr.base), labels) must beFalse
      isAutoBump(pr.copy(draft = true), labels) must beTrue
    }

    "get sbt" in {
      todo
    }
  }

  "AutoBump" should {
    "create pull requests that it identifies as its own" in {
      todo
    }

    "create pull requests with a version label" in {
      todo
    }
  }
}
