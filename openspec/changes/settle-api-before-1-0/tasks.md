## 1. One result type

- [ ] 1.1 Delete `Patched<T>` from `kdiff-runtime/.../Patch.kt` and change every helper — including
  `patchNullable` and `patchSingleton` if `close-generation-gaps` has landed — to return
  `PatchResult<T>`, with `patchNested` and `patchNestedNullable` returning the delegate's result directly
  (design D1). Verify `./gradlew :kdiff-runtime:test` passes with no assertion weakened, and that
  `PatchSpec` gains the *A helper's result can be required outright* scenario.
- [ ] 1.2 Regenerate the sample with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and verify every
  generated file is textually identical to before, proving the processor needed no change.

## 2. `Diff`

- [ ] 2.1 Remove `data` from `Diff` in `Diff.kt`, adding explicit `equals`, `hashCode` and a `toString`
  that returns `renderChanges(changes)` (design D2, D3). Verify `DiffSpec` and `DiffCollectionSpec` pass,
  and add scenarios for equality over equal changes and for `toString` equalling `render()` on a
  populated and an empty diff.
- [ ] 2.2 Add a compile-failure check — a kctfork snippet in `kdiff-processor`'s test source set, which
  already compiles snippets — showing `diff.copy(...)` and `val (changes) = diff` do not compile.
  Verify the snippet fails with the expected unresolved reference.

## 3. Functional interfaces and the map-entry constant

- [ ] 3.1 Mark `Differ<T>` and `Patcher<T>` as `fun interface` (design D4). Verify `DslSpec` gains a
  test declaring `Differ<Money> { a, b -> ... }` and passing it to `nested`, and one declaring a
  `Patcher` lambda; verify `./gradlew checkLegacyAbi` passes with no dump change from this step.
- [ ] 3.2 Add `Segment.Key.MAP_ENTRY` in `FieldPath.kt` and use it in `compareMap` and `patchMap`
  (design D5). Verify a `FieldPathLiftingSpec` or `CompareSpec` test asserts a map change's key segment
  carries `Segment.Key.MAP_ENTRY` and still renders `amounts[key=eur]`.
- [ ] 3.3 Run `./gradlew :kdiff-runtime:updateKotlinAbi` and verify the diff is exactly: `Patched` and
  its members removed; `Diff.component1`, `Diff.copy` and `Diff.copy$default` removed; `Segment$Key$Companion`
  and `MAP_ENTRY` added; the nine helpers' return types now `PatchResult`. Nothing else.

## 4. Consumers

- [ ] 4.1 Run `./gradlew :kdiff-sample:test :kdiff-tutorial:test` and verify both pass; `MoneyDiffer`
  in the sample reads `.value` and compiles unchanged. Fix any test that asserted on `Diff`'s old
  data-class `toString`.

## 5. Documentation

- [ ] 5.1 Write `docs/api-stability.md` with the seven sections of design D6, link it from `README.md`
  *Status* and `docs/README.md`. Verify every claim in it is one the ABI gate or a closed vocabulary
  already enforces, by cross-reading `api/*.api` and `Change`/`PatchFailure.Reason`.
- [ ] 5.2 Update `docs/patching.md` (re-cite the `PatchResult` declaration; no `Patched`),
  `docs/hand-written.md` (lambda form; `MAP_ENTRY`), `docs/diffing.md` (*Viewing a diff*: `toString`
  renders), `docs/errors.md` and `docs/architecture.md` (the bypass sentence names the lambda form),
  per design *Documentation*. Verify every `<!-- from: -->` block still matches its source through
  `DocumentationSamplesSpec`.
- [ ] 5.3 Add the `CHANGELOG.md` entries under `[Unreleased]`: **BREAKING** *Changed* for `Patched`
  and for `Diff.copy`, each with its one-line migration; *Added* for the functional interfaces,
  `MAP_ENTRY` and the stability page.

## 6. Done

- [ ] 6.1 Run `./gradlew check` and verify it passes, including ABI validation against the dump updated
  in 3.3.
