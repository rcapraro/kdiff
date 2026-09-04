## Why

kdiff is careful about typed selectors everywhere a caller *names* a property — `field(Order::total)`,
`under(Order::billing)` — because a mistyped name should not compile. Dispatch is the one place that
falls back to string literals, and the tutorial makes it visible:

```kotlin
when (change.path.root()) {
    "name" -> person.rename(dto.name)
    "nickname" -> person.changeNickname(dto.nickname)
    // ...
    else -> Unit
}
```

Two failures hide in that shape, and neither is caught by anything:

1. **A typo compiles.** `"nickanme"` is a valid `String`, matches nothing, and falls into `else`. The
   command silently does less than it was asked to.
2. **A forgotten property compiles.** Add a property to the aggregate's state and the dispatch keeps
   compiling, keeps passing its tests, and quietly stops emitting an event for it. `when` on a
   `String?` cannot be exhaustive, so nothing can tell you.

The second is the worse one. It is not a typing mistake a careful author avoids; it is what happens
months later when someone else extends the model.

The tutorial also casts, twice, for the same reason — `change.value as Address` and a cast to reach a
keyed element's `AddressId` — because `Added.value` is `Any?` and a key is `Any?`. Those casts are the
same fragility seen from the other end: the library knows the element type at generation time and
declines to say so.

## What Changes

- **New: generated field tokens.** A `@Trackable` class gets a generated sealed hierarchy naming each
  of its compared properties, so a `when` over them is **exhaustive**. Adding a property to the model
  breaks the dispatch at compile time until it is handled.
- **New: `FieldPath.rootName()`** in `kdiff-runtime` — the property a change sits under, promoted from
  the tutorial's local helper.
- **New: `Change.fieldOf(tokens)`** resolving a change to its token, or `null` when the change sits at
  the root and belongs to no property.
- **New: typed element and key access on tokens.** A collection property's token exposes the change's
  element at its real type, and a keyed list's token exposes the element key at its real type. The
  casts happen inside generated code where the type is statically known, so a consumer never writes
  one and no reflection is involved.
- **New: two compile-time diagnostics** for `@Trackable` shapes the token generation cannot honour
  (see the `diff-generation` delta).
- **Modified: `docs/tutorial.md` and `kdiff-tutorial`.** The dispatch is rewritten on the new API and
  both casts disappear. The old form is not shown side by side; a short note records why strings were
  fragile.

**Modules affected:** `kdiff-runtime` (the token interfaces, `rootName`, `fieldOf`), `kdiff-processor`
(emission and diagnostics), `kdiff-tutorial` and `docs/`. `kdiff-annotations` is untouched — no new
annotation is needed, because `@Trackable` already marks the types that want dispatch.

### The generated API surface changes, additively

A `@Trackable` class's generated file gains a sealed token hierarchy and a companion that resolves a
name to a token. Existing generated declarations keep their names, packages and signatures, and
`diff`, `apply` and `trackScope` are unchanged. A class that is `@Diffable` but not `@Trackable` gets
byte-for-byte the file it gets today. **Not BREAKING** — no consumer must adapt, and annotation
semantics are unchanged.

### Two decisions worth surfacing now

**Tokens are gated on `@Trackable`, not generated for every `@Diffable` type.** Tokens exist to
dispatch changes, dispatch is what tracking is for, and generating ~30 lines per type for every
annotated class in a large project would inflate every consumer's build for an API most of them never
touch. The cost is that a consumer who diffs without tracking must add `@Trackable` to get tokens.

**Tokens cover every *compared* property, not only the tracked ones.** A dispatch may run over a raw
`Diff` as easily as over a tracker's report, and a diff reports `@TrackIgnore` properties. If tokens
covered only the tracked subset, `null` would mean both "a root-level change" and "a property I chose
not to track" — two very different things collapsed into one branch. Covering the compared set keeps
`null` meaning exactly one thing.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `diff-generation`: a `@Trackable` class's generated API gains field tokens, a resolver, and typed
  element and key access; `FieldPath` gains a way to name the property a change sits under. All of it
  extends the existing contract for how a change identifies where it was found.

`change-tracking` is deliberately **not** modified. Nothing about what a tracker observes or reports
changes — tokens describe a change's location, which is the change model, not the tracker.

## Impact

- **New public API in `kdiff-runtime`:** `FieldPath.rootName()`, `FieldToken`, `FieldTokens<F>`,
  `Change.fieldOf(tokens)`, and the two typed-access interfaces a generated token implements where
  they apply (`ElementField<E>`, `KeyedField<E, K>`).

  There is deliberately **no** typed access to a change's *value*. It would be unsound: for a nested
  property such as `name: FullName` the change arrives at `name.family` carrying a `String`, not a
  `FullName`, so a `valueOf(token)` typed by the property would lie. Callers read new values from the
  command that supplied them, which is what the tutorial already does.
- **Modified `kdiff-processor`:** token emission for `@Trackable` types, plus the new diagnostics.
- **Modified `kdiff-tutorial`:** the dispatch, and the removal of `root()` and `elementKey()` now that
  the library provides both.
- **Modified `docs/tutorial.md`** and, where they describe paths and dispatch, `docs/diffing.md` and
  `docs/tracking.md`.
- **Regenerated:** `kdiff-tutorial`'s `PersonStateDiff.kt` gains tokens. `kdiff-sample` is unchanged —
  nothing in it is `@Trackable`… except `Order`, which is, so its generated file gains tokens too and
  its specs must still pass.
- No dependency, Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
