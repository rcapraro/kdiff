## 1. Build gates first, so every later commit is checked by them

- [x] 1.1 Write `.editorconfig` at the repository root stating `ktlint_code_style = intellij_idea`,
      4-space indent, 120 columns, LF, final newline and the `ij_kotlin_*` import settings, and verify
      `./gradlew ktlintCheck` configures and reports violations rather than failing to run
- [x] 1.2 Add `org.jlleitschuh.gradle.ktlint` 14.2.0 (ktlint 1.8.0) to `gradle/libs.versions.toml` and
      the root build, excluding generated sources, and verify `ktlintCheck` covers every module's main
      and test source sets and no generated file (read the task list and one report)
- [x] 1.3 Disable in `.editorconfig`, with a stated reason, only a rule the project genuinely rejects
      — `standard:filename`, which would rename `Patch.kt` after the one class it holds beside the
      patch helpers that are the file's actual subject — and verify no source file carries a ktlint
      suppression (`grep -r ktlint-disable` finds nothing)
- [x] 1.3a Fix in the sources the violations ktlint cannot auto-correct — 3 lines over 120 columns and
      2 dangling top-level KDocs — and verify each fix is layout-only and `./gradlew check` stays green
- [x] 1.3b Run `./gradlew ktlintFormat`, verify `./gradlew check` is green afterwards with no
      assertion edited, and commit the result as a formatting-only commit containing nothing else
      (`build: add ktlint and reformat the sources to its rules` — the repo's conventional-commit
      types have no `style`) so the reformat stays reviewable in isolation
- [x] 1.4 Add detekt 2.0.0-alpha.6 — plugin id `dev.detekt`, version pinned exactly in
      `gradle/libs.versions.toml` (2.x moved the plugin id and Maven group off
      `io.gitlab.arturbosch.detekt`) — and verify the plugin *and its own dependencies* resolve by
      running `./gradlew detektGenerateConfig`, which is where the known alpha-series wrong-group bug
      would surface
- [x] 1.5 Check in the generated config at `config/detekt/detekt.yml`, exclude generated sources
      (`tasks.withType<dev.detekt.gradle.Detekt> { exclude("**/build/generated/**") }`), and disable in
      the config — one line each, with a reason — any rule the project does not intend to follow;
      verify `./gradlew detektMain detektTest` reports no finding and that no source file gained a
      suppression annotation (`git diff` touches no `.kt`)
- [x] 1.6 Wire the type-resolving `detektMain` and `detektTest` into each module's `check` — first read
      `./gradlew check --dry-run` to see what the plugin wires by itself and add only what is missing;
      verify a deliberately introduced violation makes `./gradlew check` fail naming the rule, the file
      and the line, then revert it
- [x] 1.7 Set `allWarningsAsErrors = true` on the Kotlin compilations of every module and verify
      `./gradlew check` still passes, fixing any warning it surfaces
- [x] 1.8 Enable KGP ABI validation (`kotlin { abiValidation() }` with
      `@OptIn(ExperimentalAbiValidation::class)`) for the three published modules only, wire
      `checkKotlinAbi` into each module's `check`, run `updateKotlinAbi` to record the current surface,
      and verify the dumps are checked in and `./gradlew check` passes
- [x] 1.9 Verify the ABI gate bites: delete one public declaration from `kdiff-runtime`, confirm
      `./gradlew check` fails naming it, then restore the declaration
- [x] 1.10 Verify the ABI gate ignores what it should: add an `internal` declaration to
      `kdiff-runtime`, confirm `./gradlew check` passes with no dump change, then remove it
- [x] 1.11 Add Dokka 2.2.0 to the three published modules, deliberately *not* wired into `check`, and
      verify `./gradlew dokkaGenerate` produces HTML covering the three modules and no page for
      sample, tutorial or benchmarks
- [x] 1.12 Make every failing gate name its fix command (ktlint → `ktlintFormat`, ABI →
      `updateKotlinAbi`) and verify by reading the failure output of 1.9 and of a deliberately
      misformatted file
- [x] 1.13 Confirm CI needs no new step: `./gradlew check` covers 1.2/1.6/1.7/1.8, and
      `.github/workflows/ci.yml` is unchanged. Verify by reading the workflow and the `check` task
      graph (`./gradlew check --dry-run`)

## 2. Declared exception types

- [x] 2.1 Add `Errors.kt` to `kdiff-runtime` with `DuplicateDiffKeyException` (property, key property,
      key; `IllegalArgumentException`), `CyclicStructureException` (path, repeated;
      `IllegalArgumentException`) and `PatchFailedException` (failures; `IllegalStateException`), each
      with `internal` constructors and KDoc; verify `./gradlew :kdiff-runtime:compileKotlin` and
      `updateKotlinAbi` records them
- [x] 2.2 Raise `DuplicateDiffKeyException` from `Compare.duplicateKey` and from the patch side,
      keeping the message wording, and verify a new `DuplicateKeyErrorSpec` asserts the type, its three
      inspectable properties, and that `catch (IllegalArgumentException)` still catches it
- [x] 2.3 Verify the existing duplicate-key specs still pass unchanged
      (`./gradlew :kdiff-runtime:test :kdiff-processor:test :kdiff-sample:test`), since the new type is
      a subtype of what they assert

## 3. Typed patch failure reasons

- [x] 3.1 Add `PatchFailure.Reason` as a sealed interface with the fourteen cases in design decision 1,
      and an internal `describe()` `when` producing today's sentence for each; verify a
      `PatchFailureReasonSpec` asserts every case renders the exact string the runtime produces now
- [x] 3.2 Change `PatchFailure.reason` to `Reason`, keep `toString()` as `"path: sentence"`, and update
      every construction site in `Patch.kt` and `Patcher.kt` (`unmatchedFailures`, `unpatchable`,
      `notConstructorProperty` and the ten inline sites) to pass a case; verify
      `./gradlew :kdiff-runtime:test` passes with the existing patch specs' rendered assertions
      unchanged
- [x] 3.3 Add `PatchResult.getOrThrow()` returning the value or raising `PatchFailedException` with
      every failure and a count in its message, and verify a spec covers the clean case, the partial
      case, and that a clean `getOrThrow()` equals the same result's `value`
- [x] 3.4 Update the assertions in `kdiff-processor`, `kdiff-sample` and `kdiff-tutorial` specs that
      match a failure reason as text so they match a case instead, and verify
      `./gradlew :kdiff-processor:test :kdiff-sample:test :kdiff-tutorial:test`
- [x] 3.5 Verify generated output is unchanged: run `./gradlew :kdiff-sample:kspKotlin --rerun-tasks`
      and confirm `git diff` on the generated sources is empty (they are not tracked, so compare
      against the copies captured before this change, or against the snapshot specs in
      `kdiff-processor`)

## 4. The descent bound and cyclic structures

- [x] 4.1 Add `MAX_DESCENT` and the internal `Descent` object (counter in a `ThreadLocal`, identity and
      segment recording from `MAX_DESCENT - 64` upward, `try`/`finally` decrement, list cleared at zero)
      and verify a `DescentSpec` covers: the counter returns to zero after a normal comparison, after a
      thrown comparison, and the recording list is empty afterwards
- [x] 4.2 Wrap the descent points in `Compare.kt` (`compareNested`, `compareNestedNullable`,
      `compareKeyedList`, `comparePositionalList`, `compareMap`) — and deliberately *not* `compareSet` —
      and verify `./gradlew :kdiff-runtime:test` passes with every existing comparison spec unchanged
- [x] 4.3 Wrap the descent points in `Patch.kt` (`patchNested`, `patchNestedNullable`,
      `patchKeyedList`, `patchPositionalList`, `patchMap`) and the subtype delegation in `Dsl.kt`, and
      verify the existing patch and DSL specs pass unchanged
- [x] 4.4 Verify the guard reports what the spec requires: a `CyclicStructureSpec` builds a genuine
      cycle (a `var` self-reference) and asserts `CyclicStructureException` naming the stopping path
      with `repeated == true`; builds an acyclic chain deeper than `MAX_DESCENT` and asserts
      `repeated == false` with a message saying no instance was re-entered; and asserts no
      `StackOverflowError` in either case
- [x] 4.5 Verify the guard covers applying too: the same spec applies a diff to a cyclic source and
      asserts the same exception type and stopping path
- [x] 4.6 Verify a self-referencing structure within the bound is unaffected: three-level
      `Node(name, next)` chains differing at the innermost `name` report one change at `next.next.name`
- [x] 4.7 Verify no reported change moved: run `./gradlew check` and confirm every comparison,
      tracking and routing spec passes with no assertion edited in this task group
- [x] 4.8 Verify the cost: run `./gradlew :kdiff-benchmarks:jmh` before and after task group 4 and
      confirm no regression beyond run-to-run noise. If it regresses, take the fallback in design
      decision 3 and re-measure before closing this task

## 5. `Diff` as a first-class value

- [x] 5.1 Make `Diff` implement `Iterable<Change>`, add `size`, `isNotEmpty()`, `plus`, a
      `vararg Change` secondary constructor and `Diff.EMPTY`; change `isEmpty` from a property to a
      function; verify a `DiffCollectionSpec` covers iteration order against `changes`, `size`,
      combine order, `Diff()` equalling `EMPTY`, and a stdlib operator (`filter`) applied directly
- [x] 5.2 Add `Diff.at(property)` and `Diff.under(property)` as `KProperty1` extensions returning
      `Diff`, and verify a `DiffNarrowingSpec` covers: `at` excluding what lies beneath, `under`
      including the property itself, order preserved, and narrowing composing with combining
- [x] 5.3 Update every `diff.isEmpty` call site in `kdiff-runtime`, `kdiff-sample`, `kdiff-tutorial`
      and `kdiff-benchmarks`, and verify `./gradlew check`
- [x] 5.4 Verify the `@DiffWith`/hand-written parity specs still hold
      (`HandWrittenParitySpec`, `AnnotatedParitySpec`) — `Diff` equality is unchanged, so they must
      pass with no edit

## 6. DSL scoping, shared scope surface and contracts

- [x] 6.1 Add `@KdiffDsl` (`@DslMarker`) and apply it to `DifferBuilder`, `TrackScopeBuilder`,
      `TrackerBuilder`, `ChangeRoutes`, `ElementRoutes` and `KeyedElementRoutes`; verify
      `./gradlew check` passes, proving no existing spec or sample relied on leaked scope
- [x] 6.2 Verify the marker bites: add a `kctfork` compile-testing spec (or a
      `kotlin-compile-testing` snippet in `kdiff-processor`'s suite) asserting that a nested `under { }`
      calling the outer routing's `on` fails to compile, that an element routing calling an enclosing
      member fails to compile, and that an explicitly qualified receiver still compiles
- [x] 6.3 Extract `ScopeDeclaration<T>` (`depth`, `field`, `field(depth)`, `under`, `except`) with one
      internal implementation, and have `TrackScopeBuilder` and `TrackerBuilder` both delegate to it;
      verify every existing tracking spec passes unchanged and `updateKotlinAbi` shows the members
      moving to the interface
- [x] 6.4 Validate depth wherever it is stated — the scope's own `depth` and `field(property, depth)`,
      through both routes — and verify a `ScopeValidationSpec` covers depth 0, depth -2, the unlimited
      depth accepted, the scope's own invalid depth, and an inline scope failing with the same message
      as the standalone builder
- [x] 6.5 Reject a `differ { }` naming no property and no subtype, with a message saying what is
      missing, and verify a spec covers the empty block rejected and a subtypes-only differ accepted
      and dispatching
- [x] 6.6 Add `contract { callsInPlace(block, EXACTLY_ONCE) }` to `differ`, `trackScope`, both
      `tracker` overloads and `Diff.route`, making each `inline` with the body kept to one line, and
      verify a spec assigns an uninitialised `val` inside each block and reads it after
- [x] 6.7 Verify the "indistinguishable from generated" requirement still holds after 6.3 and 6.4:
      `./gradlew :kdiff-runtime:test :kdiff-tutorial:test`, with `ResolvedScopeEquivalenceSpec`,
      `TrackScopeCompositionSpec` and `TrackScopeWideningSpec` passing unedited

## 7. Documentation and release notes

- [x] 7.1 Update `docs/patching.md` for the typed reasons and `getOrThrow`, and verify
      `DocumentationSamplesSpec` (which compiles the documented samples) passes
- [x] 7.2 Update `docs/diffing.md` for the `Diff` collection surface, narrowing and the declared
      exceptions, and verify `DocumentationSamplesSpec` passes
- [x] 7.3 Update `docs/hand-written.md` for the shared scope surface, the empty-differ rejection and
      the DSL marker, and verify `DocumentationSamplesSpec` passes
- [x] 7.4 Update `docs/tracking.md` for depth validation on both routes, and verify
      `DocumentationSamplesSpec` passes
- [x] 7.5 Remove "a genuine cycle overflows the stack" from *What kdiff does not do* in
      `docs/architecture.md`, replace it with the bound and what the exception says, and note that
      guard depth and tracking depth are unrelated bounds
- [x] 7.6 Document the build gates in `CONTRIBUTING.md`: the ABI dump and when to run
      `updateKotlinAbi`, the formatting command, that `./gradlew check` is still the one command, and
      that detekt is deliberately pinned to a 2.0 alpha because it is the release built against this
      project's Kotlin — with what to do if an alpha regresses
- [x] 7.7 Update `README.md` where it shows `diff.isEmpty` or a failure reason, and verify the README
      samples the documentation spec covers still pass
- [x] 7.8 Write the `CHANGELOG.md` section for this version — before any tag — listing each breaking
      item with its migration, taking the table from design.md's *Migration Plan*
- [x] 7.9 Update `CLAUDE.md` for what a contributor must now know: the ABI dump, the formatting and
      analysis gates, the descent bound, and that `PatchFailure.Reason` is a second closed vocabulary
      (adding a case is breaking, like adding a `Change` variant)

## 8. Definition of done

- [x] 8.1 Verify the generated API surface is unchanged end to end:
      `./gradlew :kdiff-sample:kspKotlin --rerun-tasks`, read every file in
      `kdiff-sample/build/generated/ksp/main/kotlin/demo/`, and confirm each matches the sample in
      design.md — no emitter was touched, so any difference means the runtime change went further than
      designed
- [x] 8.2 Verify the ABI dumps record exactly the intended breaks: read the `api/` diff and confirm
      every removal appears in the CHANGELOG's breaking list, and nothing appears that does not
- [x] 8.3 Run `./gradlew check` and confirm it is green
