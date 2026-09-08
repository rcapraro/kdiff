## 1. Runtime

- [x] 1.1 In `comparePositionalList` (`kdiff-runtime/.../Compare.kt`), compute the common trailing run
  with one backward scan bounded by `min(before.size, after.size)`, using each branch's own predicate
  for "would report nothing" — `==` when `differ == null`, `differ.diff(...).isEmpty()` when it is not
  (design D2, D5). Compare only the window that remains, emitting element changes and removals at old
  indices and additions at new ones (design D3). Verify `./gradlew :kdiff-runtime:compileKotlin`
  succeeds and the existing `CompareSpec` still passes except where it asserts the old cascade.
- [x] 1.2 Update the `comparePositionalList` KDoc to state the exclusion rule and the index-coordinate
  rule, since a caller writing `object : Differ<T>` by hand reads that helper rather than the builder
  (design D3, *Documentation* §5). Verify by reading the rendered KDoc in the file.

## 2. Runtime specs

- [x] 2.1 Add a `PositionalListAlignmentSpec` covering the contiguous-edit scenarios from
  `specs/diff-generation/spec.md`: head insertion, head removal, an interior run inserted, an interior
  run removed, and the same for a list of elements reached through a differ. Verify each reports
  exactly the changes the scenario names, at the indices it names.
- [x] 2.1a Add the index-rule scenarios (design D3): a comparison reports additions or removals but
  never both; every element change names a position both lists hold, with additions and removals at
  indices at or after it; and an insertion earlier than a changed element is *not* aligned, reporting
  position by position as the stated ceiling. Verify the first two hold across the enumerated domain
  from 2.2 rather than on one example.
- [x] 2.2 Add the equal-length guarantee to the same spec: the three-place ranking scenario, and a
  check over **every** equal-length pair up to length 4 asserting the changes equal those a plain
  index-by-index oracle reports (design D2, `G5`). Exhaustion rather than sampling, matching
  `ResolvedScopeEquivalenceSpec`, so no property-testing dependency is added — the proposal's Impact
  says none. Paired with a test that the enumerated domain actually reaches pairs with an agreeing
  tail, so the oracle check cannot pass vacuously.
- [x] 2.3 Add the boundary scenarios: `["a"]` against `["a", "a"]` reporting one addition at index 0
  and one removal at index 0 in reverse (design D4), and two lists agreeing at neither end reporting
  exactly what a position-by-position comparison reports.
- [x] 2.4 Add the two predicate-guard scenarios that fail if the trim is ever weakened to `==`
  (design D5): an element type whose `equals` is looser than its differ, and a type with a compared
  property its `equals` does not cover. Verified by mutation: replacing the predicate with `==` fails
  exactly these two tests and no others.
- [x] 2.5 Extend `PatchSpec` with the interior round-trips from `specs/diff-application/spec.md`:
  head insertion, an interior run inserted, an interior run removed, an insertion plus a later element
  change, and a list of nested annotated elements gaining a head element. Verify each reproduces the
  target list exactly, including order, with no failures.
- [x] 2.6 Re-run the existing unkeyed-list assertions across `CompareSpec`, `CompareOrderSpec`,
  `DiffCollectionSpec`, `DslSpec`, `PatchSpec` and the tracking specs. `./gradlew :kdiff-runtime:test`
  is green with **no edit needed**: every existing positional assertion either uses equal-length lists
  (where `G5` makes the output identical) or a trailing-only edit (where the agreeing tail is empty).
  No test encoded the cascade, so nothing was re-baselined and no assertion was weakened.

## 3. Consumers

- [x] 3.1 Run `./gradlew :kdiff-sample:test` and `:kdiff-tutorial:test`. Both green with no edit; the
  generated output is unchanged (design D6) and `git status` shows no file outside `kdiff-runtime`
  touched, so `AnnotatedParitySpec` still holds both routes to identical output.
- [x] 3.2 Confirm the processor is untouched: verify `./gradlew :kdiff-processor:test` passes with no
  change to its snapshot assertions, since the emitted `comparePositionalList` call is identical.

## 4. Documentation

- [x] 4.1 Apply the five replacements verbatim from `design.md` — *Documentation*: the
  `docs/architecture.md` cost-table row and *What kdiff does not do* bullet, the `docs/diffing.md`
  *Without a key* paragraph, the `docs/hand-written.md` builder table row for `list`, and the
  `DifferBuilder.list` KDoc. Verify `docs/architecture.md`'s "no edit-distance search anywhere in the
  library" sentence is left untouched, and that the bullet still states the remaining ceiling.
- [x] 4.2 Add the `CHANGELOG.md` entry: the one-sentence description, the before/after example, and
  the `BREAKING` note that an unkeyed list whose length differs now reports the insertion or deletion
  instead of a cascade. Written under the existing `[Unreleased]` heading rather than a new version
  section — picking the version number is a release decision, since the tag must match `version` in
  the root `build.gradle.kts` (still `0.4.0`); the entry moves under that heading when the release is
  cut.

## 5. Done

- [x] 5.1 Verify no ABI dump changed: `comparePositionalList` keeps its signature, so
  `./gradlew checkLegacyAbi` passes without running `updateKotlinAbi`.
- [x] 5.2 Run `./gradlew check` and verify it passes, including ktlint, detekt and ABI validation.
  Green. Detekt caught `LoopWithTooManyJumpStatements` on the first shape of the tail scan; the fix
  hoists the predicate into a named `agrees(old, new, differ)`, which is also where D5's rationale now
  lives.
