## Context

See `proposal.md` — Why. Requirements are in `specs/diff-application/spec.md` and
`specs/diff-generation/spec.md`.

Two earlier changes fixed what this one must work within. The skeleton froze the generated
declaration: `object <Type>Differ` in `<Type>Diff.kt`, in the annotated type's package. The core
change fixed the split — the runtime holds everything type-independent, generated code holds only
what the runtime cannot know without reflection — and produced a change model where every kind
except one carries the data needed to reconstruct a value.

That exception is the reason two requirements in `diff-generation` change here. This is not new
scope: it is the cost of the round-trip guarantee, and it is cheaper to pay now, before anything
outside this repository depends on the change model.

## Goals / Non-Goals

**Goals**

- A total round-trip: `apply(before, diff(before, after))` equals `after`, for every property shape
  the library compares, each an explicit test.
- Patching that never silently loses a change — anything unapplicable comes back named, with a
  reason.
- No cost to `Differ<T>`: hand-written differs keep working untouched.

**Non-Goals**

- Inverting a diff (`undo`). The model will support it once a type change carries both values, but
  it is not specified here and no requirement depends on it.
- Applying a diff to an instance it was not taken from. The result of doing so is whatever the
  changes describe; the library does not verify the source matches, and does not promise anything
  about the outcome. See D6.
- Patching a type kdiff only knows how to compare. That is the `@DiffWith` failure case, and it is
  a reported failure by design rather than a gap to close later.
- Performance. Rebuilding a collection allocates; correctness across shapes first.

## Decisions

### D1 — `Patcher<T>` is a separate interface, implemented only by generated code

```kotlin
public interface Patcher<T> {
    public fun apply(before: T, changes: List<Change>): PatchResult<T>
}
```

`Differ<T>` is untouched. The reason is concrete rather than stylistic: the DSL builds a differ out
of property references, which can read a property but cannot construct the enclosing type. A
hand-written differ therefore *cannot* implement `apply`. Putting `apply` on `Differ<T>` would force
every DSL differ to carry a throwing stub, and would break the core change's rule that a
hand-written differ is indistinguishable from a generated one — a rule the whole escape hatch rests
on.

The generated object gains the interface and keeps its name:

```kotlin
public object PersonDiffer : Differ<Person>, Patcher<Person>
```

Renaming it to something neutral was considered and rejected: the name is public API from the
skeleton change, a rename breaks every consumer, and "differ" is what people will keep calling it.
Adding a supertype is additive; renaming is not.

*Alternative considered*: a separate generated `PersonPatcher` object. Rejected — two objects per
type doubles the generated surface and forces callers to import both to do a round-trip, for no
gain over one object with two interfaces.

### D2 — The two `diff-generation` changes, and why they are forced

**`TypeChanged` gains its values.** It records `beforeType` and `afterType` as strings. A string is
not an instance, and nothing in the change model can turn `"Transfer"` into a `Transfer`. Applying a
sealed subclass swap is therefore impossible today, and the round-trip cannot be total without this.

The core change's design asserted that changes "carry enough information to reconstruct a value". It
is true of `ValueChanged`, `Added`, `Removed` and `Moved`, and false of `TypeChanged`. This corrects
that.

**`Segment.Key` retains the key.** It holds `value: String`, built with `key.toString()`. Matching
an existing element by comparing string forms happens to work; *adding* an entry does not. Applying
an addition to a `Map<Int, V>` needs an `Int`, and `"1"` cannot be turned back into one without
knowing the key type and how to parse it — which is exactly the reflection the library refuses.

So the segment holds the key itself:

```kotlin
public data class Key(public val property: String, public val value: Any?) : Segment
```

Rendering still calls `toString()`, so no rendered path changes — which is what keeps this a change
to the model rather than to observable output. Both are breaking to anyone constructing or
destructuring these types; nothing outside this repository does yet.

### D3 — What lives in `kdiff-runtime` versus what is generated

The core change's split carries over unchanged, and the same asymmetry decides it: generated code
can only be fixed by recompiling consumers, so it stays thin.

| Lives in the runtime | Why not generated |
|---|---|
| `Patcher<T>`, `PatchResult<T>`, `PatchFailure` | The contract and its result. Type-independent. |
| Path matching — does this change belong to this property, and what is left of the path beneath it | Identical for every type and easy to get subtly wrong. |
| List, set and map rebuilding | Generic over the element patcher. Order reconstruction in particular is one algorithm, not one per property. |
| Failure construction and reasons | Message wording should not be baked into consumers' compiled code. |

**Generated — only what the runtime cannot know:** which property a path segment names, how to read
it, which patcher handles it, and — the part only generated code can express — how to build a new
instance with that property replaced. That last one is `copy(name = value)`, and it is the whole
reason `apply` cannot live in the runtime.

The shape mirrors `diff`: one runtime call per property, gathering per-property results, then a
single `copy` with the new values.

### D4 — Applying is a fold over properties, not a walk down paths

The obvious implementation walks each change down the object graph. The generated code instead
groups changes by their first segment, so each property is handled once with the changes that
belong to it, and the property's new value is computed from those.

This matters because `copy` is all-or-nothing: reconstructing `Person` means having the final value
of every constructor property at once. Grouping first gives exactly that, and it means a property
with several changes beneath it — a nested type with three modified fields — is rebuilt once rather
than three times.

Changes whose first segment names no compared property are collected and reported. That is the
unknown-path failure, and grouping makes it fall out of the algorithm rather than needing a
separate search.

### D5 — Collections rebuild by target state, not by replaying operations in order

The order-sensitive case is a keyed list that has a modification, an addition, a removal and a move
at once. Replaying those as operations against a mutable list is where index arithmetic goes wrong:
each removal shifts what every later index means.

So the rebuild computes the target directly:

1. start from the source elements, keyed;
2. drop the keys named by removals;
3. patch the elements named by element-level changes, recursively;
4. add the elements carried by additions;
5. order the result by the target index each element should hold — a moved element's `to`, and for
   everything else its source position adjusted for what was dropped and added.

Nothing mutates a list while indices are being read off it. A positional list is the same idea
without step 5, since it has no moves. A set needs only steps 2 and 4 — it has no order and no
element identity, which is precisely why the core change refuses to report element modifications
for one. A map is the keyed-list algorithm without ordering.

*Alternative considered*: apply operations sequentially in reported order. Rejected — it makes
correctness depend on the order changes happen to be emitted in, which is a comparison detail no
part of the spec pins down.

### D6 — No verification that the diff came from the instance being patched

Applying does not check a change's before-value against what it finds. Two reasons: a diff carries
before-values only for `ValueChanged` and `TypeChanged`, so the check would be partial and its
absence elsewhere misleading; and a partial check invites the belief that a clean apply proves the
diff matched the source, which it would not.

The consequence is stated as a non-goal rather than hidden: applying a diff to an unrelated instance
produces whatever the changes describe, and the library promises nothing about it. Verification
would be a separate, opt-in mode, and it would need before-values on every change kind first.

### D7 — Type resolution: the same table, read for reconstruction

No new property shapes are introduced, so resolution is the core change's ordered table read a
second time — this time asking "how is this rebuilt?" rather than "how is this compared?". Each row
gains a reconstruction, and two of them gain a failure:

| Property shape | Compared by | Rebuilt by |
|---|---|---|
| `@DiffIgnore` | nothing emitted | nothing to apply; source value kept |
| `@DiffWith(D)` where `D` is also a `Patcher` | delegate to `D` | delegate to `D.apply` |
| `@DiffWith(D)` where `D` only compares | delegate to `D` | **failure**, naming the property |
| Primitive, `String`, enum | `!=` | take the change's after value |
| Nullable `T?` | guarded, null either side is a value change | same — the after value, including `null` |
| Nested `@Diffable` | delegate to `<Nested>Differ` | delegate to that type's `apply` |
| `List<E>` keyed | matched by key | D5's rebuild, elements patched recursively |
| `List<E>` positional | index by index | D5 without ordering |
| `Set<E>` | membership | membership: drop removals, add additions |
| `Map<K, V>` | by entry key | D5 without ordering; keys come from the segment (D2) |
| Sealed type | dispatch on subclass | same subclass: delegate; swap: substitute the type change's after value |
| Constructor property | compared | `copy(name = value)` |
| Body property | compared | **failure** — `copy` takes constructor parameters only |

The body-property case is worth naming because it is invisible until someone hits it: a property
declared in a data class body is compared like any other, and cannot be reconstructed. It is a
reported failure with a reason that says why, not a compile error — the type is still perfectly
diffable, and refusing to generate a differ for it would be a large regression to fix a small one.

Generics and unsupported types remain compile errors from the core change; they never reach a
patcher.

Cyclic graphs remain unsupported, for the same reason and with the same consequence.

### D8 — Incremental processing: the same originating files

`apply` is generated into the existing `<Type>Diff.kt`, from exactly the inputs that already
produce `diff`: the annotated type's file, every nested `@Diffable` type's file, every sealed
subclass's file, and every `@DiffWith` target's file. The set does not grow, and `aggregating`
stays `false`.

The one addition is a check rather than a dependency: whether a `@DiffWith` target also implements
`Patcher`. That is read from the same declaration already resolved for `Differ`, in the same file
already recorded — so no new file is read, and nothing new needs declaring.

Verification is the method the core change established, and the reason it exists still applies: at
the pinned KSP version the Gradle worker deletes the whole generated-output directory on every run,
so timestamps prove nothing in either direction. Build with `-Pksp.incremental.log=true` and read
the accumulated source-to-outputs map in `build/kspCaches/<sourceSet>/logs/kspSourceToOutputs.log`.
The expectation here is that the map is *unchanged* from the core change — same inputs, same
outputs — which is a sharper assertion than it sounds: if generating `apply` accidentally reads a
file nobody declared, the map is where it shows up.

## Sample of the generated file

For the model the sample already uses:

```kotlin
@Diffable data class Address(@DiffKey val id: String, val street: String, val city: String)

@Diffable
data class Person(
    val name: String,
    @DiffIgnore val lastSeen: String,
    val address: Address?,
    val addresses: List<Address>,
)
```

`PersonDiff.kt` keeps the `diff` it has and gains `apply` beside it:

```kotlin
public object PersonDiffer : Differ<Person>, Patcher<Person> {
  override fun diff(before: Person, after: Person): Diff = Diff(
    buildList<Change> {
      compareValue("name", before.name, after.name)
      compareNestedNullable("address", before.address, after.address, AddressDiffer)
      compareKeyedList("addresses", "id", before.addresses, after.addresses, AddressDiffer) { it.id }
    },
  )

  override fun apply(before: Person, changes: List<Change>): PatchResult<Person> {
    val grouped = groupByProperty(changes, setOf("name", "address", "addresses"))
    val failures = grouped.unmatched.asFailures("Person")

    val name = patchValue("name", before.name, grouped)
    val address = patchNestedNullable("address", before.address, grouped, AddressDiffer)
    val addresses = patchKeyedList("addresses", before.addresses, grouped, AddressDiffer) { it.id }

    return PatchResult(
      before.copy(
        name = name.value,
        address = address.value,
        addresses = addresses.value,
      ),
      failures + name.failures + address.failures + addresses.failures,
    )
  }
}
```

`lastSeen` appears in neither: ignored properties are not compared and so have nothing to apply,
and `copy` leaves the source value in place. The `setOf(...)` of compared property names is what
turns an unknown path into a reported failure rather than a silent no-op — the one piece of
knowledge the runtime cannot have.

## Risks / Trade-offs

- **Two breaking model changes land at once (D2)** → Both are confined to types nothing outside
  this repository consumes, and both are forced by the round-trip rather than chosen. The mitigation
  is timing: paying now costs a same-session update to the core change's tests; paying later costs a
  released API. The alternative — leaving them — makes sealed swaps and non-string map keys
  permanently unpatchable, which the spec would then have to carve out.

- **Keyed-list ordering is the one genuinely hard algorithm here (D5)** → It is also the one most
  likely to pass a naive test and fail on a real case. Mitigated by computing target state instead
  of replaying operations, by keeping the algorithm in the runtime where it is unit-testable
  directly, and by a round-trip scenario that combines a modification, an addition, a removal and a
  move in a single list rather than testing each alone.

- **A total round-trip is a strong claim, and the spec now makes it** → Every shape gets its own
  scenario, so a gap surfaces as a named failing test rather than as a quiet exception to a general
  promise. The risk is real but bounded: if a shape turns out not to round-trip, that is a spec
  conversation, not a silent narrowing.

- **`apply` roughly doubles generated file size** → Accepted, and bounded by D3: still one runtime
  call per property, no inline loops, no branching cleverness. A generated file stays proportional
  to the class it serves.

- **Patching silently succeeds against the wrong source object (D6)** → The deliberate non-goal,
  and the one place where a caller can misuse the library and get a plausible wrong answer. Stated
  in the spec's non-goals rather than left implicit, and revisitable as an opt-in verifying mode
  once every change kind carries a before-value.

## Migration Plan

`kdiff-sample` is the only consumer and is updated here. Within this repository the breaking model
changes touch the core change's runtime tests (which construct `TypeChanged` and `Segment.Key`
directly) and the sealed generation in the processor.

No published artifacts exist, so there is no external migration. Rollback is reverting the change;
`Differ`, `Diff` and the generated `diff` signature are untouched, so nothing that only compares is
affected either way.

## Open Questions

- Whether to offer diff inversion (`undo`) once a type change carries both values. Deferrable: it
  is additive, changes no requirement here, and is better judged once patching has a user.
- Whether a verifying apply mode is worth the before-values it would require on every change kind.
  Cannot be settled before someone actually applies a diff to the wrong object and says what they
  wanted to happen.
