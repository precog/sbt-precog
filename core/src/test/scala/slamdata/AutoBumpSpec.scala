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

import org.specs2.mutable.Specification

class AutoBumpSpec extends Specification {

  "ChangeLabel" should {
    import AutoBump.ChangeLabel

    "deserialize" in {
      ChangeLabel("version: revision") mustEqual Some(ChangeLabel.Revision)
      ChangeLabel("version: feature") mustEqual Some(ChangeLabel.Feature)
      ChangeLabel("version: breaking") mustEqual Some(ChangeLabel.Breaking)
    }

    "deserialize with pattern recognition" in {
      "version: revision" must beLike {
        case ChangeLabel(ChangeLabel.Revision) => ok
      }
      "version: feature" must beLike {
        case ChangeLabel(ChangeLabel.Feature) => ok
      }
      "version: breaking" must beLike {
        case ChangeLabel(ChangeLabel.Breaking) => ok
      }
    }

    "serialize" in {
      ChangeLabel.Revision.label mustEqual "version: revision"
      ChangeLabel.Feature.label mustEqual "version: feature"
      ChangeLabel.Breaking.label mustEqual "version: breaking"
    }

    "be ordered" in {
      todo
    }
  }
}
