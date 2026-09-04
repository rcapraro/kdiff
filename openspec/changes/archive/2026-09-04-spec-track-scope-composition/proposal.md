## Why

A code review of `add-change-tracking` found four defects in the tracking runtime, all of them in
behaviour the specs never pinned down. The worst silently *widened* what a tracker reported: passing
a prepared `TrackScope` and stating a `depth` in the same builder block discarded the scope's
properties and fell through to tracking the whole object, so a tracker reported changes its caller
had explicitly scoped out.

They are already fixed, with regression tests in `TrackScopeCompositionSpec`. What is missing is the
contract: nothing in `openspec/specs/change-tracking/spec.md` says how a prepared scope, a property
named at the call site, and a stated depth combine, so the fixes rest on tests alone and the next
person to touch `TrackerBuilder.build` has nothing to check themselves against.

This change writes those rules into the spec. It adds no capability and changes no behaviour.

## What Changes

- **Specs only.** No production code changes. The runtime already behaves as described; this change
  makes the behaviour a stated contract rather than an accident of the implementation.
- **New requirement: how a scope's three sources compose.** Naming a property at the call site wins
  outright over a prepared scope; a stated depth applies *to* what the prepared scope names rather
  than replacing it; and stating a depth never widens a scope to a property it did not name. The last
  clause is the one the defect violated, so it is stated as its own normative sentence.
- **New requirement: naming one property twice takes the widest depth.** `field(p)` followed by
  `under(p)` reported nothing at all, because the first selector won by declaration order. Each
  selector is a request to track something, so the order they are written in must not decide which is
  honoured.
- **Modified requirement: "A tracking scope with no selectors tracks the whole object."** It reads as
  though naming no property always means tracking everything. There are two distinct cases, and the
  library relies on the difference: a scope built by hand that names none wants them all, while a
  scope built from an explicit empty list has none to name — which is what a `@Trackable` class whose
  every compared property is `@TrackIgnore`d declares, and where tracking everything would be the
  opposite of what its author asked for. The requirement is restated to cover both.

**Not BREAKING**, and no annotation semantics change. `@Trackable`, `@TrackIgnore` and `@TrackDepth`
behave exactly as `diff-generation` already specifies, the generated API surface is untouched, and no
consumer must recompile or adapt.

**Affected module: `kdiff-runtime` only**, and only as the subject of the requirements — the code
matching them is already committed. `kdiff-annotations`, `kdiff-processor` and `kdiff-sample` are not
involved.

## Capabilities

### New Capabilities
<!-- None: this change specifies existing behaviour of an existing capability. -->

### Modified Capabilities
- `change-tracking`: adds the rules for composing a prepared scope with call-site selectors and a
  stated depth, adds the precedence for a property named more than once, and restates the
  no-selectors requirement so that a scope naming no property and a scope with no property to name
  are no longer conflated.

## Impact

- `openspec/specs/change-tracking/spec.md`: two requirements added, one restated.
- No source file changes. `kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/TrackScopeCompositionSpec.kt`
  already covers every scenario this change specifies; the verification task is to confirm that
  mapping is complete rather than to write new tests.
- No dependency, Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
