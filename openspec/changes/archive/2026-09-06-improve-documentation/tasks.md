## 1. The version guard rail

- [x] 1.1 Add `systemProperty("kdiff.version", project.version.toString())` to `tasks.test` in
  `kdiff-sample/build.gradle.kts`, alongside the existing `kdiff.repoRoot` (design D1) — verify the
  property is readable from a test run through Gradle and reports `0.2.0`, matching
  `build.gradle.kts:12`
- [x] 1.2 Add a test to `DocumentationSamplesSpec` that scans the raw text of `README.md` and every
  `docs/*.md` for `io.github.kdiff:<module>:<version>` and asserts each match's version equals the
  `kdiff.version` property (design D1) — verify it currently **fails** on the three `0.1.0`
  coordinates at `README.md:84-86`, naming them, before task 2.1 fixes them
- [x] 1.3 Confirm the new test scans page text rather than only fenced Kotlin blocks, so a coordinate
  in prose or a shell snippet is covered (design D1) — verify by temporarily placing a wrong
  coordinate outside a ` ```kotlin ` block and checking the test fails, then removing it
- [x] 1.4 Document the version assertion in `CONTRIBUTING.md`'s Documentation section, beside the
  existing `from:` / `illustrative` marker convention — verify the section now describes both what the
  harness checks about samples and what it checks about coordinates

## 2. Accuracy corrections

- [x] 2.1 Correct the three install coordinates in `README.md` from `0.1.0` to `0.2.0` (design D1) —
  verify the test from 1.2 now passes and `kdiff-processor` is still declared through `ksp(...)` and
  never `implementation(...)`, preserving `diff-generation`'s runtime-classpath guarantee
- [x] 2.2 Rewrite the README's Status section so it names no version, linking to `CHANGELOG.md` and
  the releases page instead (design D1) — verify no version string remains anywhere in `README.md`
  outside the install snippet, by grepping for a semver pattern and reporting every hit
- [x] 2.3 Correct `docs/architecture.md`: "Four modules" becomes five, and the module diagram gains
  `kdiff-tutorial` — verify the page's module list matches the five directories in the repository root
  and does not contradict `CLAUDE.md`'s module graph
- [x] 2.4 Correct the sentence introducing the README's tracking example, which credits `@Trackable`
  for a snippet whose `Address` carries no such annotation (design D9) — verify `demo.Address` is
  unchanged, the snippet itself is unchanged, and the new sentence is true of the code shown
- [x] 2.5 Add `under()` to the README's routing example so the 0.2.0 feature is visible from the front
  page — verify the example is still valid against `Route.kt`'s `under` signature and that any block
  carrying a `from:` marker still passes `./gradlew :kdiff-sample:test`

## 3. Facts to establish before they are written down

- [x] 3.1 Determine what a self-referential `@Diffable` data class actually does — compile one and run
  it (design D5) — verify by reporting the observed outcome verbatim; the limitations section states
  that outcome and nothing beyond it, and says nothing if the result is inconclusive
- [x] 3.2 Determine what `compareKeyedList` does with a duplicate key, given it matches elements with
  `associateBy` (design D7) — verify with a Kotest case in `kdiff-runtime` exercising a list holding
  two elements with the same key, and report which element is compared and what is reported
- [x] 3.3 Read `compareSet` and `compareMap` and state their cost from the code, rather than inferring
  it from the list cases (design D6) — verify the stated cost matches what the functions do, and
  record that no benchmark is being added so none may be cited
- [x] 3.4 Run `./gradlew :kdiff-tutorial:run` and compare its output line by line against the console
  block in `docs/tutorial.md` (design D8) — verify by reporting the actual output; correct the block if
  it has drifted, and note that this block is outside the harness and checked by hand

## 4. Diagrams

- [x] 4.1 Convert `docs/architecture.md`'s module diagram from Unicode box-drawing to plain ASCII
  while adding the fifth module from 2.3 (design D2) — verify no character outside 7-bit ASCII remains
  in the diagram, and that it renders aligned in a terminal at 100 columns
- [x] 4.2 Add the three-capabilities diagram to `docs/architecture.md`, showing the "does it
  construct?" axis that decides which capabilities get a builder (design D3) — verify it agrees with
  the existing table rather than restating it, and that the table is kept
- [x] 4.3 Add a diagram of a change and its path to `docs/diffing.md`, joining the `Change` variants to
  the `Segment` kinds a path is built from (design D3) — verify every variant and segment shown matches
  `Diff.kt` and `FieldPath.kt`, and that both existing tables are kept
- [x] 4.4 Add the `under` frame dispatch diagram to `docs/diffing.md`, showing where a change named by
  no handler goes: a frame's own `otherwise` if declared, else back out to the enclosing routing at the
  path it arrived with (design D3) — verify against `Route.kt` and `RouteSpec.kt`, and that the diagram
  covers the change reported *at* a framed property, which is treated as unhandled
- [x] 4.5 Add the depth-counting diagram to `docs/tracking.md`, showing which path segments consume a
  step and which do not (design D3) — verify the examples match the existing path/steps table and the
  cases in `OrderTrackingSpec`, and that the table is kept
- [x] 4.6 Confirm every diagram added in this group is plain ASCII, sits outside a ` ```kotlin ` fence,
  and is therefore invisible to `DocumentationSamplesSpec` (design D2) — verify `./gradlew
  :kdiff-sample:test` still passes and reports no new unmarked-block failure

## 5. Navigation and orientation

- [x] 5.1 Add a "Where to go next" footer to `docs/diffing.md`, `docs/patching.md`,
  `docs/tracking.md`, `docs/hand-written.md`, `docs/annotations.md` and `docs/architecture.md`,
  matching the one `docs/tutorial.md` already has (design D4) — verify each footer lists the pages a
  reader of *that* page would want next rather than all six every time, and that every link resolves
- [x] 5.2 Add a single opening line saying who the page is for to `docs/diffing.md`,
  `docs/patching.md` and `docs/tracking.md`, which currently open on an API block (design D4) — verify
  the other four pages already do this and are left untouched
- [x] 5.3 Confirm no `docs/index.md` or `docs/README.md` was added and the README's table remains the
  only index (design D4) — verify by listing `docs/` and checking it holds exactly the seven existing
  pages

## 6. Completeness

- [x] 6.1 Add a "What kdiff does not do" section to `docs/architecture.md` covering index-by-index
  unkeyed lists, per-property rather than per-type custom comparison, tree-rooted comparison with the
  outcome established in 3.1, and Kotlin/JVM only (design D5) — verify each bullet against the code it
  describes, and that the pre-`1.0.0` point cross-references the README's Status rather than repeating
  it
- [x] 6.2 Add a "What a comparison costs" section to `docs/architecture.md` beside the existing
  explanation of why algorithms are not generated, stating the linear bound that `compareKeyedList` and
  `comparePositionalList` actually have, plus the set and map costs from 3.3 (design D6) — verify no
  wall-clock figure or benchmark is claimed
- [x] 6.3 Give `DiffNode` and `tree()` a worked example in `docs/diffing.md`, showing the node shape
  and `allChanges()`, replacing the single paragraph — verify the example carries a `from:` marker
  citing `ViewSpec.kt`, whose cases already cover grouping by prefix, the empty tree, a change at the
  root, and that the tree holds exactly what the flat list holds
- [x] 6.4 Move the "a swap reports two moves" rule into `docs/diffing.md` under Lists, keeping the
  tutorial's sentence about its own output (design D7) — verify the rule follows from the keyed-list
  behaviour described around it and matches `CompareSpec.kt`
- [x] 6.5 Document the duplicate-key behaviour established in 3.2, on `docs/diffing.md` under Lists
  (design D7) — verify the page states the observed behaviour only, and does not propose a diagnostic;
  if one seems warranted, record it in the change result as a candidate for a separate change

## 7. Tutorial

- [x] 7.1 Add the command-to-events flow diagram near the top of `docs/tutorial.md`, after the thesis
  paragraph it illustrates (design D8) — verify every named step exists in
  `UpdatePersonHandler.kt`, `UpdatePerson.kt` and `PersonDiffing.kt`, and that the diagram is plain
  ASCII
- [x] 7.2 Add one line to the tutorial's opening stating that the annotated mirror is at the end, with
  a link to that section (design D8) — verify the anchor resolves and the page's order is otherwise
  unchanged
- [x] 7.3 Apply any correction to the console output block found by 3.4 — verify by reporting the
  block and the run output side by side

## 8. Verification

- [x] 8.1 Confirm no file under any `src/main` in any module changed — verify by listing every file
  this change touched and checking the only source file is
  `kdiff-sample/src/test/kotlin/demo/DocumentationSamplesSpec.kt`, plus any spec added by 3.2
- [x] 8.2 Confirm the generated sample sources are byte-identical to before this change, proving no
  behaviour moved — verify by regenerating with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and
  comparing `kdiff-sample/build/generated/ksp/main/kotlin/demo/*.kt`
- [x] 8.3 Confirm `docs/architecture.md` and `CLAUDE.md` agree after the corrections, with `CLAUDE.md`
  stating the rules and `docs/architecture.md` explaining them — verify by reading both module-graph
  sections and reporting any statement present in one and contradicted by the other
- [x] 8.4 Verify every internal link in `README.md` and `docs/*.md` resolves to a file and anchor that
  exists, including the footers added in 5.1
- [x] 8.5 Confirm `gradle/libs.versions.toml`, the root `build.gradle.kts`, both files under
  `.github/workflows/` and `CHANGELOG.md` are unchanged — verify by diffing them
- [x] 8.6 Run `./gradlew check` and report the result verbatim
