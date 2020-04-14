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

class AutoBumpTest extends Specification {

  "ChangeLabel" should {
    import AutoBump.ChangeLabel
    "deserialize" in {
      ChangeLabel("version: revision") === Some(ChangeLabel.Revision)
      ChangeLabel("version: feature") === Some(ChangeLabel.Feature)
      ChangeLabel("version: breaking") === Some(ChangeLabel.Breaking)
    }

    "serialize" in {
      ChangeLabel.Revision.label === "version: revision"
      ChangeLabel.Feature.label === "version: feature"
      ChangeLabel.Breaking.label === "version: breaking"
    }

    "pattern match" in {
      todo
    }

    "be ordered" in {
      todo
    }
  }
}
