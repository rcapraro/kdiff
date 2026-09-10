## 1. The processor's recorded surface

- [x] 1.1 Make `DiffProcessor` `internal` in `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/DiffProcessor.kt`
  (design D2). Verify `./gradlew :kdiff-processor:test` passes — the tests reach the processor through
  kctfork and the service registration, not by naming the class — and that
  `DiffProcessorProvider.create` still returns it as a `SymbolProcessor`.
- [x] 1.2 Run `./gradlew :kdiff-processor:updateKotlinAbi` and verify the diff to
  `kdiff-processor/api/kdiff-processor.api` is exactly the removal of `DiffProcessor` and its four
  members, leaving `DiffProcessorProvider` alone in the file. Nothing else.

## 2. The reason vocabulary opens

- [x] 2.1 Add to `PatchFailure.Reason` in `kdiff-runtime/.../Patcher.kt` the KDoc clause that a further
  case may be declared in a minor version, that a branch on reasons is written with a catch-all, and
  that `PatchFailure`'s rendering stays total (design D3). No declaration is added, removed or altered;
  verify `./gradlew :kdiff-runtime:checkLegacyAbi` passes with no dump change.
- [x] 2.2 Extend `PatchFailureCaseSpec` with the two scenarios the delta spec adds: *a reason no caller
  recognises still reaches that caller* — a `when` over the reasons a caller knows plus an `else`,
  shown reaching the `else` for every case that caller does not name — and *a failure still renders as
  a readable line*, asserted over every case declared today rather than over a sample.

  **Amended during implementation.** This task first asked that a case declared later, without a
  rendering, *fail this test*. It cannot: enumerating the cases needs `sealedSubclasses` and so
  `kotlin-reflect`, the one dependency `kdiff-runtime` refuses, so the table is a literal and a
  fifteenth case leaves it at fourteen and the test green. What does catch such a case is the
  exhaustive `when` in `describe()` failing to compile until the new case has a rendering — a
  mechanical guarantee, just not a test-shaped one. The spec's scenarios are met for every case that
  exists; the comment in the spec now says which of the two mechanisms is doing the work, where an
  earlier version of it claimed the count was.
- [x] 2.3 Add a compile-failure check for *nothing outside the library declares a reason*: a kctfork
  snippet in `kdiff-processor`'s test source set declaring `object Mine : PatchFailure.Reason` and
  failing with the sealed-inheritance error. Verify the expected message appears.

## 3. The capability interfaces may grow

- [x] 3.1 Add to `Differ`, `Patcher` and `Tracked` the KDoc clause that a member carrying its own
  implementation may be added in a minor version, and that a member without one is breaking
  (design D5). KDoc only; verify the dump does not move.
- [x] 3.2 Add to `DslSpec` a test pinning the language behaviour the promise relies on: a `fun
  interface` carrying a non-abstract member, shown still converting from a lambda. That is the half of
  *a lambda implementation survives the addition* that can be checked without actually adding a member
  to `Differ`.

  **Amended during implementation.** This task first called it "the test the promise rests on", which
  overstates it two ways. The interface is declared in the test file and has no relationship to
  `Differ`, so the test cannot fail for any change to kdiff — it asserts a property of Kotlin. And it
  does not cover the case the promise actually guards against: someone adding an *abstract* member to a
  capability interface, which would break every hand-written implementation and every lambda, and which
  this test would stay green through. That case is held by the clause in `Differ`'s KDoc and by review,
  not by a gate.

## 4. `docs/api-stability.md`

- [x] 4.1 §1: state that the dump records the public API and, unavoidably, three kinds of entry that
  are not part of it — declarations published so an `inline` function can reach them, the accessors a
  `value class` lowers to, and a code-generation entry point — with the concrete examples from
  design D1. Say that their removal still fails the build, which is what the dump is for.
- [x] 4.2 §2: split the two vocabularies. `Change` keeps the closed terms. `PatchFailure.Reason` stays
  sealed but may gain a case in a minor, with the dispatch-versus-report reasoning from D3 and the
  instruction to branch with an `else`.
- [x] 4.3 §4 and §5: state that the runtime helpers are contract, and why — `Patcher` has no builder, so
  the helpers are the hand-written route, not merely what generated code calls (design D4). State that
  they evolve by addition, citing 0.6.0's widening of four compare helpers as the shape of that.
- [x] 4.4 §4: add the additive axis — a capability interface may gain a defaulted member, and a
  generated object may declare a further capability interface, with what a consumer written before
  either still does.
- [x] 4.5 §6: reframe as *kdiff targets JVM 21*, with the two consequences that follow (design D8), and
  name the `inline` declarations in the runtime — `route`, `at`, `under`, `onEach` — so a consumer who
  writes no hand-written differ sees why the constraint reaches them.
- [x] 4.6 New section: Kotlin and KSP compatibility (design D7) — the pair kdiff is built against, the
  metadata floor on the runtime and annotations, the KSP plugin the processor expects, and the policy
  that a Kotlin minor is tracked by a kdiff minor.
- [x] 4.7 §7 *Decided, and not done*: add the experimental tier, declined, with D6's reasoning and its
  stated cost — an addition in `1.x` is permanent from the release that makes it.
- [x] 4.8 Re-read the page end to end for the tense it is written in: it says what `1.0.0` *will*
  promise. Decide once whether this change also moves it to what `1.x` *does* promise, and apply that
  decision to every section rather than to the ones edited above.

## 5. The rest of the documentation

- [x] 5.1 `README.md`: Install — reword the requirement per D8. Status — replace *"No compatibility
  guarantee is offered before `1.0.0`"* with what `1.x` promises and what it may add, linking
  `api-stability.md` for the whole of it.
- [x] 5.2 `docs/faq.md`: reword *Which platforms are supported?* per D8, and add *What may change in a
  kdiff minor?* answering with the four additive directions and pointing at `api-stability.md`.
- [x] 5.3 `docs/patching.md` and `docs/errors.md`: show a branch on `PatchFailure.Reason` written with
  an `else`, and say that rendering a failure needs no branch at all. `errors.md`'s fourteen-case table
  gains a line saying the set may grow.
- [x] 5.4 `docs/architecture.md` and `CONTRIBUTING.md`: follow §6 and the additive axis; `CLAUDE.md`'s
  *Three capabilities on one generated object* section gains the sentence that the axis is now a
  promise, not only a design principle.
- [x] 5.5 Verify `./gradlew :kdiff-sample:test` passes, which is what checks the edited pages: every
  `from:`-marked block still matches its source, no block is left unmarked, and every coordinate still
  names the published group and version.

## 6. Done

- [x] 6.1 Run `./gradlew check` and verify it passes. Report any failure verbatim.
