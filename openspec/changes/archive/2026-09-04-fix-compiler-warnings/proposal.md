## Why

`./gradlew check` is green but not quiet. Two compiler warnings survive it, both found while writing
`kdiff-tutorial` and recorded there as follow-ups rather than fixed in a change that had committed to
touching no library source.

A warning that always fires is a warning nobody reads. Both of these have persisted across several
changes precisely because they became background noise — one of them slipped in unnoticed during
`add-change-tracking` because the build output was being filtered for errors.

## What Changes

- **`kdiff-runtime`: silence an unchecked cast in `Select.kt`.** `resolveAgainst` tests a `Differ<T>`
  for `Tracked<T>` with `as?`, which the compiler cannot prove because both are generic. The cast is
  safe — a failed test yields null and the null path is the "declares no scope" case — so it gets
  `@Suppress("UNCHECKED_CAST")`, which is how `Patch.kt` already handles the same situation.
- **`kdiff-processor`: stop emitting a redundant `else` in a generated sealed `apply`.** The `when`
  over a sealed type's subclasses is already exhaustive, so the trailing `else` draws a warning in
  every consumer that annotates a sealed type. Emitted only when it is actually needed — see below.

**Modules affected:** `kdiff-runtime` (one annotation) and `kdiff-processor` (one conditional).
`kdiff-annotations` and the two example modules are untouched except for regenerated output.

### The generated API surface changes, additively and not BREAKING

Generated text changes for a `@Diffable` sealed type: its `apply` loses one line. No signature, name,
package or behaviour changes, and a diff or patch produces identical results before and after — the
removed branch was unreachable. Consumers need not adapt; those who recompile get one fewer warning.
Annotation semantics are unchanged.

### The one thing that needs care

The `else` cannot simply be deleted. A sealed type with **no** subclasses is accepted today —
`sealedBody` only rejects subclasses that are not `@Diffable`, and an empty list has none — and for
that type the branchless `when (before) { }` would be a compile error in generated code. Trading a
warning for a broken build would be a bad deal.

So the `else` is emitted exactly when the subclass list is empty, and a compile-testing scenario pins
that case rather than trusting the reasoning.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. This change declares skip_specs: true. -->

Neither fix changes observable behaviour. `diff-application`'s requirements describe what applying a
diff does, and they hold identically before and after: the branch removed was unreachable for any
sealed type that has subclasses, and is retained for the one shape where it is reachable. No
requirement changes, and none is invented to satisfy validation.

## Impact

- **Modified:** `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Select.kt` (one annotation);
  `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/DiffProcessor.kt` (one conditional);
  `kdiff-processor`'s compile-testing specs gain scenarios for both the ordinary and the empty sealed
  type.
- **Regenerated:** the sealed differs in `kdiff-sample` and `kdiff-tutorial`. No hand-written file in
  either example changes.
- No dependency, Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
