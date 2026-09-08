## Why

An unkeyed list is compared index by index, so an element inserted or removed anywhere but the tail
misaligns every element after it. `docs/architecture.md` records this under *What kdiff does not do*,
and the cost of the misalignment is worse than the sentence there suggests:

```
before = [A, B, C]
after  = [X, A, B, C]

reported                         true
  ValueChanged(tags[0], A, X)      Added(tags[0], X)
  ValueChanged(tags[1], B, A)
  ValueChanged(tags[2], C, B)
  Added(tags[3], C)
```

Four changes, none of them describing what happened, for one insertion. With an element type that has
its own differ the damage compounds: each mispaired element is compared *property by property*, so a
single head insert produces a cascade of nested value changes at paths that name the wrong element.

The stated answer is `@DiffKey`, and it remains the right answer when elements have an identity. But
plenty of lists have none — `List<String>` tags, `List<Money>` line amounts, an ordered log of
value-typed events — and for those the library today reports a diff that is technically correct,
unreadable, and actively misleading to anything routing on it.

The fix does not require the edit-distance search that the linear-cost promise rules out. Excluding
the two lists' common tail before comparing is one backward scan, adds no element comparison to what
the function already performs, stays O(n), and collapses the example above to the one change it should
be.

## What Changes

One runtime function changes. Nothing else.

**`kdiff-runtime` — `comparePositionalList`**

Before comparing, walk inward from the end of each list while the two agree, bounded by the shorter
list. Compare only the window that remains, and report leftovers at the window's boundary rather than
at the lists' tails.

- Both windows still begin at index 0, so element pairing *inside* the window is exactly the pairing
  used today. This change does not re-pair anything; it stops earlier and relocates the additions and
  removals. No leading run needs excluding — the window's own comparison already reports nothing
  across leading positions where the two lists agree.
- An insertion or deletion of one contiguous run — at the head, in the middle, or at the tail — now
  reports as exactly those `Added` or `Removed` changes and nothing else.
- **When the two lists have the same length, the reported changes are unchanged.** The windows begin
  at index 0 and have equal length, so there is no tail and no re-pairing; the positions excluded are
  ones whose comparison reports nothing, which reported nothing before either.
- A position is excluded using **the comparison that branch would have performed** — equality where
  elements are compared as values, the differ reporting no change where they are compared by a differ.
  Never equality standing in for a differ: a type's `equals` can be looser than the properties its
  differ reads, and excluding on equality alone would silently drop a change. `design.md` — D5 gives
  the two ordinary shapes where that happens.

**A stated guarantee, not an implementation accident**

That last property becomes a written requirement: *an unkeyed list whose two sides have the same
length is compared index by index*. For a fixed-arity list — a seven-slot weekly schedule, a
coordinate triple, a three-place podium — the index **is** the element's identity, and any alignment
that overrides it produces a confidently wrong reading:

```
finalists  [alice, bob, carol]  ->  [bob, alice, carol]

index-as-identity            an alignment that matches on element value
  ValueChanged([0], a, b)      Added(finalists[0], bob)
  ValueChanged([1], b, a)      Removed(finalists[1], bob)
                               ... and alice, who moved from 1st to 2nd,
                                   is not mentioned at all
```

Writing the guarantee down is what removes the need for a `@DiffList(match = POSITIONAL)` escape
hatch in this change: the only constituency for forcing positional comparison is fixed-arity lists,
and they are equal-length by definition, so trimming cannot reach them. It also means a future change
that wants value-based alignment has to revoke a stated promise and supply the knob that buys it back,
rather than surprising a consumer whose podium diff quietly changed shape.

**BREAKING** — no signature is removed, no annotation gains or loses meaning, no `Change` variant is
added, and the generated API surface is byte-identical. What changes is the *content* of the diff for
one case: an unkeyed list whose length differs between the two sides now reports the insertion or
deletion instead of a cascade. A consumer asserting on those change lists must re-baseline them. The
change is narrow enough to state in one sentence, and the behaviour it replaces is not one anyone
chose.

**Not in scope**

- **Patience or LCS alignment.** Trimming solves one contiguous edit per comparison. Two scattered
  edits (`[A,B,C] -> [X,A,B,C']`) trim to nothing and still smear. Anchor-based alignment would fix
  that, at the cost of ~100 lines of LIS machinery whose failure mode is a *silently plausible* wrong
  alignment, and it would break the equal-length guarantee above and so require the annotation, the
  processor work, the DSL parameter and the ABI churn this change avoids. Trimming is not a dead end:
  an anchor-based alignment uses it both as preprocessing and as its between-anchors handler, so
  nothing here would be rewritten.
- **Matching unkeyed elements by their own value.** It fixes insertion by destroying modification:
  `["a","b"] -> ["a","c"]` would become a removal plus an addition instead of one value change.
- **`Moved` for unkeyed lists.** A move stays what a key buys. `patchPositionalList` has no `Moved`
  branch, and the existing requirement that an unkeyed list never reports a move is kept as written.
- **Sets and maps.** Neither is positional; neither is touched.

## Capabilities

### New Capabilities

None. This change narrows what an existing comparison reports; it adds no capability.

### Modified Capabilities

- `diff-generation`: the requirement *A list whose element type declares no key is compared by
  position* is rewritten. It currently says elements are compared index by index and that leftovers
  are reported "at the trailing indices" — both of which stop being true once an agreeing tail is
  excluded. The replacement states the exclusion rule in terms of what a comparison would report, states
  the equal-length guarantee explicitly, states which index each kind of change carries, and keeps the
  existing prohibition on reporting a move.
- `diff-application`: the requirement *Applying a diff to its source reproduces the target* gains a
  scenario for an unkeyed list with an **interior** insertion or removal. `patchPositionalList`
  already replays such a script correctly — it applies element changes and removals at old indices,
  then insertions at new indices in ascending order — but until now the comparison never produced
  one, so the ordering of that replay was untested and unstated. This change makes it load-bearing.

`change-tracking` needs no delta. Tracking filters changes by path depth and never inspects a
positional index, so a change reported at `tags[0]` instead of `tags[3]` selects identically.

## Impact

- **Modules**: `kdiff-runtime` only — `Compare.kt`, one function. `kdiff-annotations`,
  `kdiff-processor` and the `differ { }` DSL are untouched.
- **Public API**: unchanged. `comparePositionalList` keeps its signature, so no ABI dump is
  regenerated and `updateKotlinAbi` is not run.
- **Generated output**: unchanged. The processor already emits a call to `comparePositionalList`; the
  emitted text is identical before and after, so `kdiff-sample`'s generated files do not change and
  the processor's snapshot assertions are unaffected. Its *tests* may change where they assert on the
  diff of a length-changing unkeyed list.
- **Hand-written parity**: preserved without action. `DifferBuilder.list` delegates to the same
  runtime helper, so a hand-written differ gains the improvement in the same commit, which is what the
  "indistinguishable from a generated one" requirement demands.
- **Dependencies**: none added. No version bumped.
- **Docs**: five passages state the old behaviour and are rewritten in the same commit as the code —
  `docs/architecture.md`'s cost table row for a positional list and its *What kdiff does not do*
  bullet, `docs/diffing.md`'s *Without a key* paragraph, `docs/hand-written.md`'s builder table row for
  `list`, and the `DifferBuilder.list` KDoc (with `comparePositionalList`'s own KDoc in step). The
  architecture bullet shrinks rather than disappears: scattered edits still smear, and that ceiling
  should be stated, not implied. `design.md` — *Documentation* holds the replacement wording verbatim.
  One sentence there survives untouched by design: "no edit-distance search anywhere in the library"
  stays true, because trimming is agreement at the ends, not a search.
