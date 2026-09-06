## Context

See proposal.md — Why. What matters for the approach is where the collapse happens and what it costs
to notice it.

Both helpers reach for `associateBy`, which is where a repeated key disappears:

```
   compareKeyedList(name, keyProperty, before, after, differ, keyOf)
      before.withIndex().associateBy { keyOf(it.value) }     <-- last wins
      after .withIndex().associateBy { keyOf(it.value) }     <-- last wins
      then: walk beforeByKey, walk afterByKey

   patchKeyedList(source, changes, patcher, keyOf)
      byKey = source.associateBy { keyOf(it) }               <-- last wins
      order = source.map { keyOf(it) }                       <-- keeps BOTH
      ...
      positioned = order.filter { it in byKey }              <-- keeps BOTH
      return target.filterNotNull().map { byKey.getValue(it) }
                                          ^^^^^^^^^^^^^^^^ same element, twice
```

The compare side loses an element. The patch side loses one *and* duplicates another, because `order`
and `byKey` disagree about how many elements there are — which is why an empty change list is enough
to corrupt the result.

Detection is nearly free and needs no new data structure: `associateBy` already builds the map, and a
map smaller than the list it came from is exactly the duplicate condition. One `size` comparison per
side, O(1) after work that was happening anyway. There is no performance argument for leaving this
alone, which removes the only reason to prefer a cheaper half-measure.

## Goals / Non-Goals

**Goals**

- A duplicate key fails loudly, at the point the data is read, in both directions.
- One rule and one message shape, so `diff` and `apply` never disagree about the same list.
- The failure names what a caller needs to fix it: the property, the key value, the type.
- Annotated and hand-written differs gain the check together, without the processor changing.

**Non-Goals**

- Not making duplicate keys *work*. There is no representable diff for them; this change refuses, it
  does not support.
- No compile-time diagnostic. Uniqueness is a data property; the declaration cannot carry it.
- No new `Change` variant, and no change to `PatchResult`'s shape.
- No global "lenient mode" flag. A switch between "correct" and "silently wrong" is not a
  configuration axis worth having, and adding one would make the wrong behaviour permanent.

## Decisions

### D1. Throw, rather than reporting through the existing failure channels

`compareKeyedList` returns nothing — it appends to a `MutableList<Change>` — so it has no channel to
report through at all short of a new `Change` variant, which the closed vocabulary rules out.
`patchKeyedList` does have one, in `PatchFailure`. Using it on the patch side alone was considered and
rejected in the proposal: the two directions would answer differently about identical data, and a
caller who diffs and then applies would get an exception from the first call and a soft failure from
the second.

There is precedent for throwing on a misuse in this runtime: `ChangeRoutes.otherwise` uses
`require(fallback == null) { "a routing declares one otherwise handler; this is the second" }`. A
duplicate `@DiffKey` value is the same category — a caller has told the library something is an
identity when it is not.

`IllegalArgumentException` via `require`, matching that precedent. Not a bespoke exception type: a new
public exception class is API surface to maintain, and nothing here is recoverable in a way a caller
would branch on. If it later turns out consumers want to catch this specifically, adding a type is
additive and can be its own change.

**Alternative considered:** returning the changes computed so far plus a sentinel. Rejected — it is a
silent fallback wearing a hat, and every caller would have to remember to check.

### D2. Both sides of a comparison are checked, and the check runs before any comparing

`compareKeyedList` checks `before` and `after`. A duplicate on either side makes the result
unrepresentable, and checking only the side that happens to be walked first would make the behaviour
depend on argument order.

The check runs before the first change is appended, so a rejected comparison contributes nothing to
the caller's `MutableList<Change>`. A partially-populated list plus an exception would leave a caller
who catches the exception holding changes from an interpretation the library just declared invalid.

### D3. One message, built in one place

Both helpers produce the same message from the same private function, so the two directions cannot
drift:

```
addresses is keyed by id, but two elements share the key A1.
A keyed element must be uniquely identified; addresses[id=A1] cannot name one of them.
```

It names the list (`addresses`), the key property (`id`), the offending value (`A1`) and *why* it
matters, in the vocabulary the paths already use — a reader who has seen `addresses[id=A1]` in a diff
recognises it immediately. The `@DiffKey` mention is accurate for a generated differ and reads as the
concept rather than the annotation for a hand-written one, which names the same thing through
`keyedList(…, Address::id, …)`.

The message reports **one** duplicated key, not all of them. The first is enough to act on, and
collecting every duplicate would mean a second pass on a path that is about to throw.

### D4. The check lives in the runtime helpers, not in generated code

Consistent with the standing rule that algorithms live in `kdiff-runtime` and the processor emits
calls. It also gets the behaviour for free in three places at once: generated differs, `differ { }`
builders, and any hand-written `Differ` that calls the helpers directly — the "when the DSL is not
enough" route `docs/hand-written.md` documents.

`kdiff-processor` is not touched, and the generated output is byte-identical. That is worth verifying
explicitly rather than assuming, since it is the claim that makes this a runtime-only change.

### D5. Tracking inherits the behaviour and gains no rule

`Tracker.update` and `trackedDiff` call the differ and then filter. So the throw happens underneath
them, before any scope is consulted, and a scope that excludes the offending property does not suppress
it.

That ordering is deliberate and worth a test rather than a comment: a scope decides what is *reported*,
not whether the data is interpretable. The alternative — checking only properties in scope — would mean
the same two instances compare fine or throw depending on an unrelated tracking declaration.

### D6. The existing test that documents the collapse is inverted, not deleted

`CompareSpec`'s "a repeated key keeps the last element carrying it, and the earlier one is never
compared" was written one change ago to pin the behaviour being removed here. It is rewritten in place
to assert the rejection, keeping the case in the same context with the other keyed-list tests.

Deleting it would lose the record that this input was once handled differently, which is exactly what a
reader hitting the new exception will want to know.

### D7. `patchKeyedList` gains the two names it needs, and the processor emits them

Found during implementation, and it revises D4's claim that this is runtime-only.

`compareKeyedList` already takes `name` and `keyProperty`. `patchKeyedList` takes neither: it receives
the source list, the changes with the property segment already stripped by `groupByProperty`, a patcher
and a `keyOf` lambda. So it cannot name the list or the key property, which is exactly what D3's
message and the `diff-application` requirement demand. Deriving them is not possible either — the
stripped changes no longer carry the list name, and an empty change list carries nothing at all, which
is precisely the case that corrupts.

So `patchKeyedList` gains `name: String?` and `keyProperty: String?`, and the processor emits them at
the call site it generates.

Both default to **null**, so a hand-written `Patcher` calling the helper directly — the route
`docs/hand-written.md` documents — can omit them. The message then drops the clauses that need them
rather than filling them with placeholders: `this list[key=A1]` reads worse than saying nothing about
which list it was. One builder still produces both shapes, so the two cannot drift.

**What this costs, stated plainly:** the generated method *bodies* change, by exactly those two
arguments. The generated **API** does not — `<Type>Differ` and its methods keep their shape, and
nothing about the published surface moves. The verification task therefore asserts that the generated
diff is those arguments and nothing else, rather than asserting the output is unchanged.

**Alternative considered:** leaving the patch-side message vaguer and amending the
`diff-application` requirement to ask only for the key value. Rejected on the user's decision: a patch
failure that cannot name the offending property is materially harder to act on, and the two directions
wording the same condition differently is the asymmetry D1 already rejected.

### Schema design rules recorded as inapplicable

`openspec/config.yaml` asks a design to cover type resolution, incremental processing, a sample of the
generated file, and the runtime-versus-generated split.

- **Type resolution** — nothing to cover. No annotation is added, no new type shape is accepted or
  rejected, and the processor does not resolve anything it did not resolve before.
- **Incremental processing** — nothing to cover. No generated file changes, so no file's dependencies
  or aggregating flag change.
- **A sample of the generated file** — now applicable, and covered by D7. The generated call site
  changes from `patchKeyedList(before.addresses, grouped.forProperty("addresses"), AddressDiffer) { it.id }`
  to the same call carrying `name = "addresses", keyProperty = "id"`. That single line is the whole of
  the generated diff; every other generated declaration is unchanged.
- **Runtime versus generated** — the check itself is entirely runtime, per D4. The processor's only
  new work is passing two string literals it already knows; no logic is generated.

## Risks / Trade-offs

**The rejection surfaces far from the data that caused it** — inside a `Tracker.update` in a request
handler, say, rather than at the line that built the list. → Mitigated by the message naming the
property, the key property and the offending value, so the fix is legible from the stack trace alone
without reproducing anything.

**The exception escapes from a `diff` a caller believed total.** `Differ.diff` is documented as pure
and is widely assumed not to throw. → The KDoc on both helpers and on `Differ.diff` states the
precondition, so it is discoverable before it is hit; and the refusal is confined to input that has no
answer to give, never to input a comparison could describe.

**Two elements sharing a key may look legitimate in a caller's model**, with `@DiffKey` on a property
that was never meant to be unique. → Then the property is not an identity and should not be declared
as one: drop `@DiffKey` and compare the list by position. That is one edit, and it is what the data is
asking for.

**Cost on a hot path.** → One `Map.size` comparison per side per keyed list, on a map that was already
built. Not measurable, and no benchmark is added or claimed.

## Migration Plan

Nothing to migrate. The library is unreleased work in progress: there is no deployed consumer holding
a model that this refuses, and no coordination between versions to arrange.

Rollback is reverting the two helpers and the processor's call site; nothing persists, and no
annotation or spec'd compile-time behaviour is involved.

## Open Questions

None that change the specs, the approach or the task breakdown. One worth revisiting only if consumers
ask: whether the rejection deserves a named exception type they can catch. Adding one later is
additive.
