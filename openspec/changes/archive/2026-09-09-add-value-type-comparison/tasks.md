## 1. Annotations

- [x] 1.1 Add `DiffAsValue` to `kdiff-annotations/.../Properties.kt` with `CLASS` and `PROPERTY`
  targets, binary retention and KDoc stating both meanings and the no-effect rule (design D4). Run
  `./gradlew :kdiff-annotations:updateKotlinAbi` and verify the dump gains exactly one annotation
  class.

## 2. Processor — what is a value

- [x] 2.1 Extend `VALUE_TYPES` in `Resolution.kt` with the qualified names from design D2, and make
  `isValueType()` also return true for a declaration carrying `Modifier.VALUE` or `@DiffAsValue`
  (design D3, D4). Verify with a kctfork spec that a `BigDecimal`, an `Instant`, a `UUID`, a
  `LocalDate` list element, a `BigDecimal` map value, an inline value class and a `@DiffAsValue` data
  class each compile without a property annotation and report one value change at their path.
- [x] 2.2 Confirm whether `kotlin.time.Instant` and `kotlin.uuid.Uuid` are stable in the pinned
  standard library by compiling a kctfork snippet that declares each; keep the names in the set either
  way and document only what compiled (design D2). Verify the doc list in 6.1 matches what the spec
  proved.
- [x] 2.3 Add a kctfork scenario that `java.util.Date` is still rejected, so the set is shown to be a
  list and not a package.

## 3. Processor — `@DiffAsValue`

- [x] 3.1 In `resolve()`, return `Comparison.ByValue` for a property carrying `@DiffAsValue` after the
  `@DiffWith` check and before the value test (design D1), adding the class-level type's containing file
  to the generated file's originating set when the value came from a `@DiffAsValue` declaration
  (design D7). Verify with kctfork specs for the nested-as-a-whole and collection-as-a-whole scenarios,
  compiling and invoking the generated code, and a spec that removing the class-level annotation from
  its file regenerates the dependent class.
- [x] 3.2 Report the five design D5 diagnostics — the class-level conflict with `@Diffable`, the
  no-effect cases on a class and on a property, and the two property-level conflicts — from
  `resolve()` and the class-level check beside `isSupported()`. Add `DIFF_AS_VALUE` to the
  annotations-without-`@Diffable` loop (introducing that loop for this annotation alone if
  `close-generation-gaps` has not landed). Verify one kctfork test per scenario in *A type or a property
  can be declared to compare as one value*, each asserting the verbatim message and the reported
  location.
- [x] 3.3 Amend the two "cannot compare" messages per design D6. Verify `DiagnosticSpec`'s existing
  substring assertions still pass and add one asserting `@DiffAsValue` appears in each.
- [x] 3.4 Run `./gradlew :kdiff-processor:test` and verify every existing spec passes unchanged.

## 4. Sample and tutorial

- [x] 4.1 Add `val discount: BigDecimal`, `val placedAt: Instant`, a `@JvmInline value class Sku`
  property and a `@DiffAsValue data class Coordinates` property to `kdiff-sample/.../Model.kt`
  (design D8). Run `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and verify the four new lines in
  `OrderDiff.kt` are `compareValue` / `patchValue` pairs and nothing else moved.
- [x] 4.2 Extend `OrderDiffSpec` and `RoundTripSpec` with the new properties, including the
  `BigDecimal` scale case and the `@DiffAsValue` wholesale set, and `HandWrittenParitySpec` with
  `field(Order::location)` against the annotated `@DiffAsValue` property. Verify
  `./gradlew :kdiff-sample:test` passes.
- [x] 4.3 Add a `RecipesSpec` test for the how-to recipe *Compare a type as a single value*, showing a
  class-level and a property-level `@DiffAsValue`. Verify it carries the `from:` marker the docs cite.
- [x] 4.4 Revise the identifier comment in `kdiff-tutorial/.../domain/Person.kt` and the matching
  sentence in `docs/tutorial.md` to the reason that survives this change (design D8). Verify
  `./gradlew :kdiff-tutorial:test` passes unchanged.

## 5. Documentation

- [x] 5.1 Update `docs/errors.md` with the five D5 messages and the two D6 amendments, verbatim, and the
  tier-1 count. Verify each quoted string matches the emitting `KSPLogger.error` call by search.
- [x] 5.2 Update `docs/annotations.md` (the `@DiffAsValue` section, the *What counts as a value* list
  with its criterion and the `BigDecimal` caveat, six rejection-table rows), `docs/diffing.md`
  (*Values, enums and nullables*), `docs/architecture.md` (the rewritten per-property/per-type bullet),
  `docs/faq.md` (the `BigDecimal` answer) and `docs/hand-written.md` (the `field` row), per design
  *Documentation*. Verify every `<!-- from: -->` block still matches its source.
- [x] 5.3 Add the `docs/how-to.md` recipe citing the 4.3 test.
- [x] 5.4 Add the `CHANGELOG.md` *Added* entry under `[Unreleased]` listing the three routes and the
  accepted types, written for someone deciding whether to upgrade.

## 6. Done

- [x] 6.1 Run `./gradlew check` and verify it passes, including ABI validation against the annotations
  dump updated in 1.1 and the unchanged runtime dump.
