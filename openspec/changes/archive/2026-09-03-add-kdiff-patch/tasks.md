# Tasks — add-kdiff-patch

The two breaking model changes land first: they break existing tests the moment they land, and
doing them first keeps that breakage in one place rather than spread across the change. Runtime
patching is next, then generation, then the round-trip suite that is this change's definition of
done.

## 1. Model changes (breaking, land together)

- [x] 1.1 Add before and after values to `TypeChanged` per design D2, and verify `:kdiff-runtime:build` succeeds under `explicitApi()`
- [x] 1.2 Change `Segment.Key` to retain the key value rather than its string form, keeping `FieldPath.toString()` rendering identical, and verify the existing path-rendering specs still pass unchanged
- [x] 1.3 Add a Kotest spec for the spec's "A non-string key is retained as its own value" scenario — an integer key survives as an integer and still renders as `[key=1]` — and verify `:kdiff-runtime:test` passes
- [x] 1.4 Update the runtime specs that construct `TypeChanged` and `Segment.Key` directly, and verify `:kdiff-runtime:test` passes
- [x] 1.5 Update the processor's sealed generation to pass both values into `TypeChanged`, add a kctfork spec for the spec's "A subclass swap carries both values" scenario, and verify `:kdiff-processor:test` passes
- [x] 1.6 Update `compareKeyedList` and `compareMap` to build key segments from the key itself, and verify every existing keyed-list and map spec still passes with unchanged rendered paths

## 2. Runtime patching contract

- [x] 2.1 Add `Patcher<T>`, `PatchResult<T>` and `PatchFailure` to `kdiff-runtime` per design D1, with KDoc, and verify `:kdiff-runtime:build` succeeds
- [x] 2.2 Add the change-grouping helper of design D4 — group changes by first path segment, returning both the grouped changes and those matching no known property — with a Kotest spec, and verify `:kdiff-runtime:test` passes
- [x] 2.3 Add failure construction with reasons for the unknown-path, unpatchable-`@DiffWith` and non-constructor-property cases, with a Kotest spec asserting each reason names what is at fault, and verify `:kdiff-runtime:test` passes

## 3. Runtime patching helpers

- [x] 3.1 Add value patching (taking a change's after value, including `null`) with a Kotest spec covering the spec's "A value change sets the property" scenario, and verify `:kdiff-runtime:test` passes
- [x] 3.2 Add nested patching, non-null and nullable, delegating to a `Patcher` and stripping the consumed path segment, with a Kotest spec asserting a change at `company.address.city` applies at that location, and verify `:kdiff-runtime:test` passes
- [x] 3.3 Add keyed list rebuilding per design D5 — drop removals, patch element changes recursively, add additions, then order by target index — with a Kotest spec covering a modification, an addition, a removal and a move in one list, and verify `:kdiff-runtime:test` passes
- [x] 3.4 Add positional list rebuilding (D5 without ordering) with a Kotest spec covering a changed element and both length directions, and verify `:kdiff-runtime:test` passes
- [x] 3.5 Add set rebuilding — drop removals, add additions, never reorder — with a Kotest spec, and verify `:kdiff-runtime:test` passes
- [x] 3.6 Add map rebuilding, taking entry keys from the key segment per design D2, with a Kotest spec covering a changed, an added and a removed entry, including a map whose keys are not strings, and verify `:kdiff-runtime:test` passes

## 4. Generation

- [x] 4.1 Generate `apply` into the existing `<Type>Diff.kt`, with the generated object gaining `Patcher<T>` alongside `Differ<T>` and keeping its name, and verify a kctfork spec that an annotated data class round-trips a single value change
- [x] 4.2 Emit the set of compared property names so an unknown path becomes a reported failure rather than a silent no-op (design D4), with a kctfork spec asserting the failure and its reason
- [x] 4.3 Generate reconstruction for each shape in design D7's table — value, nullable, nested, keyed list, positional list, set, map — routed through the same resolution the comparison uses, with a kctfork spec per shape
- [x] 4.4 Generate sealed reconstruction: delegate when both sides are the same subclass, substitute the type change's after value on a swap, with a kctfork spec covering both
- [x] 4.5 Resolve whether a `@DiffWith` target also implements `Patcher`; delegate to it when it does, emit a reported failure when it does not, with kctfork specs for both the spec's `@DiffWith` scenarios
- [x] 4.6 Emit a reported failure for a change targeting a property declared in the class body rather than the constructor, with a kctfork spec asserting the reason states why
- [x] 4.7 Verify the generated file still reads as one runtime call per property with no inline loops (design D3), by reviewing a generated file for a class using several shapes

## 5. Round-trip suite

- [x] 5.1 Add a round-trip helper to `kdiff-sample`'s tests asserting `apply(before, diff(before, after))` equals `after` and reports no failures, so each shape below is one line
- [x] 5.2 Round-trip scalars, enums and nullable properties in both null directions, and verify `:kdiff-sample:test` passes
- [x] 5.3 Round-trip nested annotated types, including three levels deep, and verify `:kdiff-sample:test` passes
- [x] 5.4 Round-trip a keyed list whose target differs by a modification, an addition, a removal and a move at once, asserting element order matches exactly, and verify `:kdiff-sample:test` passes
- [x] 5.5 Round-trip an unkeyed list that changed length in both directions, a set, and a map with a changed, an added and a removed entry including non-string keys, and verify `:kdiff-sample:test` passes
- [x] 5.6 Round-trip a sealed subclass change and a same-subclass change, asserting the result holds the target's subclass, and verify `:kdiff-sample:test` passes
- [x] 5.7 Round-trip a `@DiffWith` property whose object also patches, and assert the compare-only case reports a failure while the rest of the instance still patches, and verify `:kdiff-sample:test` passes
- [x] 5.8 Assert an ignored property keeps the source value after a round-trip and reports no failures, per the spec's scenario
- [x] 5.9 Assert applying does not modify its source, is repeatable, and that an empty change list returns an equal instance

## 6. Verification

- [x] 6.1 Confirm the KSP source-to-outputs map is unchanged from the core change by building `kdiff-sample` with `-Pksp.incremental.log=true` and reading `build/kspCaches/main/logs/kspSourceToOutputs.log` — generating `apply` must read no file that was not already an input (design D8). Timestamps are not usable here; the Gradle worker rewrites all output every run
- [x] 6.2 Confirm `:kdiff-sample:dependencies --configuration runtimeClasspath` still contains neither the processor, `symbol-processing-api` nor KotlinPoet
- [x] 6.3 Run `./gradlew clean check` and confirm it passes; report any failure verbatim
