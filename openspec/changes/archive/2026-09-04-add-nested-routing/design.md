## Context

See proposal.md — Why. What matters for the approach is the shape of the code being extended.

`ChangeRoutes.dispatch` groups changes by `change.path.rootName()`, the name of the path's first
segment when that segment is a `Segment.Field`. Everything routing knows about location comes from
that one call, so routing sees exactly one level. `Segment.Key` lookup inside `KeyedElementRoutes`
reads `segments[1]`, one position past the property — the same assumption expressed as an index.

Two things in the existing code already have the shape a frame needs:

- `ElementRoutes.dispatch` returns the changes it had no shape for, and `ChangeRoutes.dispatch` sends
  what comes back to the fallback. A frame is a third participant in that same handshake.
- `Change.sides()` in `Select.kt` is an internal `when` over the five variants, exhaustive so a sixth
  variant fails to compile rather than being dropped. Re-rooting a change needs the same construct.

`DifferBuilder.nested(property, differ)` is declared `<V : Any> nested(property: KProperty1<T, V?>, …)`,
which is how one call already serves a nullable and a non-null property: a property reference is
covariant in the value it reads.

## Goals / Non-Goals

**Goals:**

- One dispatch implementation. A frame reuses `ChangeRoutes`' own dispatch rather than growing a
  second one that has to be kept in step with it.
- A frame is observationally identical to routing the nested value's own diff, so the two are
  interchangeable and the workaround they replace can be deleted without behaviour moving.
- One fallback at the outermost routing sees every change no handler at any depth named.

**Non-Goals:**

- Routing map entries by key (`onEach` over a `Map` property). Reachable through a frame's change
  list by hand today; not part of this change.
- Making the descent operation public API. It has exactly one caller.
- Rejecting a frame over a collection property at compile time.
- Any change to comparison, patching or tracking. A frame reads a diff that already exists.

## Decisions

### Re-root the changes rather than carry a depth offset

A frame filters the enclosing routing's changes down to those under its property, drops the leading
segment from each, and hands the result to a fresh `ChangeRoutes<V>` — the same class, dispatching
the same way.

The alternative is to leave paths alone and thread a depth offset through dispatch, so `rootName`
and the `Segment.Key` lookup read at `segments[depth]` and `segments[depth + 1]`. It allocates
nothing, and it was rejected on two counts. It hands handlers absolute paths, which breaks the
equivalence the spec requires and makes a frame's handlers subtly different from the same handlers
used at the top level. And it spreads the offset across every read of a path, so every future route
kind has to remember it — the kind of arithmetic that widens a scope silently, which is the failure
direction the tracking code already warns about.

Re-rooting allocates one `Change` per change per frame. Only changes already filtered into the frame
are re-rooted, diffs are small, and routing runs once per decision rather than in a loop.

### The descent is internal, and shaped like `Change.sides()`

`FieldPath` gains a member that drops its first segment, and an internal `when` over the five
variants applies it to a change. Both stay internal: `prefixedWith` is public because lifting a
nested differ's paths is part of the contract between a differ and its caller, whereas the descent
has one caller inside routing and no external contract. Adding it as an abstraction for the second
real caller, not the first anticipated one.

The `when` is exhaustive by construction, so a sixth `Change` variant fails to compile here — the
same guarantee `sides()` gives, in the same style, for the same reason.

### Unhandled changes propagate outward, at their original paths

A frame consumes what its own fallback names and returns the rest to the enclosing routing, which
applies its own rules. `internal fun ChangeRoutes.dispatch` changes from returning `Unit` to
returning the changes it did not account for; the public `Diff.route` entry point discards that
return and keeps its signature.

The returned changes are the *original* instances, not the re-rooted ones, so an enclosing fallback
reads the path it would have read without the frame. Dispatch filters rather than copies, and
preserves order, so what a frame gets back is an order-preserving subsequence of what it passed in:
one cursor walking the re-rooted list and the original list in step restores every change in linear
time, without a lookup structure. If either invariant broke, the walk runs off the end of the list and
throws rather than pairing a change with the wrong original.

Rationale: a caller who pins `otherwise` empty as a safety net — the reason this change exists — needs
one net, not one per nesting level. Consuming silently inside the frame would give every level its own
blind spot. This also matches what `onEach` already does with a change it has no shape for.

### `under` is not inline and needs no `reified`

```kotlin
public fun <V : Any> under(property: KProperty1<T, V?>, block: ChangeRoutes<V>.() -> Unit)
```

`onEach` is `inline` with `reified` type parameters because it needs `E::class` and `K::class` for
the casts it makes safe. A frame casts nothing: it names a property and dispatches paths. So `under`
stays a plain function and adds nothing to the `@PublishedApi` surface.

`KProperty1<T, V?>` with `V : Any` copies `nested`'s signature exactly, so one call serves a nullable
nested value object and a non-null one — the same reason given there.

### API shape

The reviewable surface for this change is the call site, not a generated file; nothing is generated
(see below).

```kotlin
diff.route<Person> {
    under(Person::identity) {
        under(CivilIdentity::name) {
            on(FullName::given)  { events += GivenNameCorrected(id, …) }
            on(FullName::family) { events += FamilyNameChanged(id, …) }
        }
        on(CivilIdentity::civilStatus) { events += CivilStatusCorrected(id, …) }
    }

    under(Person::fiscal) {
        on(FiscalProfile::fatca) { events += FatcaDeclarationChanged(id, …) }
        onEach(FiscalProfile::crs, CrsRecord::country) {
            added   { events += CrsRecordAdded(id, it) }
            removed { events += CrsRecordRemoved(id, it) }
            changed { country -> events += CrsRecordChanged(id, country) }
        }
    }

    otherwise { unrouted += Diff(it) }   // sees anything unnamed at any depth
}
```

### Type resolution

- **Nullability**: covered by `<V : Any>` over `KProperty1<T, V?>`. A nullable nested value that
  appears or disappears is reported by `nested` as a value change *at* the property, which is the
  change a frame has nothing to dispatch and treats as unhandled.
- **Generics**: `under` is generic in the framed type; no type argument is reified and no reflection
  library is involved, consistent with the rest of routing.
- **Collections**: a frame over a collection property receives paths whose first segment is an index
  or a key, so `rootName()` is null and every change propagates outward unhandled. That is the honest
  outcome rather than an error, and `onEach` is the call that expresses the intent. Documented, not
  enforced.
- **Maps**: unchanged — map entry changes reach a frame's change list and are read by hand. Out of
  scope, and the reason is recorded in Non-Goals.
- **Enums and other leaves**: a framed property whose type has no properties to name behaves like the
  nullable case — changes sit at the property, and are unhandled.
- **Nested `@Diffable` types**: a frame's child type may be compared by a generated differ or a
  hand-written one; the parity scenario in the spec pins that the routing cannot tell.

### Tracking scope stays one authority

A frame does not filter. The diff it dispatches was already filtered by whatever `trackedDiff` the
caller used, so scope is decided once, at the outermost comparison. This is the substantive advantage
over re-diffing a child by hand, where the child comparison silently escapes the parent's scope and
the caller has to thread a second scope to get it back.

### Nothing lives in the processor

`kdiff-runtime` gains all of it. No file is generated, no `Dependencies(aggregating = …)` declaration
is involved, and incremental processing is unaffected — the processor is not touched, so
`kdiff-sample`'s generated output is byte-identical. Routing reads a `Diff` at runtime and has never
appeared in generated code; keeping it that way is the same rule the runtime helpers follow, so a fix
here ships as a dependency bump rather than a recompile of every consumer.

## Risks / Trade-offs

- **Pairing re-rooted changes back to their originals relies on dispatch filtering *in order*, rather
  than copying or reordering** → Both hold today, in `ChangeRoutes` and `ElementRoutes` alike. Pin them
  with the spec scenario requiring a propagated change to carry its original path, and with a case
  putting several changes under one frame where only some are handled — a single unhandled change
  cannot tell a correct walk from a broken one. A future change that copies or reorders then fails a
  test instead of quietly relocating a fallback's changes.
- **A frame that declares no handler and no fallback routes nothing and says nothing** → It claims the
  property, so its changes stop reaching the enclosing fallback only if it handles them; since it
  handles none, they all propagate and the outermost fallback still sees them. The failure is visible
  where it matters.
- **One extra `Change` allocation per change per frame** → Bounded by diff size times nesting depth,
  both small. Accepted for parity.
- **Deep frames read as deeply indented blocks** → A frame per level mirrors the model's own nesting,
  which is the point; nothing forces a caller to frame a level it does not route.
- **`internal fun dispatch` changes its return type** → Internal to `kdiff-runtime`; `Diff.route`
  keeps its signature and no consumer sees it.

## Migration Plan

Purely additive; no migration. An existing routing that declares no frame takes the same path through
the same dispatch and behaves identically, which the existing routing scenarios continue to pin.
Rollback is a revert — nothing is persisted, generated or serialised by this change.

## Open Questions

- Should the path descent become public API? Deferred until a caller outside routing wants it; the
  internal form can be published later without changing behaviour.
- Should `onEach` gain a form for `Map` properties? Deferred — a frame's change list reaches map
  entries today, and the consumer driving this change avoids needing it.
