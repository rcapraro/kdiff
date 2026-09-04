## 1. Runtime: the full comparison vocabulary in `differ { }`

- [x] 1.1 Add `list`, `keyedList`, `set` and `map` to `DifferBuilder`, each appending a closure that
  calls the matching runtime helper (`comparePositionalList`, `compareKeyedList`, `compareSet`,
  `compareMap`). Verify: new `DifferBuilderSpec` cases in `kdiff-runtime` assert the changes and paths
  for a keyed move, a positional edit, a set addition and a map entry change.
- [x] 1.2 Collapse `nested` onto `KProperty1<T, V?>` + `compareNestedNullable`, so one overload serves
  the nullable and non-null cases. Verify: existing nested specs still pass, plus a case where the
  nested value is null on one side reporting one `ValueChanged` at the property.
- [x] 1.3 Add `subtype(KClass<S>, Differ<S>)`: when any subtype is declared, `diff` delegates for two
  instances of the same declared subtype and otherwise reports `TypeChanged` at `FieldPath.ROOT`
  followed by the fields declared alongside. Verify: a spec covering both branches, including a sealed
  parent with an own property compared across a swap.
- [x] 1.4 Add a parity spec comparing a hand-written description of a model against the generated
  differ for the annotated same model, asserting equal changes in the same order for one transition
  touching every shape. Verify: `./gradlew :kdiff-runtime:test` (parity spec lives where both differs
  are reachable — `kdiff-sample` if the annotated model must be generated).

## 2. Runtime: scopes and one-shot tracked comparison

- [x] 2.1 Add `excluded: Set<String>` to `TrackScope`, `except(property)` to `TrackScopeBuilder` and
  `TrackerBuilder`, and the exclusion test to `ResolvedScope.selects` (rejected before any depth rule).
  Verify: specs for excluding a property, excluding one that nests, and an exclusion surviving a stated
  depth.
- [x] 2.2 Make mixing `except` with `field`/`under` in one scope a `require` failure, and excluding an
  uncompared property a no-op. Verify: a spec asserting the rejection message and one asserting the
  no-op.
- [x] 2.3 Extend `TrackScopeCompositionSpec` with exclusions: a prepared scope narrowed by `except`, and
  `except` combined with a stated depth, must never report more than the unexcluded case. Verify:
  `./gradlew :kdiff-runtime:test --tests '*TrackScopeCompositionSpec*'`.
- [x] 2.4 Add `Differ<T>.trackedDiff(before, after, scope = null)` resolving through `resolveAgainst`.
  Verify: a spec asserting its result equals a `Tracker`'s report for the same pair, that a declared
  scope applies when none is given, and that it retains no baseline.

## 3. Runtime: routing

- [x] 3.1 Add `Route.kt` with `Diff.route<T>`, `ChangeRoutes<T>` (`on`, `otherwise`, duplicate-property
  `require`) and change partitioning by the root path segment. Verify: specs for a handler firing once
  with its changes, a property with no change not firing, an unnamed property reaching `otherwise`, a
  root change reaching `otherwise`, no `otherwise` ignoring leftovers, and a duplicate property being
  rejected.
- [x] 3.2 Add `ElementRoutes<E, K>` with `added`, `removed`, `moved` and `changed`, reached through the
  two `reified` `onEach` overloads (keyed and unkeyed) and a `@PublishedApi internal` registration hook.
  Verify: specs for typed added/removed elements, a move supplying its key and both positions, and
  `changed` firing once for an element with two changed properties.
- [x] 3.3 Add a spec routing the same transition compared by a generated differ and by a hand-written
  one, asserting the same handlers run with the same values. Verify: `./gradlew :kdiff-runtime:test`.

## 4. Remove field tokens

- [x] 4.1 Delete `kdiff-runtime/Tokens.kt` and `TokensSpec.kt`. Verify: `./gradlew :kdiff-runtime:test`.
- [x] 4.2 Delete `tokenType`, `tokenObject`, `tokenCompanion`, `tokenName` and `fieldTokenName` from the
  processor, the token entries from `Names.kt`, and the token-only plumbing from `Resolution.kt`
  (`elementType()`, `Comparison.KeyedList.keyType`, the `element` field on `KeyedList`,
  `PositionalList`, `AsSet`, `AsMap`, and `KSType.tokenElement()`). Verify: `./gradlew
  :kdiff-processor:test`.
- [x] 4.3 Delete `FieldTokenSpec.kt` and remove the token-collision diagnostic case from
  `DiagnosticSpec.kt`. Verify: `./gradlew :kdiff-processor:test`.
- [x] 4.4 Regenerate `kdiff-sample` and confirm the generated file is the differ object alone.
  Verify: `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` then read
  `kdiff-sample/build/generated/ksp/main/kotlin/demo/<Type>Diff.kt`, and `./gradlew :kdiff-sample:test`.

## 5. Rebuild the tutorial

- [x] 5.1 Replace `tutorial/domain` with plain data classes carrying no kdiff import: `Person` (no
  `PersonState`), `Address`, `Employment` and its subclasses, `FullName`, the identifiers, and `Money`
  under `tutorial/money`. Verify: `./gradlew :kdiff-tutorial:compileKotlin` with no kdiff import in the
  package.
- [x] 5.2 Add `tutorial/diff/PersonDiffing.kt` holding `PersonDiffer` (`differ { }`, covering the value
  object, the keyed list, the sealed type, the foreign type and the set) and
  `PersonScope` (`trackScope { except(Person::lastSeenAt) }`). Verify: a spec asserting the diff of a
  transition touching each shape.
- [x] 5.3 Delete `Person.kt`'s mutable aggregate, `PersonState.kt`, `PersonDto.projectOnto` and
  `ScopeVariants.kt`; rewrite `UpdatePersonHandler` as load → `applyTo` → `trackedDiff` → `route` →
  save, with the audit trail fed by `otherwise`. Verify: `PersonCommandSpec` rewritten and green.
- [x] 5.4 Trim `Money` to its `differ { }` description, dropping the hand-written `Patcher` the tutorial
  never exercises. Verify: `./gradlew :kdiff-tutorial:test`.
- [x] 5.5 Add `tutorial/annotated/` with the same model annotated in one file, plus `AnnotatedParitySpec`
  asserting both routes report equal changes for the same transition. Verify:
  `./gradlew :kdiff-tutorial:test --tests '*AnnotatedParitySpec*'`.
- [x] 5.6 Update `Main.kt` and confirm the printed run still shows a rename, an add, a reorder, an edit,
  an employment change and an untracked touch producing nothing. Verify: `./gradlew :kdiff-tutorial:run`
  and paste the output into the tutorial doc.

## 6. Documentation

- [x] 6.1 Rewrite `docs/tutorial.md` against the new module: annotation-free domain first, the annotated
  mirror second, routing in place of the token `when`, and the depth-trap section reduced to the
  paragraph the annotated route still needs. Verify: every `<!-- from: -->` snippet matches the file it
  cites.
- [x] 6.2 Update `docs/hand-written.md` with the full builder vocabulary, the sealed case, and the
  hazard that the builder cannot diagnose a wrong description. Verify: snippets compile as written
  against the sample or tutorial sources they cite.
- [x] 6.3 Update `docs/diffing.md`: remove the token section, document routing in its place. Update
  `docs/tracking.md` for `except` and `trackedDiff`. Verify: no `fieldOf`, `FieldToken`, `ElementField`
  or `KeyedField` reference survives outside the archived change.
- [x] 6.4 Update `README.md` and `CLAUDE.md` where they describe the capability table or the dispatch
  story. Verify: `grep -r "fieldOf\|FieldToken" --include='*.md' .` returns only `openspec/changes/archive`.

## 7. Done

- [x] 7.1 Run `./gradlew check` and report the result verbatim.
