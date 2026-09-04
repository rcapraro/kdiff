## 1. Confirm the runtime already matches the delta

- [x] 1.1 Read `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Track.kt` (`TrackerBuilder.build`)
  and `TrackScope.atDepth`, and confirm the three composition rules hold as written: a named property
  replaces a prepared scope, a stated depth is applied to the prepared scope's properties, and neither
  path falls back to the declared scope or to tracking everything — verify no production change is
  needed, and say so explicitly rather than leaving it implied
- [x] 1.2 Read `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Select.kt` (`ResolvedScope.selects`)
  and confirm a property named more than once resolves to the widest depth, unlimited beating any
  bounded depth — verify no production change is needed
- [x] 1.3 Confirm `TrackScope.trackedFields` distinguishes "named none, wants all" (null) from "none to
  name" (empty list), and that `Dsl.kt`'s `TrackScopeBuilder.build` and `TrackScope.kt`'s
  `trackScopeOf` produce those two respectively — verify no production change is needed

## 2. Map every delta scenario to a test

- [x] 2.1 Map each of the 5 scenarios of "A prepared scope, a named property and a stated depth compose
  by one rule" to a test in `kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/TrackScopeCompositionSpec.kt`
  — verify each has a covering test and add one for any that does not
- [x] 2.2 Map both scenarios of "A property named more than once is tracked at the widest depth named"
  to tests in the same spec — verify each has a covering test
- [x] 2.3 Map all 4 scenarios of the modified "A tracking scope with no selectors tracks the whole
  object" to tests — verify the two empty-scope kinds and the root-change case are each covered
- [x] 2.4 Add a test for "The two kinds of empty scope are distinguishable", which is currently only an
  incidental assertion inside two other tests rather than a test of its own — verify
  `./gradlew :kdiff-runtime:test` passes
- [x] 2.5 Rename the test currently called "a differ declaring no scope of its own does not widen
  either": it builds a differ that *does* declare a scope (`status`), so the name states the opposite
  of what it exercises — verify the renamed test still passes and its name matches the scenario it
  covers

## 3. Verification

- [x] 3.1 Confirm no file under any `src/main` changed in this change — verify by listing the files
  touched and checking they are all specs or tests
- [x] 3.2 Run `openspec validate spec-track-scope-composition --strict` and report the result
- [x] 3.3 Run `./gradlew check` and report the result verbatim
