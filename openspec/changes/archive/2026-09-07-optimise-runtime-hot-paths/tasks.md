## 1. Benchmark harness and baseline

The baseline must exist before any optimisation lands, or there is nothing to compare against.

- [x] 1.1 Add a `jmh` plugin entry to `gradle/libs.versions.toml`, pinning the current
      `me.champeau.jmh` release read from the Gradle Plugin Portal rather than from memory (design
      D11, Open Questions); verify `./gradlew help` resolves the plugin with no version warning.
- [x] 1.2 Create the `kdiff-benchmarks` module — `settings.gradle.kts` include, `build.gradle.kts`
      applying the plugin and depending on `kdiff-runtime`, `kdiff-annotations` and the processor via
      `ksp` — and leave it out of `publishedModules` in the root build; verify
      `./gradlew :kdiff-benchmarks:compileJmhKotlin` succeeds and `./gradlew :kdiff-benchmarks:tasks`
      shows no `publish` task.
- [x] 1.3 Write the nine benchmark cases of the design D11 table (compare ×5, apply ×2, track, route),
      over the `kdiff-sample`-shaped model plus one 6-level-deep synthetic model; verify
      `./gradlew :kdiff-benchmarks:jmh` completes and reports a score for every case.
- [x] 1.4 Capture the baseline on unmodified runtime and processor code — throughput and
      `-Pjmh.profilers=gc` allocation rate — and paste both tables into `design.md` under a
      "Baseline" heading; verify the file records a number for all nine cases.
- [x] 1.5 Confirm `./gradlew check` does **not** run `jmh` (design D11); verify by timing
      `./gradlew check` against the pre-change time and by inspecting `./gradlew check --dry-run` for
      the absence of any `jmh` *run* task. Code review then showed the module needed the opposite for
      its *compile*: `check` now depends on `jmhClasses`, so the dry run shows three jmh compile tasks
      and no run task, and a runtime API change can no longer leave the benchmarks broken with CI green.

## 2. Runtime — path construction (design D1, D2)

- [x] 2.1 Rewrite `FieldPath.prefixedWith(segment)` to build one right-sized list, add the `internal`
      two-segment `prefixedWith(outer, inner)`, and add the matching `internal` two-segment lift for
      `Change` over a `withPath` promoted from `Patcher.kt` to `internal` in `Diff.kt`; verify with a
      new Kotest spec asserting single- and two-segment lifts produce paths equal to the composed
      single lifts, for all five `Change` variants.
- [x] 2.2 Change `FieldPath.withoutFirst` and `Change.withoutFirstSegment` to return a `subList` view;
      verify with a Kotest spec that a derived path is `==` to the `drop(1)` result, has the same
      `hashCode` and the same `toString`, and that repeated descent from a 6-segment path yields the
      expected path at each level.
- [x] 2.3 Audit every `FieldPath` construction inside `kdiff-runtime` for a caller-retained mutable
      backing list (design D2, correctness note); verify by search that each site passes a fresh
      `ArrayList`, `emptyList()` or `listOf(...)`, and record the result in the task notes.
- [x] 2.4 Run `./gradlew :kdiff-runtime:test` and confirm every existing spec passes **unchanged** —
      any spec needing adaptation here means a path changed and the work is wrong.

## 3. Runtime — comparison hot path (design D3, D4, D5)

- [x] 3.1 Hoist `Segment.Field(name)` out of the per-change loops in `compareNested`,
      `compareKeyedList` and `comparePositionalList`, and replace `map { }` + `addAll` with direct
      appends; verify `./gradlew :kdiff-runtime:test --tests '*CompareSpec*'` passes unchanged.
- [x] 3.2 Switch the collection helpers' double lift `prefixedWith(element).prefixedWith(field)` to
      the single two-segment lift from 2.1; verify `CompareSpec` and `DiffSpec` pass unchanged and
      that reported paths in `kdiff-sample`'s `OrderDiffSpec` are byte-identical.
- [x] 3.3 Rewrite `compareSet` as two membership walks building at `FieldPath.of(name)` directly;
      verify with a Kotest spec that removals then additions are reported in receiver iteration order,
      matching the current output for the same pair.
- [x] 3.4 Replace `requireUniqueKeys` with `internal fun duplicateKey(name, keyProperty, key): Nothing`,
      moving both message branches across verbatim and still throwing `IllegalArgumentException`;
      verify the existing duplicate-key specs in `kdiff-runtime` and `kdiff-sample` pass with their
      asserted message text unedited.
- [x] 3.5 Rewrite `compareKeyedList` to index by key into `Map<Any?, Int>` without `withIndex()`,
      detecting a repeated key from `put`'s return value during that build (design D5); verify
      `DuplicateKeySpec` passes unchanged, and add specs pinning the two ordering properties — the
      named key is the first element whose key was already seen, and a duplicate in `before` is
      reported even when `after` also has one.
- [x] 3.6 Confirm moves, additions, removals and in-place edits still report identically after 3.5;
      verify `CompareSpec`, `DiffSpec` and `kdiff-sample`'s `OrderDiffSpec` pass unchanged.
- [x] 3.7 Add a Kotest spec for the new requirement *A comparison reads each compared property once
      per instance*: a model whose getters count invocations, covering a plain property, an ignored
      property, a keyed list's key property, and a nested property reporting several changes; verify
      all five scenarios of that requirement.

## 4. Runtime — tracking selection (design D6)

Do 4.1 before 4.2: the oracle must exist and pass against the *current* implementation, or it proves
nothing about the new one.

- [x] 4.1 Add `ResolvedScopeEquivalenceSpec` carrying a copy of today's eight-line selection rule as an
      oracle, enumerating the domain of design D6 exhaustively — scopes over `{a, b}` named zero to
      twice at depths `{1, 2, 3, UNLIMITED_DEPTH}`, both scope kinds including the empty `Named`,
      exclusion sets over `{}`/`{a}`/`{b}`/`{a, b}`, and every path of length 0–3 over `Field(a)`,
      `Field(b)`, `Index(0)`, `Key(k, v)`; verify it passes against the unmodified `ResolvedScope` and
      that a deliberate one-character mutation of the rule makes it fail with the scope and path named.
- [x] 4.2 Add a standalone invariant spec, independent of the oracle, asserting the failure direction
      directly: for any scope naming a set of properties, no change rooted at a property outside that
      set is ever selected; verify it passes against the unmodified implementation too.
- [x] 4.3 Split `ResolvedScope` into a sealed interface with `Everything` and `Named` implementations,
      making `resolveAgainst` the single place that chooses between them; verify 4.1 and 4.2 still pass
      and that no call site outside `Select.kt`, `Track.kt` and their specs needed touching.
- [x] 4.4 Fold `TrackedField` depths into `Named`'s `Map<String, Int>` of widest depth at construction
      and make `selects` an allocation-free lookup; verify 4.1 and 4.2 pass, and that
      `TrackScopeCompositionSpec`, `TrackingSpec`, `TrackedDiffSpec`, `TrackingCoherenceSpec` and
      `SelectSpec` all pass **unchanged** — an adapted test here is evidence the selection moved.
- [x] 4.5 Guard the per-change side computation in `Tracker.update` on `fieldListeners.isNotEmpty()`
      and pass the two sides without a `Pair`, keeping `sides()` an exhaustive `when`; verify with a
      Kotest spec that a tracker with only a batched listener still returns the same `Diff`, and that
      per-field listeners still fire once per selected change in report order.

## 5. Runtime — application fast paths (design D7)

- [x] 5.1 Add `if (changes.isEmpty()) return Patched(source)` to `patchValue`, `patchSet`,
      `patchPositionalList` and `patchMap`; verify with a Kotest spec asserting the returned value is
      the *same instance* as the source for each, per the new `diff-application` scenarios.
- [x] 5.2 Rework `patchKeyedList` to build one `LinkedHashMap`, detecting a repeated key from `put` as
      in 3.5, and place the empty-changes exit immediately after that build — after the check, by
      position rather than by a condition (design D7); verify
      `./gradlew :kdiff-runtime:test --tests '*PatchSpec*'` and the runtime `DuplicateKeySpec` pass
      unchanged, and add the new scenario *A keyed list with a repeated key is rejected even when no
      change addresses it*.
- [x] 5.3 Delete the parallel `order` list from `patchKeyedList`, reading position off `byKey.keys`;
      verify the keyed-list round-trip specs pass unchanged, in particular a case combining a removal,
      an addition and a move, since insertion-order maintenance across `remove` and re-`put` is what
      replaces the two removed linear scans.
- [x] 5.4 Add the remaining new `diff-application` scenarios as Kotest specs — an unaddressed set,
      map and positional list carried through as the source instances, and unaddressed value
      properties — verifying both instance identity and overall result equality.

## 6. Runtime — routing and rendering (design D10)

- [x] 6.1 Rewrite `ChangeRoutes.dispatch` to group changes by `rootName()` once into a
      `LinkedHashMap`, preserving report order within each group; verify every scenario of *A diff can
      be routed to handlers named by property reference* still holds via `RouteSpec` passing
      unchanged.
- [x] 6.2 Replace `it in unroutable` with an identity set; verify with new Kotest specs covering the
      three scenarios of the new requirement *A routing identifies its leftover changes by change
      instance*, including two value-equal changes routed independently and a frame's returned change
      arriving at the enclosing path.
- [x] 6.3 Make `renderChanges` render each path once instead of twice; verify
      `./gradlew :kdiff-runtime:test --tests '*ViewSpec*'` passes unchanged, including column padding.

## 7. Processor — generated `apply` body (design D9)

- [x] 7.1 Emit the compared-property name set as a `private val comparedProperties: Set<String>` on
      the generated object and have `groupByProperty` read it; verify with a kctfork compile-testing
      spec that compiles a type, invokes the generated `apply`, and asserts the generated text
      declares the property once and the `apply` body allocates no `setOf`.
- [x] 7.2 Emit the failure list as one `buildList` of `addAll` calls instead of a `+` chain; verify
      with a kctfork spec that failures appear in the same order as today — unmatched first, then one
      property at a time — including a case with failures from two different properties.
- [x] 7.3 Confirm generated files still declare `Dependencies(aggregating = false, *sources)` with no
      new originating file (design D9, incremental processing); verify by asserting on the generated
      file set for a two-type fixture.
- [x] 7.4 Update the processor specs that snapshot generated text to the new shape, leaving the
      compile-and-invoke specs untouched; verify `./gradlew :kdiff-processor:test` passes.

## 8. Sample, tutorial and documentation

- [x] 8.1 Regenerate the sample's differs with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and
      read `kdiff-sample/build/generated/ksp/main/kotlin/demo/OrderDiff.kt`; verify it matches the
      sample shown in design D9 and that the `diff` body and `trackScope` are unchanged from before.
- [x] 8.2 Run `./gradlew :kdiff-sample:test` and `./gradlew :kdiff-tutorial:test`; verify every spec
      passes unchanged, in particular `HandWrittenParitySpec`, `AnnotatedParitySpec` and
      `RoundTripSpec` — parity between the generated and hand-written routes is the property most at
      risk from an optimisation applied to only one of them.
- [x] 8.3 Check `README.md` and `docs/` for any snippet of generated `apply` output that the D9 shape
      change invalidates, and update it; verify by searching the docs for `groupByProperty` and
      `setOf(` and confirming each hit matches the regenerated output.
- [x] 8.4 Add the `CHANGELOG.md` entry for the next version, written before any tag, describing the
      runtime optimisations, the generated-`apply` shape change and the new benchmark module.

## 9. Measurement and close-out

- [x] 9.1 Re-run `./gradlew :kdiff-benchmarks:jmh` and `-Pjmh.profilers=gc` on the optimised code and
      record both tables in `design.md` beside the 1.4 baseline; verify every one of the nine cases
      has a before and an after number, and report any case that did not improve as not improved.
- [x] 9.2 Read the full diff of the change; verify no unrelated refactoring, that each non-obvious
      construction in `FieldPath` and `Compare.kt` carries a one-line *why* comment, and that no
      public signature was removed or changed.
- [x] 9.3 From the deep-graph benchmark case, state in `design.md` — under D8 — whether the remaining
      lift cost justifies a follow-up `diffInto` proposal; verify a one-paragraph answer with numbers
      backing it is present.
- [x] 9.4 Run `./gradlew check` and confirm it is green.
