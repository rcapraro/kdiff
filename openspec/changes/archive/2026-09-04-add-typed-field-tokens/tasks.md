## 1. Runtime API

- [x] 1.1 Add `FieldPath.rootName()` to `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/FieldPath.kt`,
  yielding the first segment's property name and nothing for the root path or a non-property first
  segment (design D3) — verify a Kotest spec covers all three scenarios of "A path can name the property
  a change sits under"
- [x] 1.2 Add `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Tokens.kt` with `FieldToken`,
  `FieldTokens<F>`, `ElementField<E>`, `KeyedField<E, K>` and `Change.fieldOf(tokens)` (design D3, D4)
  — verify `./gradlew :kdiff-runtime:compileKotlin` passes under `explicitApi()` with KDoc on every
  public declaration and no new warning
- [x] 1.3 Verify `fieldOf` resolves a change to its token, yields null at the root, and yields null for
  a name absent from the token set — a Kotest spec against a hand-written `FieldTokens`, covering the
  three scenarios of "A change resolves to the token of the property it sits under"

## 2. Resolution model

- [x] 2.1 Extend `Comparison` in `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/Resolution.kt`
  to carry the element type for `PositionalList`, `AsSet` and `AsMap`, and the `@DiffKey` property's
  type for `KeyedList` (design D5) — additive only, so `emit` and `emitPatch` keep compiling unchanged
  — verify `./gradlew :kdiff-processor:test` passes with no spec edited
- [x] 2.2 Populate the new type names in `resolveList` and `resolveMap`, where the types are already
  resolved — verify a kctfork scenario asserts the emitted token type arguments for a keyed list, a
  positional list, a set and a map

## 3. Token generation

- [x] 3.1 Emit the sealed token hierarchy into the existing `<Type>Diff.kt` for a class that is both
  `@Diffable` and `@Trackable`, one `data object` per compared property, each exposing `propertyName`
  (design D1, D7) — verify a kctfork scenario reads the tokens back off the compiled output and
  asserts one per compared property
- [x] 3.2 Emit the `companion object : FieldTokens<F>` with its `byName` table (design D3) — verify
  `fieldOf` resolves each property name to its token and an unknown name to null, against compiled
  generated code
- [x] 3.3 Give a collection property's token `ElementField<E>` with a generated `elementOf` that
  reads `Added` and `Removed` and yields null otherwise, and give a keyed list's token
  `KeyedField<E, K>` with a generated `keyOf` (design D4, D5) — verify kctfork scenarios cover the four
  scenarios of "A collection property's token exposes the change's element at its element type" and the
  three of "A keyed collection's token exposes the element key at its key type"
- [x] 3.4 Verify `@TrackIgnore` properties are tokenised and `@DiffIgnore` properties are not
  (design D2) — kctfork scenarios for both, plus a check that the tracking scope is unaffected
- [x] 3.5 Verify a class that is `@Diffable` but not `@Trackable` generates no tokens and its file is
  otherwise unchanged — a kctfork scenario asserting the generated source contains no token hierarchy
- [x] 3.6 Verify a `when` over the tokens plus null compiles **without** an `else`, and that adding a
  compared property makes the same `when` fail to compile — two kctfork scenarios, the second being the
  one that proves the whole change is worth making
- [x] 3.7 Verify generating tokens changes nothing observable: the same class compiled with and without
  tokens available reports the same changes in the same order, applies to the same result, and declares
  the same tracking scope — kctfork scenarios for "Generating tokens leaves comparison, application and
  tracking unchanged"

## 4. Diagnostics

- [x] 4.1 Reject a `@Trackable` class whose two compared properties would produce the same token name,
  with a `KSPLogger.error` at the class naming both properties (design D6) — verify `DiagnosticSpec`
  asserts a failing exit code, the message and the reported location, and that the error is **not**
  a duplicate-declaration error from generated code
- [x] 4.2 Reject a property whose name yields no valid identifier after PascalCase conversion, at the
  property (design D6) — verify `DiagnosticSpec` covers it
- [x] 4.3 Verify one rejected class fails the build alongside a valid one, and the failure is
  attributable to the offending class — `DiagnosticSpec` scenario

## 5. Incremental processing

- [x] 5.1 Confirm `Dependencies(aggregating = false, originatingFile)` is unchanged and the dependency
  set did not grow, since element and key types are already dependencies through their differs
  (design D8) — verify by inspecting the emission call site and by a kctfork scenario where an unrelated
  file changes and the token-bearing file does not regenerate
- [x] 5.2 Confirm in `kdiff-tutorial` that changing `Address` still regenerates `AddressDiff.kt` and
  that `PersonStateDiff.kt`'s tokens are unaffected by an unrelated edit — report what was observed

## 6. Rewrite the tutorial's dispatch

- [x] 6.1 Delete the tutorial's local `root()` and `elementKey()` helpers now that `kdiff-runtime`
  provides both — verify nothing else referenced them
- [x] 6.2 Rewrite `dispatch` in `UpdatePersonHandler.kt` on `change.fieldOf(PersonStateField)` with an
  exhaustive `when`, no `else`, and the audit case as an explicit list of the properties the domain has
  no opinion about (design D10) — verify no cast remains in the file and
  `./gradlew :kdiff-tutorial:test` passes unchanged, since behaviour must be identical
- [x] 6.3 Verify the rewritten dispatch produces byte-identical output from
  `./gradlew :kdiff-tutorial:run` compared to before, proving the rewrite changed no behaviour — diff
  against a captured baseline
- [x] 6.4 Add a spec asserting the exhaustiveness property is real: a Kotest spec is not enough, so
  verify by adding a property to a fixture state class in `kdiff-processor`'s compile-testing (task 3.6)
  rather than claiming it here

## 7. Documentation

- [x] 7.1 Rewrite the dispatch section of `docs/tutorial.md` on the new API, with a short note on why
  the string form was fragile — a typo compiled and a forgotten property compiled — and no side-by-side
  old version — verify every sample carries a `from:` marker and the docs check passes
- [x] 7.2 Document `rootName`, tokens and typed access in `docs/diffing.md` under the existing path and
  change-model sections — verify the samples trace to `kdiff-runtime` or `kdiff-tutorial`
- [x] 7.3 Add the new annotations-adjacent behaviour to `docs/annotations.md`: `@Trackable` now also
  generates field tokens, and the two new compile errors — verify each claim matches a requirement in
  `openspec/specs/diff-generation/spec.md` and a test in `DiagnosticSpec`
- [x] 7.4 State in the tutorial that an exhaustive `when` will break when the model gains a property,
  and that this is the point (design, Risks) — verify the note exists

## 8. Verification

- [x] 8.1 Confirm `kdiff-sample`'s `Order` is `@Trackable`, so its generated file gains tokens, and that
  every existing sample spec still passes unchanged — verify and report
- [x] 8.2 Confirm no warning is introduced anywhere, comparing against a baseline captured first — the
  generated `as?` casts must not warn
- [x] 8.3 Confirm `gradle/libs.versions.toml` is unchanged and no module gained a dependency
- [x] 8.4 Run `./gradlew clean check` and report the result verbatim
