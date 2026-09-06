## Why

A keyed list whose elements share a `@DiffKey` value is compared and patched wrongly today, silently,
in two different ways.

`compareKeyedList` matches elements with `associateBy`, which keeps the last element carrying a
repeated key and discards the earlier one. The earlier element is never compared against anything. A
list that loses a duplicate reports a move and a value change rather than a removal — pinned by
`CompareSpec`'s "a repeated key keeps the last element carrying it, and the earlier one is never
compared", written in the preceding change to document the behaviour rather than to endorse it.

`patchKeyedList` is worse, and this part is not documented anywhere. It collapses the same way when
building `byKey`, but builds `order` from the source list directly, so a repeated key appears in
`order` twice. Both occurrences survive into `positioned`, and both resolve through
`byKey.getValue(key)` to the *same* element. So a two-element list whose elements share a key rebuilds
as that one element twice, losing the other — **even when the change list is empty**. The round-trip
property that `RoundTripSpec` states as the library's headline guarantee does not hold for such a list.

Neither is fixable by comparing the duplicates properly. `Segment.Key` identifies an element by its key
*value*, so two elements sharing a key produce colliding paths, and no `Change` at `addresses[id=A1]`
can say which of them it means. Reporting both correctly would need a path segment that distinguishes
them, and the `Change` vocabulary is closed. A duplicate key therefore has no representable diff, and
the only correct behaviour is to refuse rather than to guess.

It cannot be caught at compile time either: uniqueness is a property of the data, not of the
declaration. `@DiffKey` says which property identifies an element; nothing in the source says the
values will differ. So this is a runtime precondition, and the only question was how loudly to fail.

## What Changes

- **`compareKeyedList` rejects a duplicate key.** Before comparing, it checks each side for a repeated
  key and throws `IllegalArgumentException` naming the property, the duplicated key value and the
  list, rather than collapsing.
- **`patchKeyedList` rejects a duplicate key** in its source list, by the same rule and with the same
  message shape, rather than duplicating an element and dropping another.
- **One rule, both directions.** A pair of instances that `diff` refuses is a pair that `apply`
  refuses. The alternative — reporting a `PatchFailure` on one side and throwing on the other — was
  considered and rejected: it makes the two directions disagree about the same data, and `apply`'s
  partial-result contract is about changes it cannot apply, not about a source it cannot interpret.
- **The documentation stops describing the collapse as behaviour.** `docs/diffing.md` currently
  documents the last-wins rule under Lists, and `docs/architecture.md` lists it under "What kdiff does
  not do". Both are rewritten to state the precondition and what violating it costs.
- **What to do about a key that is not unique.** It is not an identity, so it should not be declared as
  one: remove `@DiffKey` from the element type, or describe the property with `list` rather than
  `keyedList`, and the list is compared by position instead, giving up move reporting.

**Modules affected:** `kdiff-runtime` — `Compare.kt` and `Patch.kt`, both called by generated code and
by the hand-written DSL, so annotated and hand-written differs gain the check together. Also
`kdiff-processor`, for the reason in design D7: `patchKeyedList` does not currently receive the list
name or the key property name, and cannot produce the specified error message without them, so it gains
both as defaulted parameters and the processor emits them.

**The generated API surface does not change.** No annotation is added, removed or altered, and no
generated declaration changes shape — `<Type>Differ` and its methods are identical, so no consumer
recompiles differently. The generated method *bodies* do change, by exactly the two new arguments at
the `patchKeyedList` call site; that is a change to emitted code, not to the published surface.
**Annotation semantics are unchanged** in what they accept and reject at compile time: `@DiffKey` still
marks the identifying property, still permits at most one per type, and every existing compile error is
unmoved. What changes is what the runtime does when the *data* violates the uniqueness that `@DiffKey`
always implied.

`patchKeyedList`'s new parameters are **defaulted**, so a hand-written `Patcher` calling it directly
keeps compiling and is not source-broken by this change.

### Tracking is affected only by inheritance

`Tracker` and `trackedDiff` read a diff that a differ produced, so a duplicate key throws from the
comparison beneath them and needs no rule of its own. This is worth stating because a scope that
excludes the offending property does **not** rescue it: filtering happens after comparison, so the
throw comes first. That is deliberate — a scope narrows what is reported, and it should not decide
whether the data is interpretable.

### Explicitly out of scope

- **Maps.** `compareMap` is keyed by the map's own keys, which are unique by construction. Nothing to
  check.
- **Positional lists and sets.** Neither matches by key. `compareSet` compares by membership, where
  duplicates cannot exist in the input type.
- **A compile-time diagnostic.** Impossible, as above — recorded so nobody proposes it again.
- **A new `Change` variant** that could distinguish two elements sharing a key. The vocabulary is
  closed, and widening it to accommodate a modelling error is the wrong trade.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities

- `diff-generation`: "A list whose element type declares a key is compared by key" gains the
  uniqueness precondition and the behaviour when it is violated.
- `diff-application`: the requirement covering keyed-list reconstruction gains the same precondition,
  so both directions are specified together rather than one being left implied.

## Impact

- **Modified source:** `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Compare.kt` and
  `Patch.kt`. Both are `public` helpers on the published surface, so the new precondition is part of
  their contract and belongs in their KDoc. Also the processor's keyed-list patch emission, to pass
  `patchKeyedList` the two names (design D7).
- **Modified tests:** `CompareSpec`'s existing duplicate-key case asserts the old collapse and must be
  rewritten to assert the rejection — it is the one test this change deliberately inverts.
  `PatchSpec` and `RoundTripSpec` gain the patch-side cases, which have no coverage at all today.
- **Modified documentation:** `docs/diffing.md` (the Lists section) and `docs/architecture.md` ("What
  kdiff does not do"), both of which currently describe the collapse as the behaviour to expect.
- **`CHANGELOG.md`** gains an `Unreleased` entry describing the precondition and what to do about a key
  that is not unique.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves. No build script changes.
