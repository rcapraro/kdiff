## Context

See `proposal.md` — *Why*. Three facts about the current code shape the approach.

**The whole limitation lives in one alignment step.** `comparePositionalList` (`Compare.kt`) is two
loops: zip to `minOf(before.size, after.size)`, then report the leftover tail. The *emission* half —
`ValueChanged` / recurse for a pair, `Added` and `Removed` for a leftover — is already correct and
already patchable. Only the pairing is wrong.

```
   before[]  after[]
        \      /
         v    v
    +--------------+
    |  ALIGNMENT   |  <--- zip by index; the only thing this change touches
    +--------------+
           |
      pairs + leftovers
           v
    +--------------+
    |   EMISSION   |  ValueChanged / recurse | Added | Removed
    +--------------+   unchanged
```

**`patchPositionalList` already speaks edit-script.** Its replay order (`Patch.kt:196-249`) is:

```
1. element changes applied in place, indexed into `source`   -> OLD indices
2. `removed` indices filtered out of `source`                -> OLD indices
3. `added` inserted ascending, clamped to size               -> NEW indices
```

That is the standard delete-by-old-index / insert-by-new-index replay, so it already round-trips an
interior insertion or deletion. Today's comparison simply never emits one — additions and removals
only ever occur in the disjoint tail, where old and new indices coincide. **No patching change is
needed**; what changes is that a previously unreachable branch of the replay becomes reachable, which
is why `diff-application` gains a scenario rather than a requirement.

**Positional comparison is reached identically from both routes.** The processor emits
`comparePositionalList("tags", before.tags, after.tags, null)` and `DifferBuilder.list` calls the same
function. Changing the runtime helper keeps generated and hand-written differs indistinguishable with
no coordinating work — which is the requirement, not a convenience.

## Goals / Non-Goals

**Goals**

- One contiguous insertion or deletion, at any position, reports as exactly those `Added` / `Removed`
  changes.
- Equal-length lists produce byte-identical output to today, provably rather than incidentally.
- No comparison added: the number of element comparisons stays exactly what it is today, and the
  `differ == null` branch still allocates nothing.
- No public signature changes, so no ABI dump is regenerated.

**Non-Goals** (beyond the proposal's scope exclusions)

- Reporting the *minimal* edit script. Trimming is not minimal for two or more scattered edits and
  makes no claim to be.
- Any change to how nested elements are compared once paired.
- Any change to the descent bound. `Descent.into` is still entered once for the whole list.

## Decisions

### D1 — Trim inside `comparePositionalList`, not in a new helper

The alternative was `compareAlignedList` alongside the existing function, letting callers choose.
Rejected: choosing requires a knob, a knob requires `@DiffList`, and the proposal establishes that the
only constituency for the knob is protected by the equal-length guarantee anyway. A second public
helper would also split the generated/hand-written routes into two behaviours that must be kept in
step by hand. One function, one behaviour.

### D2 — Trim only the common suffix; the forward pass handles the prefix

One backward scan produces the window. There is no prefix scan.

```
s = 0
while s < min(bs, as) and same(before[bs - 1 - s], after[as - 1 - s]):  s++

window(before) = before[0 until bs - s]
window(after)  = after [0 until as - s]
```

`same` is defined in D5. A leading trim is unnecessary: both windows begin at index 0, so the forward
pass pairs `before[i]` with `after[i]` and reports nothing across the leading positions where the two
lists already agree — exactly what a prefix trim would have achieved, without a second scan and
without a guard stopping the two scans from crossing.

The pairing is therefore unchanged from today, and the window is a prefix of today's compared range:

| | today | after this change |
|---|---|---|
| pairs compared | absolute `[0, min(bs,as))` | absolute `[0, min(bs,as) - s)` — same pairing, shorter range |
| pairs skipped | none | only trailing positions whose comparison would report nothing |
| `Added` / `Removed` | at `[min, max)` | at `[min - s, max - s)` — same count, earlier indices |

**When `bs == as`**: the two windows have equal length, so there is no tail; the pairing inside the
window is today's pairing; and the trimmed trailing positions are ones whose comparison reports
nothing, so today reported nothing there either. The output is identical. This is `G5` in the spec,
and it is checkable as a property test over arbitrary equal-length pairs rather than as a handful of
examples.

That the backward scan alone suffices is not an approximation. For a single contiguous run inserted
or deleted at position `k`, the scan matches every position after the run, leaving `s = bs - k`; the
window's forward pass then reports nothing across `[0, k)` — the untouched leading elements — and the
run itself as additions or removals at `k` onward.

Alternatives. Trimming the prefix *only* does not fix a head insertion at all, since there is no common
prefix there. Trimming both ends was the first shape of this design; it needs an overlap guard between
the two scans and shifts reported indices for a list with repeated elements at the boundary, buying
nothing the backward scan does not already deliver. Re-basing window indices to 0 would make the
reported paths meaningless to the patcher and to a reader.

### D3 — Index coordinates: new for additions, old for removals — and they never have to disagree

`Added` carries a new-list index and `Removed` an old-list one, forced by the replay in *Context*:
`patchPositionalList` inserts against the target's positions and deletes against the source's.

The first draft of this section carried a caveat — that after an interior insertion an element change
could be reported at an index the element no longer occupies. **It cannot.** The arithmetic rules it
out, and the guarantee that replaces it is stronger than the caveat it removes:

```
agreeing = the trailing run the two lists agree on
shared   = min(bs, as) - agreeing

element changes   index in [0, shared)
additions         index in [shared, as - agreeing)
removals          index in [shared, bs - agreeing)
                            ^ they begin exactly where element changes stop
```

Two consequences:

- **Every element change names the same position in both lists.** Both windows begin at index 0, so an
  index below `shared` is that element's index in `before` and in `after` alike. There is no
  divergence to report, and none to state in the spec.
- **A comparison reports additions or removals, never both.** `shared = min(bs, as) - agreeing`, so
  `bs < as` empties the removal range and `bs > as` empties the addition range. Equal lengths empty
  both. The disjointness today's implementation gets from comparing to the common length, this one
  gets from the same identity applied to the window.

An insertion *before* a changed element is therefore not something the coordinates have to describe:
any element change breaks the tail agreement, which forces the window to span from 0 through that
element, so such a pair smears rather than aligning — exactly the two-scattered-edits ceiling the
proposal states. Recovering it would need the anchor-based alignment that is out of scope.

The alternative — reporting an addition at its old-list index — would break the replay, since nothing
in the source is at that position yet. Reporting both coordinates would need a sixth `Change` variant
or a widened `ValueChanged`, and the vocabulary is closed.

### D4 — The backward scan is bounded by the shorter list

`s` never exceeds `min(bs, as)`. The bound is what keeps the scan from running off the front of the
shorter list, and it is the only guard the algorithm needs now that there is no second scan to collide
with.

Its one visible consequence is a list with repeated elements at the boundary. `["a"]` against
`["a", "a"]` matches the single shared `"a"`, leaving windows `[]` and `["a"]`, so the addition is
reported at index 0 — *an `"a"` was added at the front* — where today it is reported at index 1. For
two identical elements the two readings are equally true, and both replay to the same list. The bound
picks one; the spec states which, so it is a decision rather than a discovery.

### D5 — A position is excluded using the branch's own comparison, never a second one

`comparePositionalList` has two branches: `differ == null` compares elements as opaque values,
`differ != null` descends into them. The backward scan's `same` is, in each branch, exactly the
predicate that branch already uses to decide whether to report a change:

```
differ == null   ->   before[i] == after[j]              the branch's own test
differ != null   ->   differ.diff(before[i], after[j]).isEmpty()   the branch's own work
```

So *excluding a position is equivalent to comparing it* holds **by construction**, and the requirement
carries no precondition on how a differ relates to equality.

**The alternative is wrong, and was this design's first shape.** Trimming both branches on `==` assumes
`differ.diff(a, b)` is empty whenever `a == b`. The library guarantees that nowhere, and two ordinary
shapes break it:

- A generated differ compares `getDeclaredProperties()` minus `@DiffIgnore` (`DiffProcessor.kt:423`) —
  **not** the primary constructor's parameters. A `@Diffable` data class with a property declared in
  its body has that property compared and not covered by the `equals` the compiler generates.
- A domain type whose `equals` is by identity — the standard entity pattern, and the shape
  `kdiff-tutorial`'s annotation-free domain is built on — compares `==` equal to an instance carrying
  different values in the properties its differ reads.

In both, an `==` trim would silently drop a reported change. That is a worse failure than the cascade
this change fixes: a verbose diff is survivable, a diff missing a change is not. It is also invisible —
nothing about the result says a position was skipped.

**Cost is unchanged.** The scan's comparisons are comparisons the forward pass no longer makes:
`s + (min(bs, as) - s) = min(bs, as)`, exactly today's count, plus the one comparison that stops the
scan. Trimming relocates work rather than adding it, in both branches.

The `differ != null` branch is also where trimming pays best: a mispaired `@Diffable` element is
compared property by property, so a head insertion today produces a nested cascade, not just a shallow
one.

### D6 — Nothing is generated; the change is entirely in `kdiff-runtime`

Per the repository rule that algorithms live in the runtime and the processor emits calls to them.
The generated file is unchanged, byte for byte:

```kotlin
public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order> {
    override fun diff(before: Order, after: Order): Diff = Diff(
        buildList {
            compareValue("reference", before.reference, after.reference)
            compareKeyedList("addresses", "id", before.addresses, after.addresses, AddrDiffer) { it.id }
            comparePositionalList("tags", before.tags, after.tags, null)     // <- identical call
            compareSet("labels", before.labels, after.labels)
        },
    )
    // apply(...) and trackScope unchanged
}
```

Consequences that would otherwise need covering in this section, and why they are empty here:

- **Type resolution** — untouched. Nullability, generics, enums, maps, sets and nested `@Diffable`
  types all reach `comparePositionalList` through the same call the processor already emits; nothing
  about how a property's type is resolved or which helper it selects changes. A `List<T?>` trims on
  `==`, which handles `null` on both sides correctly with no special case.
- **Incremental processing** — untouched. No generated file changes, so no file's `Dependencies` set
  changes and nothing becomes aggregating. This ships as a `kdiff-runtime` version bump; consumers do
  not recompile their annotated types to get it, which is the reason the algorithm lives there.
- **Runtime versus generated** — all runtime, for the reason above.

### D7 — `Moved` is still never reported for an unkeyed list

Kept as written in the existing requirement. Trimming produces no information about reordering — it
only recognises agreement at the two ends — so there is nothing to report even if the prohibition were
lifted, and `patchPositionalList` has no `Moved` branch to replay it with.

## Risks / Trade-offs

**A later edit weakens the trim predicate to `==`** → the failure this change came closest to shipping.
It is silent: a position is skipped and nothing in the result says so. Mitigated by stating the rule in
the spec as *comparing the position would report nothing* rather than as element equality, and by a
scenario over an element type whose `equals` is looser than its differ — which fails the moment the
predicate is weakened. See D5 for the two ordinary shapes that break under `==`.

**An insertion before a changed element still smears** → the two-scattered-edits ceiling, stated in the
proposal and in the rewritten `docs/architecture.md` bullet. Not mitigated: any element change breaks
the tail agreement, so trimming cannot align across it (D3). The docs must not imply otherwise.

**Scattered edits still smear, and the docs must not imply otherwise** → the `docs/architecture.md`
bullet shrinks rather than disappears, and says plainly that trimming handles one contiguous edit.
Overclaiming here is worse than the current honest limitation.

**A consumer's asserted change lists change shape** → BREAKING, per the proposal. Confined to unkeyed
lists whose length differs; the equal-length guarantee means a large class of consumers see nothing at
all. The `CHANGELOG.md` entry carries the one-sentence description and the before/after example.

**An addition into a run of identical elements is reported at the front of the run** → D4. `["a"]`
against `["a", "a"]` reports the addition at index 0 rather than index 1. Both readings are true and
both replay to the same list; the spec names the one the bound produces so it is not discovered later.

**Off-by-one in the backward scan** → D4, plus a scenario for the `["a"]` versus `["a", "a"]` case and
one for two lists that agree at neither end, where `s == 0` must leave behaviour exactly as it is
today.

## Migration Plan

Not applicable in the deployment sense — a library version bump, no data or configuration to migrate.
A consumer adopting the new runtime re-baselines assertions over unkeyed lists that change length; the
`CHANGELOG.md` entry is what they read to know that. Rollback is pinning the previous
`kdiff-runtime` version, which is possible precisely because the algorithm is not generated into their
build.

## Documentation

Five passages state the current behaviour and are rewritten in the same commit as the code, so nothing
in the repository describes a trim the runtime does not yet do. They are given verbatim here because
the wording is a design decision — in particular, the *What kdiff does not do* bullet **shrinks rather
than disappears**, and overclaiming there would be worse than the limitation it replaces.

One sentence deliberately survives untouched: `docs/architecture.md`'s *"There is no quadratic pairwise
matching and no edit-distance search anywhere in the library, so no collection size makes a comparison
fall off a cliff."* Trimming is agreement at the two ends, not a search, so the claim stays literally
true and must not be softened.

### 1. `docs/architecture.md` — the cost table row for a positional list

```
| positional list | one pass backward from the end, then one pass over the window that remains | linear |
```

### 2. `docs/architecture.md` — the *What kdiff does not do* bullet

> **An unkeyed list is matched by position, not by content.** kdiff excludes the tail the two lists
> already agree on and compares only what remains, so one contiguous insertion or deletion — at the
> head, in the middle, or at the tail — reports as exactly those additions or removals. Two scattered
> edits do not: `[A,B,C] -> [X,A,B,C']` agrees at neither end and smears again, because recovering that
> would need the edit-distance search the linear bound rules out. `@DiffKey` on the element type
> remains the answer wherever elements have an identity, and it is why keyed lists are the shape the
> library is built around.
>
> Two lists of the *same length* are always compared index by index, whatever their contents. For a
> fixed-arity list — seven weekday slots, a coordinate triple, a three-place ranking — the index **is**
> the element's identity, and that guarantee is what stops kdiff reporting an addition and a removal
> for what is a change of position.

### 3. `docs/diffing.md` — the *Without a key* paragraph under lists

> **Without a key**, elements are matched by position after the tail the two lists already agree on is
> excluded, so one contiguous insertion or deletion reports as exactly that rather than shifting
> everything after it. A removal is reported at its index in the old list and an addition at its index
> in the new one; an element change always names a position *both* lists hold, and one comparison
> reports additions or removals but never both. Two lists of the same length are always compared index
> by index. A move is never reported — with no key there is nothing to recognise a moved element by.

### 4. `docs/hand-written.md` — the builder table row for `list`

```
| `list(p, differ?)` | by position, after excluding an agreeing tail; never a move | a `List` whose element declares no key |
```

### 5. `kdiff-runtime` — the `DifferBuilder.list` KDoc

```kotlin
/**
 * Compares [property] by position, excluding the tail the two lists already agree on so that one
 * contiguous insertion or deletion reports as such rather than shifting every element after it.
 * Descends into elements with [differ] when one is given.
 *
 * A move is never reported: with no key there is nothing to recognise a moved element by. Name a
 * key with [keyedList] to get moves.
 */
```

The KDoc on `comparePositionalList` itself changes in step, and is the one place that also states the
index rule from D3 — a caller writing an `object : Differ<T>` by hand reads that helper's documentation,
not the builder's. It must state the rule as D3 finally landed it (an element change names a position
both lists hold; additions or removals, never both), not as the retracted first draft had it.
