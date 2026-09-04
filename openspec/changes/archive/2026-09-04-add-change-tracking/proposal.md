## Why

kdiff answers a single question — *how do these two instances differ?* — and leaves the caller to
ask it. Anyone watching an evolving value has to keep the previous instance by hand, remember to
call `diff`, and then sift the flat `List<Change>` down to the fields they actually care about. That
bookkeeping is the same in every codebase that does it, and getting it wrong is silent: a forgotten
baseline reports nothing, and an unfiltered change list reports everything.

This change adds the reactive half, and adds it the way kdiff already does things. Comparison is
reachable two ways — `@Diffable` for a class you own, the `differ { }` DSL for one you do not — and
a hand-written differ is required to be indistinguishable from a generated one. Tracking follows
that same rule: a tracking scope is declarable by annotation *or* built in ordinary Kotlin, and the
two are the same type, consumed by the same tracker.

## What Changes

- **New: `@Trackable`, `@TrackIgnore`, `@TrackDepth` annotations** (`kdiff-annotations`), shaped
  exactly like the comparison annotations they sit beside. `@Trackable` on a class opts the class in
  and tracks every compared property, as `@Diffable` opts a class into comparison. `@TrackIgnore`
  opts a property out, as `@DiffIgnore` does. `@TrackDepth` sets one property's depth, as `@DiffKey`
  and `@DiffWith` qualify one property. There is no property-level opt-in, for the same reason
  `@Diffable` has none: the class opts in, properties opt out.
- **New: `TrackScope<T>` and the `trackScope { }` DSL** (`kdiff-runtime`). A scope names the tracked
  fields and their depths. `trackScope { field(Order::total); under(Order::billing) }` builds one by
  hand from typed property references, exactly as `differ { }` builds a differ from them — and a
  hand-written scope is indistinguishable from a generated one to the tracker that consumes it.
- **New: the `Tracked<T>` capability on the generated declaration.** A `@Trackable` class's
  generated object gains `Tracked<T>`, exposing its declared scope as the same `TrackScope<T>` the
  DSL produces. Diff, patch and track are therefore all reached through one declaration and one
  import, which is what `diff-application` already requires of patching.
- **New: `Tracker<T>` and the `tracker { }` DSL** (`kdiff-runtime`). `tracker(OrderDiffer, order) { }`
  holds a baseline; `update(next)` reports the matching changes, then adopts `next`. The builder
  declares the scope inline or takes a prebuilt one, and registers the callbacks.
- **New: two callback shapes.** `onFieldChange(path, before, after)` fires once per matching change;
  `onChange(before, after, changes)` fires once per update with the whole matching set.
- **New: depth.** `depth` bounds how far into nested objects a change may lie and still be reported.
- **New: five compile-time diagnostics** for annotation combinations the library cannot honour,
  each mirroring an existing `@Diff*` diagnostic (see the `diff-generation` delta).

**Not BREAKING.** The generated API surface changes additively: a class that does not adopt
`@Trackable` gets the file it gets today, byte for byte. `@Diffable`, `@DiffKey`, `@DiffIgnore` and
`@DiffWith` keep their exact meaning, and no consumer must recompile or adapt.

### How the three capabilities fit together

The rule this change follows, and which the design states as a decision:

| capability | interface | from an annotation | written by hand |
|---|---|---|---|
| compare | `Differ<T>` | `@Diffable` | `differ { }` builder |
| apply | `Patcher<T>` | `@Diffable`, same object | `object : Patcher<T>` — no builder |
| track | `Tracked<T>` | `@Trackable`, same object | `trackScope { }` builder |

A capability gets a DSL builder exactly when it only needs to *name* properties. `differ { }` reads
properties, so it can be built generically. Patching must *construct* the property's owner, which a
builder cannot know how to do — hence `Patcher`'s deliberate absence of one, and hence
`diff-application`'s requirement that a compare-only differ makes a property unpatchable. Tracking
only names properties and depths, so it lands on the `differ { }` side of that line and gets a full
builder.

That places tracking's escape hatch exactly where comparison's already is: a type whose source
cannot be annotated is compared by a hand-written differ and tracked by a hand-written scope, with
no annotation anywhere in the picture.

### Track and patch compose

`Tracker.update` returns a `Diff` — the same type `Patcher.apply` consumes. So a tracker's output
feeds a patcher directly, and a scope that tracks everything reproduces the target exactly as
`diff-application` requires of any diff. A *narrowed* scope yields a diff that reproduces only the
tracked part of the target, which makes selective propagation a property of the design rather than
something a caller has to assemble. Both are recorded as requirements.

### Two assumptions worth calling out

1. **Depth filters; it does not roll up.** A change at `billing.city` under `depth = 1` is not
   delivered at all, rather than being reported as a change *at* `billing`. Rolling up would need a
   new `Change` variant — and the change vocabulary is deliberately sealed and closed — plus the
   nested object's before and after values, which cannot be read without reflection.
2. **A `Moved` change reaches `onFieldChange` as its two indices.** `from` becomes `before` and `to`
   becomes `after`. They are genuinely the old and new position, and the alternative — dropping
   moves from the per-field callback — loses information silently.

## Capabilities

### New Capabilities
- `change-tracking`: what a tracker observes and reports — its baseline lifecycle, how a scope is
  declared by hand or by annotation and how the two resolve, what depth means, what each callback
  receives for each kind of change, and how a tracker's output composes with applying.

### Modified Capabilities
- `diff-generation`: `@Trackable`, `@TrackIgnore` and `@TrackDepth` become part of the
  annotated-input-to-generated-API contract. A `@Trackable` class's generated declaration gains
  `Tracked<T>` alongside the comparison and application it already carries, and the annotation
  combinations the library cannot honour become compile errors.

## Impact

- `kdiff-annotations`: one new file (`Tracking.kt`) — three annotations and the unlimited-depth
  constant. Still no dependencies.
- `kdiff-runtime`: new public API (`TrackScope`, `trackScope { }`, `TrackScopeBuilder`,
  `TrackedField`, `Tracked`, `Tracker`, `tracker { }`, `TrackerBuilder`) plus an internal filter.
  Depends only on the existing `Differ`, `Diff`, `Change`, `FieldPath` and `Segment` types and the
  Kotlin stdlib — no new dependency, no reflection. `trackScope { }` joins `differ { }` in `Dsl.kt`'s
  role as the hand-written half of the library.
- `kdiff-processor`: resolves the three new annotations, emits `Tracked<T>` on the existing generated
  object when a class is `@Trackable`, and reports the new diagnostics. Incremental processing is
  unaffected: the emitted scope depends only on the annotated file, so generated files stay
  `Dependencies(aggregating = false, originatingFile)`.
- `kdiff-sample`: `Order` adopts `@Trackable`, and the sample gains a tracking test that exercises
  both routes — the generated scope and a hand-written scope over the existing hand-written
  `MoneyDiffer` — plus the track-then-patch round trip alongside the existing `RoundTripSpec`.
- No dependency, Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves in this change.
