## 1. Pin the behaviour being replaced

- [x] 1.1 Add a failing spec to `kdiff-runtime`'s `PatchSpec` proving that `patchKeyedList` today
  duplicates an element and drops another when its source holds two elements sharing a key, applying
  an **empty** change list (design, Context) — verify it fails against the current code and report the
  actual returned list, so the corruption is on the record before it is fixed
- [x] 1.2 Add a failing spec proving the round-trip guarantee does not currently hold for such a list:
  diff two instances whose keyed list holds a duplicate, apply, and compare — verify it fails, and
  report whether the failure is a wrong value or a wrong length

## 2. The rejection, in the runtime

- [x] 2.1 Add one private message builder used by both helpers, producing the message in design D3 from
  the list name, the key property name and the duplicated key value — verify by unit-asserting the
  exact string for a known input, so both call sites are held to one wording
- [x] 2.2 Make `compareKeyedList` reject a repeated key on **either** side, before appending any change,
  by comparing each `associateBy` result's size against its source list (design D1, D2) — verify with
  specs for a duplicate in `before`, a duplicate in `after`, and a duplicate present in both lists
  where the two lists are otherwise equal
- [x] 2.3 Confirm a rejected comparison contributes nothing to the caller's change list (design D2) —
  verify by catching the exception and asserting the `MutableList<Change>` it was given is still empty
- [x] 2.4 Give `patchKeyedList` defaulted `name` and `keyProperty` parameters so it can produce the
  specified message, keeping a hand-written caller that omits them compiling (design D7) — verify by
  calling it both with and without the new arguments
- [x] 2.5 Make `patchKeyedList` reject a repeated key in its source list by the same rule and message
  (design D1) — verify the specs from 1.1 and 1.2 now pass, and that the rejection happens for an empty
  change list
- [x] 2.6 Emit the two new arguments from the processor's keyed-list patch call (design D7) — verify by
  regenerating `kdiff-sample` and reading the `patchKeyedList` call in `OrderDiff.kt`, and add a
  processor spec that compiles a keyed model and asserts the generated patcher rejects a duplicate
- [x] 2.7 State the uniqueness precondition in the KDoc of both helpers and of `Differ.diff`
  (design, Risks) — verify the wording says a key identifies at most one element per list and what
  happens when it does not

## 3. Prove it reaches every route

- [x] 3.1 Add a spec proving a `differ { }` built with `keyedList` rejects a duplicate identically to a
  generated differ (design D4) — verify against the `diff-generation` scenario "A repeated key is
  rejected the same way for a hand-written differ"
- [x] 3.2 Add a `kdiff-sample` spec proving a generated differ rejects a duplicate in `Order`'s keyed
  `addresses` list — verify it exercises the generated code path rather than calling the runtime helper
  directly
- [x] 3.3 Add a spec proving the rejection happens beneath tracking and is **not** suppressed by a scope
  that excludes the offending property (design D5) — verify with a `Tracker` whose scope names only an
  unrelated property, and with `trackedDiff`, so the ordering is pinned rather than assumed
- [x] 3.4 Add a spec proving a hand-written `Patcher` using `patchKeyedList` rejects identically —
  verify against the `diff-application` scenario for a hand-written patcher

## 4. Prove the boundaries

- [x] 4.1 Add a spec proving the same key appearing once in each list is **not** a duplicate and still
  matches as one element — verify it guards the off-by-one risk of checking across lists rather than
  within one
- [x] 4.2 Add a spec proving an unkeyed list holding two equal elements still compares by position and
  does not throw — verify `comparePositionalList` and `patchPositionalList` are untouched
- [x] 4.3 Confirm sets and maps are unaffected — verify by reading `compareSet` and `compareMap` and
  stating why neither can present the condition, rather than adding a test for an impossible input

## 5. Invert the record

- [x] 5.1 Rewrite `CompareSpec`'s "a repeated key keeps the last element carrying it, and the earlier
  one is never compared" to assert the rejection, keeping it in the keyed-lists context (design D6) —
  verify no test anywhere still asserts the last-wins collapse, by grepping for the old expectations

## 6. Documentation

- [x] 6.1 Rewrite the duplicate-key passage in `docs/diffing.md` under Lists: it currently documents
  the collapse as behaviour, and must state the precondition and the rejection — verify any
  `<!-- from: -->` block it carries still cites lines that exist, since the spec it quotes is being
  rewritten by 5.1
- [x] 6.2 Remove the "A repeated key silently collapses" bullet from `docs/architecture.md`'s "What
  kdiff does not do" and, if the section still warrants a keyed-list entry, state the precondition
  instead — verify the section no longer describes behaviour the library does not have
- [x] 6.3 Add an `Unreleased` entry to `CHANGELOG.md` describing the uniqueness precondition, what
  throws when it is violated, and what to do about a key that is not unique — verify it describes
  current behaviour without narrating what the library used to do

## 7. Verification

- [x] 7.1 Confirm the generated sources differ **only** by the two new `patchKeyedList` arguments, so
  no generated declaration changed shape and no consumer recompiles differently (design D7) — verify by
  regenerating with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and diffing
  `kdiff-sample/build/generated/ksp/main/kotlin/demo/*.kt` against the copy taken before the change,
  reporting the diff in full
- [x] 7.2 Confirm every scenario in both delta specs has a test, by walking the two files scenario by
  scenario and naming the test that covers each — report any scenario left uncovered rather than
  assuming the suite is complete
- [x] 7.3 Confirm no Kotlin, KSP, KotlinPoet, Gradle or Kotest version moved and no build script
  changed — verify by diffing `gradle/libs.versions.toml` and every `build.gradle.kts`
- [x] 7.4 Run `./gradlew check` and report the result verbatim
