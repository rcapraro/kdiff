## 1. Runtime — nullable collections

- [x] 1.1 Widen `compareKeyedList`, `comparePositionalList`, `compareSet` and `compareMap` in
  `kdiff-runtime/.../Compare.kt` to take nullable `before`/`after`, opening each with the three-way
  null test from design D1 (`before !== after` → one `ValueChanged` at the property; both null →
  nothing; both present → unchanged body). Verify `./gradlew :kdiff-runtime:compileKotlin` succeeds and
  `./gradlew checkLegacyAbi` passes without `updateKotlinAbi`, proving the JVM signatures are unchanged.
- [x] 1.2 Add `patchNullable(source, changes, patch)` to `Patch.kt` per design D2, and reimplement
  `patchNestedNullable` over it without changing its signature. Verify the existing `PatchSpec` and
  `PatchFailureCaseSpec` pass unchanged, so the nested rule has one body and the same behaviour.
- [x] 1.3 Widen `DifferBuilder.list`, `keyedList`, `set` and `map` in `Dsl.kt` to accept a nullable
  property (design D7), and update each KDoc with the one sentence `nested` already carries about a
  null side. Verify `DslSpec` compiles and passes with no call site edited.
- [x] 1.4 Add a `NullableCollectionSpec` in `kdiff-runtime` covering every scenario of the ADDED
  requirement *A nullable collection property reports a null on either side as a value change* for all
  four shapes, both null directions and both-null, plus the round-trips and the `NothingBeneathNull`
  failure from `specs/diff-application/spec.md`. Verify each reports exactly the changes named, and that
  a hand-written `differ { list(Order::tags) }` over a `List<String>?` property reports the same as the
  generated differ in 4.3.

## 2. Runtime — singletons

- [x] 2.1 Add `patchSingleton(before, changes, type): PatchResult<T>` to `Patch.kt` per design D6,
  returning `before` with every change reported as `UnknownProperty(type)`. Verify a unit test in
  `PatchFailureCaseSpec` shows the empty list returning a clean result and a foreign change reported
  with the reason naming the type.
- [x] 2.2 Run `./gradlew :kdiff-runtime:updateKotlinAbi` and verify the ABI diff is exactly two new
  entries, `patchNullable` and `patchSingleton`, and nothing else moved.

## 3. Processor

- [x] 3.1 In `DiffProcessor.emitPatch`, wrap the four collection cases in `patchNullable` when
  `property.type.resolve().isMarkedNullable` (design D3); leave `emit` unchanged, since the widened
  helpers accept either. Verify with a kctfork spec that a `List<String>?`, `Set<String>?`,
  `Map<String, String>?` and keyed `List<Address>?` property each compile and round-trip through all
  four null transitions.
- [x] 3.2 In `resolveList` and `resolveMap`, report the design D4 diagnostic when the element or value
  type is a nullable `@Diffable` type; leave `List<String?>`, `Map<K, String?>` and every `Set` alone.
  Verify with a kctfork spec that `List<Address?>` and `Map<String, Address?>` fail at the property with
  the verbatim message, and that `List<String?>` and `Set<Address?>` compile.
- [x] 3.3 Extend `reportTrackingWithoutDiffable` into `reportAnnotationsWithoutDiffable` covering
  `DIFF_KEY`, `DIFF_IGNORE` and `DIFF_WITH` on properties of a class that is not `@Diffable`, and report
  the `@DiffWith` + `@DiffIgnore` conflict from `resolve()` (design D5). Verify with kctfork specs for
  each of the six scenarios of *A comparison annotation with nothing to configure is a compile error*,
  including that `@DiffKey @DiffIgnore` compiles and still keys the list.
- [x] 3.4 In `sealedBody` and `sealedApplyBody`, partition `getSealedSubclasses()` by `ClassKind.OBJECT`:
  exempt objects from the `@Diffable` requirement, emit `before is S && after is S -> Unit` and
  `is S -> patchSingleton(before, changes, "S")` for each, and add the object's containing file to the
  originating set (design D6). Extend the object-kind message in `isSupported()` with the sealed-hierarchy
  hint. Verify with kctfork specs for every scenario added to *An annotated sealed type dispatches on the
  runtime subclass*, *A sealed type with an unannotated subclass is a compile error* and the amended
  object scenario, compiling and invoking the generated code rather than asserting on its text.
- [x] 3.5 Run `./gradlew :kdiff-processor:test` and verify every existing spec passes unchanged: no
  existing snapshot moves, because no class that compiled before generates differently.

## 4. Sample and tutorial

- [x] 4.1 Add `val couponCodes: List<String>?` to `Order` and `data object Unpaid : Payment` with
  `override val amount: String get() = "0"` to `kdiff-sample/.../Model.kt` (design D8). Run
  `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and read the generated `OrderDiff.kt` and
  `PaymentDiff.kt`; verify they match the shapes in design D8 and that every other generated file is
  byte-identical to before.
- [x] 4.2 Extend `OrderDiffSpec` and `RoundTripSpec` with the nullable-collection transitions and the
  swaps to and from `Unpaid`, and `HandWrittenParitySpec` with a `list(Order::couponCodes)` clause and
  an undeclared `Unpaid` in the hand-written `Payment` differ. Verify `./gradlew :kdiff-sample:test`
  passes and the parity spec holds both routes to identical output.
- [x] 4.3 Add a `RecipesSpec` test backing a new how-to recipe *Model a state with a payload-free
  case*, so `docs/how-to.md` can cite it. Verify the recipe's code block carries the `from:` comment
  pointing at the test.
- [x] 4.4 Run `./gradlew :kdiff-tutorial:test` and verify it passes unchanged; the tutorial has neither
  shape and is not edited.

## 5. Documentation

- [x] 5.1 Update `docs/errors.md`: add the D4 and D5 messages verbatim under *1. Compile time*, amend
  the object message, and fix the tier-1 count. Verify each quoted string is identical to the
  `KSPLogger.error` call that emits it, by searching the processor source for each.
- [x] 5.2 Update `docs/diffing.md` (nullable collections under *Values, enums and nullables*; `object`
  subclasses under *Sealed types*), `docs/annotations.md` (rejection table rows; `@Diffable` section),
  `docs/hand-written.md` (builder table clause), `docs/patching.md` (wholesale set at a nullable
  collection) and `docs/faq.md` (the `data object` question), per design *Documentation*. Verify every
  `<!-- from: -->` block still matches its source.
- [x] 5.3 Add the `docs/how-to.md` recipe from 4.3 and verify it cites the `RecipesSpec` test.
- [x] 5.4 Add the `CHANGELOG.md` entries under `[Unreleased]`: *Added* for nullable collections and
  `object` subclasses, and **BREAKING** *Changed* for the comparison-annotation diagnostics with the
  one-line migration ("add `@Diffable` to the class, or remove the annotation"). Verify the entry
  reads for someone deciding whether to upgrade.

## 6. Keeping the processor under the size gate

- [x] 6.1 Added during implementation (design D9): the new diagnostics pushed `DiffProcessor` past
  detekt's `LargeClass` threshold, which it had been sitting just under. Move `emit`, `emitPatch`,
  `patchCall` and `trackScopeInitializer` to a new `Emitters.kt`, and `rebuildsACollection`,
  `propertiesOutsideDiffable` and `ownerName` to `Resolution.kt` — all pure, none reaching the logger
  or the code generator. Verify `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` produces
  byte-identical generated output, so the split is a move and not a change.

## 7. Closing the review findings

- [x] 7.1 `patchNullable` short-circuited ahead of the shape's helper, so a nullable keyed list never
  reached `patchKeyedList`'s duplicate-key precondition and stopped refusing what its non-null twin
  refuses. Delegate whenever the source is present — for an empty change list and for a wholesale
  replacement alike — since delegating is the only way to reach the precondition. Shapes with no
  precondition return at once on an empty list, so they pay nothing. Verified by a spec asserting the
  non-null and nullable calls raise identically, and end to end through generated code.
- [x] 7.2 `compareKeyedList`'s null guard returned before `indexByKey`, so comparing null against a
  list holding a repeated key reported a transition instead of refusing — handing back a change carrying
  a list the next comparison of it rejects. Index whichever side is present before reporting the
  transition. Verified in both directions, with the sound and both-null cases still reporting as before.
- [x] 7.3 `patchNullable` discarded every other change addressed to a property a value change replaced
  wholesale, with no failure reported, against *A change that cannot be applied is reported, never
  silently dropped*. Report them as `NotApplicableToValue`, which is what `patchValue` gives its own
  leftovers. Only a `ValueChanged` at the property is treated as wholesale, so a set's element changes
  and a sealed nested value's subclass swap still reach the shape's helper — pinned by a scenario each.
- [x] 7.4 Amend both ADDED spec requirements with the precondition and leftover rules and a scenario
  per case, and update `docs/diffing.md`, `docs/patching.md`, `docs/errors.md` and `CHANGELOG.md` —
  including that the leftover reporting is a behaviour change for nullable nested properties.
- [x] 7.5 Run `./gradlew check` and verify it passes.

## 8. Done

- [x] 8.1 Run `./gradlew check` and verify it passes, including ktlint, detekt, `allWarningsAsErrors`
  and ABI validation against the dump updated in 2.2.
