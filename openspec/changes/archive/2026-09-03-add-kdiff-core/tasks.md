# Tasks — add-kdiff-core

Ordered so the runtime change model and path prefixing land and are tested before any generation
depends on them (design Risks: this is how a failure localises to one property shape).

## 1. Runtime change model and paths

- [x] 1.1 Add `Segment.Index` and `Segment.Key` alongside `Segment.Field`, and verify `./gradlew :kdiff-runtime:build` succeeds under `explicitApi()`
- [x] 1.2 Add `ValueChanged`, `Added`, `Removed`, `TypeChanged` and `Moved` as `Change` implementations, each carrying the data the spec requires, and verify `:kdiff-runtime:build` succeeds
- [x] 1.3 Make rebuilding a change at a new path part of the `Change` contract per design D2, so a subtype cannot forget it, and verify the compiler rejects a subtype that omits it
- [x] 1.4 Add path prefixing to `kdiff-runtime` and a Kotest spec covering the spec's "A change identifies where it was found" requirement — `address.street`, `tags[2]`, `addresses[id=A2]` — and verify `:kdiff-runtime:test` passes
- [x] 1.5 Add a Kotest spec proving prefixing survives three levels of nesting (`company.address.city`) for every `Change` subtype, and verify `:kdiff-runtime:test` passes

## 2. Runtime comparison helpers

- [x] 2.1 Add the value and nullable comparison helpers per design D1/D3, including the null-on-either-side short circuit, and add a Kotest spec covering the spec's nullable requirement (null→value, value→null, null→null), verifying `:kdiff-runtime:test` passes
- [x] 2.2 Add the nested-delegation helper (non-null and nullable overloads) that prefixes the nested differ's changes, with a Kotest spec, and verify `:kdiff-runtime:test` passes
- [x] 2.3 Add keyed list comparison — match by key, emit added/removed/moved plus element changes — with a Kotest spec covering every scenario in the spec's keyed-list requirement including the move-not-remove-and-add case, and verify `:kdiff-runtime:test` passes
- [x] 2.4 Add positional list comparison with a Kotest spec covering changed element, trailing additions, trailing removals, and that a move is never reported, and verify `:kdiff-runtime:test` passes
- [x] 2.5 Add set comparison (membership only, never move, never element modification) with a Kotest spec, and verify `:kdiff-runtime:test` passes
- [x] 2.6 Add map comparison by entry key, recursing into the value type, with a Kotest spec, and verify `:kdiff-runtime:test` passes

## 3. Runtime views and the DSL

- [x] 3.1 Add `Diff.tree()` grouping changes by common path prefix, with a Kotest spec asserting the spec's round-trip invariant — collecting every change from the tree yields exactly the flat list — plus the empty case, and verify `:kdiff-runtime:test` passes
- [x] 3.2 Add `Diff.render()` writing one line per change with path, kind and values, with a Kotest spec asserting all five kinds are distinguishable and that an empty diff asserts no change, and verify `:kdiff-runtime:test` passes
- [x] 3.3 Add the `differ<T> { field(T::x) }` DSL building a `Differ<T>` from `KProperty1` references with no `kotlin-reflect` dependency, with a Kotest spec, and verify `:kdiff-runtime:test` passes and `./gradlew :kdiff-runtime:dependencies` still shows only the Kotlin stdlib

## 4. Annotations

- [x] 4.1 Add `@DiffKey`, `@DiffIgnore` and `@DiffWith` to `kdiff-annotations`, KDoc'd, targeting properties, and verify `:kdiff-annotations:dependencies` still shows nothing beyond the Kotlin stdlib
- [x] 4.2 Widen `@Diffable`'s KDoc to state that it is accepted on data classes and on sealed types whose subclasses are all `@Diffable`, and verify `:kdiff-annotations:build` succeeds

## 5. Generation: properties

- [x] 5.1 Resolve each property's `KSType` exactly once and route it through design D3's ordered table, and verify with a kctfork spec that a data class of scalars generates a differ reporting one value change per differing property in declaration order
- [x] 5.2 Emit nothing for a `@DiffIgnore` property, with a kctfork spec covering both spec scenarios (ignored-only differs → no changes; ignored plus compared → exactly one change), and verify `:kdiff-processor:test` passes
- [x] 5.3 Generate enum comparison via the value path with a kctfork spec, and verify `:kdiff-processor:test` passes
- [x] 5.4 Generate the nullability guard on top of whichever row matched, with a kctfork spec covering a nullable scalar and a nullable nested property (including that no changes appear beneath a nulled nested property), and verify `:kdiff-processor:test` passes
- [x] 5.5 Generate nested `@Diffable` delegation with path prefixing, with a kctfork spec asserting `address.street` and a three-level `company.address.city`, and verify `:kdiff-processor:test` passes

## 6. Generation: collections and sealed types

- [x] 6.1 Generate keyed list comparison, emitting the element type's key extractor, with a kctfork spec covering modified, added, removed, moved and unchanged, and verify `:kdiff-processor:test` passes
- [x] 6.2 Generate positional list comparison for an element type with no key, with a kctfork spec, and verify `:kdiff-processor:test` passes
- [x] 6.3 Generate set and map comparison, with kctfork specs covering the spec's scenarios for each, and verify `:kdiff-processor:test` passes
- [x] 6.4 Accept `@Diffable` on a sealed class or interface and generate the dispatching `when` per design D4 — delegate on matching subclass; on a swap emit the type change then compare only the sealed parent's own declared properties — and verify with a kctfork spec covering all four sealed scenarios in the spec plus the marker-interface case where the parent declares nothing
- [x] 6.5 Generate delegation for a sealed-typed property so the type change appears at that property's path, with a kctfork spec, and verify `:kdiff-processor:test` passes

## 7. Generation: the escape hatch

- [x] 7.1 Resolve `@DiffWith`, verifying the named class is an `object` implementing `Differ<P>` for the property's type, and generate a reference to it, with a kctfork spec asserting a property delegates to a hand-written differ at path `total.amount`
- [x] 7.2 Add a kctfork spec proving a hand-written differ nests inside two levels of generated delegation and reports at the full path, and verify `:kdiff-processor:test` passes

## 8. Diagnostics

- [x] 8.1 Reject a property whose type resolves to nothing and carries no `@DiffWith`, with an error naming the property and its type and mentioning the escape hatch, and a kctfork spec asserting the message, the location, and that adding `@DiffWith` makes the same source compile
- [x] 8.2 Reject a generic annotated class with an error naming the class, with a kctfork spec asserting message and location
- [x] 8.3 Reject a sealed type with an unannotated subclass, with an error naming both, and a kctfork spec
- [x] 8.4 Reject a type declaring more than one `@DiffKey`, with an error naming the type and both properties, and a kctfork spec
- [x] 8.5 Reject a `@DiffWith` naming something that is not an `object` implementing `Differ<P>`, with a kctfork spec asserting the error is reported at the property

## 9. Incremental processing

- [x] 9.1 Collect originating files per design D6 — the annotated type's file plus the containing file of every nested `@Diffable` type, every sealed subclass, and every `@DiffWith` target — into one `Dependencies(aggregating = false, *files)`
- [x] 9.2 Verify staleness is impossible: build `kdiff-sample` with `-Pksp.incremental.log=true` and confirm `build/kspCaches/main/logs/kspSourceToOutputs.log` lists the nested type's file as an input of the outer differ's output (`Address.kt -> OrderDiff.kt`). Timestamps cannot be used: at KSP 2.3.11 the Gradle worker wipes the output directory every run, so regeneration is guaranteed and proves nothing — see design D6
- [x] 9.3 Verify `aggregating = false` still holds with nesting in play: add an unrelated `@Diffable` class, rebuild with the same flag, and confirm the source-to-outputs map lists that file against its own generated output only, never against an existing one

## 10. Sample and verification

- [x] 10.1 Replace the sample's baseline test — the one asserting a diff is always empty — with tests asserting real changes, since that behaviour is retired by this change
- [x] 10.2 Add sample models covering each property shape (scalars, enum, nullable, nested, keyed list, unkeyed list, set, map, sealed hierarchy, a `@DiffWith` property) and a Kotest spec diffing each, and verify `:kdiff-sample:test` passes
- [x] 10.3 Add a sample test rendering a diff that contains all five change kinds and asserting the output reads correctly, as the human check on `render()`
- [x] 10.4 Review a generated file for a class using several shapes and confirm it is still one call per property with no inline loops (design D1) and that a human would be happy to read it
- [x] 10.5 Confirm `:kdiff-sample:dependencies --configuration runtimeClasspath` still contains neither the processor, `symbol-processing-api` nor KotlinPoet
- [x] 10.6 Run `./gradlew clean check` and confirm it passes; report any failure verbatim
