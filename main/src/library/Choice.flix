/*
 * Copyright 2020 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
namespace Choice {

    ///
    /// Returns the value in `c` which must be `Present`.
    ///
    pub def unbox(c: Choice[a, false, _]): a = choose c {
        case Present(v) => v
    }

    ///
    /// Returns the given choice `c` as an `Option`.
    ///
//    pub def toOption(c: Choice[a, isAbsent, isPresent]): a = choose c { // TODO: Issue with kinds.
//        case Absent     => None
//        case Present(v) => Some(v)
//    }

    // TODO:

    // - def toOption(c: Choice[a, _, _]): Option[a]
    // - def pick2(c1: Choice[a, b1, b2], c2: Choice[a, b3, b4]): a
    // - def chooseLeft2(c1: Choice[a, ?, ?], c2: Choice[a, ?, ?]): a
    // - def zip(c1: ..., c2: ...): Option[(a, b)]
    // - def map(f: a -> b, c: Choice[a, b1, b2]): Choice[b, b1, b2])
    // - def Option.toChoice(o: Option[a]): Choice[a, T, T]

    // TODO: Add tests for all of these.

}
