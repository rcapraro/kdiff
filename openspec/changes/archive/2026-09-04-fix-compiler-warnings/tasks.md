## 1. Establish the baseline

- [x] 1.1 Capture every warning `./gradlew clean check` currently emits, so the fix can be measured
  against a known list rather than an impression — verify the list contains exactly the unchecked cast
  in `Select.kt` and one redundant-`else` warning per `@Diffable` sealed type, and report it

## 2. The unchecked cast

- [x] 2.1 Add `@Suppress("UNCHECKED_CAST")` to `resolveAgainst` in
  `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Select.kt`, with a one-line note on why the
  cast is safe — verify `./gradlew :kdiff-runtime:compileKotlin` emits no warning for that file and
  `./gradlew :kdiff-runtime:test` still passes

- [x] 2.2 Remove the `kotlin-reflect` dependency the baseline exposed in
  `kdiff-tutorial/src/test/kotlin/tutorial/PersonModelSpec.kt`: `it::class.isData` needs
  `kotlin-reflect`, which is deliberately absent, and the assertion proves nothing about kdiff — the
  compile-time fact that a sealed member cannot be an object is already covered by `DiagnosticSpec`.
  Replace it with an assertion that exercises the library instead — verify the warning is gone and
  `./gradlew :kdiff-tutorial:test` passes
  <!-- Added during apply: the baseline in 1.1 turned up a third warning, in code written by the
       preceding change. It belongs here because this change's whole purpose is a quiet build. -->

## 3. The redundant else

- [x] 3.1 In `DiffProcessor.sealedApplyBody`, emit the trailing `else -> PatchResult(before)` only when
  the sealed type has no subclasses, since a `when` over an enumerated sealed hierarchy is already
  exhaustive — verify `./gradlew :kdiff-processor:compileKotlin` succeeds
- [x] 3.2 Add a kctfork scenario compiling a `@Diffable` sealed type with subclasses and asserting the
  generated `apply` contains no `else` branch, and that a round trip through it still reproduces the
  target — verify the generated code compiles warning-free and behaviour is unchanged
- [x] 3.3 Add a kctfork scenario for a `@Diffable` sealed type with **no** subclasses, asserting it
  still compiles and that its generated `apply` retains the `else` — the case that makes the
  conditional necessary rather than cosmetic
- [x] 3.4 Verify the existing sealed specs still pass unchanged — `CollectionAndSealedSpec`,
  `PatchGenerationSpec` and `DifferGenerationSpec` — so the removed branch really was unreachable

## 4. Regenerate the examples

- [x] 4.1 Regenerate `kdiff-sample` and confirm the only difference is the removed `else` line in
  `PaymentDiff.kt` — verify by diffing the generated sources against a snapshot taken first
- [x] 4.2 Regenerate `kdiff-tutorial` and confirm the same for `ContactMethodDiff.kt` and
  `EmploymentDiff.kt`, and that no other generated file changes — verify by diff
- [x] 4.3 Confirm no hand-written file in either example module needed a change — verify with
  `git status`

## 5. Verification

- [x] 5.1 Re-run the baseline from 1.1 and confirm both warning classes are gone, with no new warning
  introduced — report the before and after counts
- [x] 5.2 Confirm the documentation samples still match their sources, since `Select.kt` is a cited
  file and the harness now watches it — verify the docs check passes
- [x] 5.3 Run `./gradlew clean check` and report the result verbatim
