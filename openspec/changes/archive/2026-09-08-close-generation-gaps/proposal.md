## Why

Three ordinary Kotlin shapes defeat the processor today, and each one fails in a way the project's own
rules forbid: a compile error inside generated code, an annotation that silently does nothing, or two
diagnostics that point at each other with no way through.

```
  shape                                   what happens today
  --------------------------------------  ----------------------------------------------------
  val tags: List<String>?                 OrderDiff.kt fails to compile: List<String>? passed
                                          where List<T> is expected. The error names a file the
                                          author did not write.

  @DiffIgnore val note: String            on a class that is not @Diffable: nothing. The author
                                          believes the property is ignored. It is not compared,
                                          because nothing is compared, and nothing says so.

  sealed interface Payment {              @Diffable rejects the object ("only data classes and
    data object Unpaid : Payment          sealed types"), and the sealed parent rejects the
    data class Card(...) : Payment        hierarchy ("every subclass must be @Diffable"). No
  }                                       annotation makes this compile.
```

The first violates *user-facing problems are diagnostics at the offending symbol, never errors in
generated code*. The second violates *an annotation that could do nothing is a compile error* — a rule
the processor already enforces for `@Trackable`, `@TrackIgnore` and `@TrackDepth`, and not for the
three comparison annotations. The third is a dead end for the most idiomatic way Kotlin models a
state with a payload-free case.

None of the three is a design limit. Nullable collections have an obvious semantics the library already
uses for nullable nested types; the comparison annotations need the check the tracking annotations
already have; and a singleton has nothing to compare and nothing to rebuild, so a sealed differ can
dispatch on it without a differ of its own.

## What Changes

**A nullable collection property is compared and rebuilt.** `List<E>?`, `Set<E>?` and `Map<K, V>?`
properties follow the rule nullable nested types already follow: a null on either side is one
`ValueChanged` at the property carrying the two sides, null on both sides is nothing, and two non-null
collections are compared by the rules for that collection. The same holds for a hand-written differ,
whose `list`, `keyedList`, `set` and `map` builders accept a nullable property as `nested` already
does. Applying mirrors it: a change *at* the property sets it wholesale, changes beneath a null source
are reported as `NothingBeneathNull`, and a present collection is rebuilt as today.

**A collection whose elements are nullable and reached through a differ is a compile error.**
`List<Address?>` and `Map<K, Address?>`, where `Address` is `@Diffable`, are rejected at the property
with a message naming the element type and the two ways out. Today they also fail inside generated
code. `List<String?>` and `Set<Address?>` are unaffected: an element compared as a value or by
membership may be null already, and stays so.

**BREAKING — a comparison annotation on a property of a class that is not `@Diffable` is a compile
error.** `@DiffKey`, `@DiffIgnore` and `@DiffWith` are honoured only where a differ is generated to
honour them; anywhere else they now fail the build naming the property and the missing `@Diffable`,
exactly as `@TrackIgnore` on a class that is not `@Trackable` does today. `@DiffWith` together with
`@DiffIgnore` on one property is rejected as a conflict, since an ignored property is never compared.
A module carrying a stray annotation compiled before and does not now; that is the point, and the
message says what to do.

**An `object` subclass of a `@Diffable` sealed type needs no annotation and is dispatched on.** A
sealed differ's `when` gains a branch per `object` or `data object` subclass that reports nothing when
both sides are that singleton, and a subclass swap to or from it reports the type change and the
parent's declared properties exactly as a swap between two data classes does. Applying to a singleton
returns it unchanged; any change addressed beneath it is reported as addressing an unknown property,
since no comparison could have produced one. `@Diffable` placed *on* the object stays an error — there
is nothing to generate — and the message now says that the object needs no annotation of its own.

**Not in scope**

- Nullable elements reached through a differ. Supporting `List<Address?>` means per-element null
  handling in every collection helper and a keyed list that must still refuse a null element, because a
  key cannot be read off one. This change makes the shape a diagnostic instead of a generated-code
  error; supporting it is a separate, additive change if anyone needs it.
- Inherited properties. A property a data class inherits and does not override is not compared today
  and is not after this change; that is a documentation gap, not a generation gap, and belongs with the
  docs work.
- Any change to the change vocabulary, to `PatchFailure.Reason`, or to tracking. Depth counts
  property steps and a nullable collection introduces none.

## Capabilities

### New Capabilities

None. Every item closes a gap in a capability that already exists.

### Modified Capabilities

- `diff-generation`:
  - ADDED *A nullable collection property reports a null on either side as a value change* — the
    comparison rule for `List<E>?`, `Set<E>?` and `Map<K, V>?`, generated and hand-written alike.
  - ADDED *A collection whose elements are nullable and compared by a differ is a compile error*.
  - ADDED *A comparison annotation with nothing to configure is a compile error* — the counterpart of
    the existing *A tracking annotation with nothing to configure is a compile error*.
  - MODIFIED *An annotated sealed type dispatches on the runtime subclass* — an `object` subclass is
    dispatched on without an annotation of its own.
  - MODIFIED *A sealed type with an unannotated subclass is a compile error* — an `object` subclass is
    exempt, because there is nothing an annotation on it could configure.
  - MODIFIED *Annotating an unsupported declaration is a compile error* — the rejection of `@Diffable`
    on an object now also states that an object in a sealed hierarchy needs no annotation.
- `diff-application`:
  - ADDED *A nullable collection property is rebuilt when present and set wholesale when it appears or
    disappears* — the round-trip over a nullable collection, and the failure reported beneath a null
    one.
  - ADDED *A sealed singleton is applied by returning it* — the round-trip over a swap to or from an
    `object` subclass, and what a foreign change beneath a singleton reports.

`change-tracking` needs no delta. A nullable collection adds no property step, and a singleton
subclass declares no property a scope could name.

## Impact

- **Modules**: `kdiff-runtime` (the four collection compare helpers accept nullable sides; two new
  helpers, `patchNullable` and `patchSingleton`; `DifferBuilder` widens four parameter types),
  `kdiff-processor` (nullability-aware resolution, three new diagnostics and one amended message,
  singleton branches in the sealed emitters), `kdiff-sample` (gains a nullable collection and an
  `object` payment case so the integration test covers both), docs.
- **Public runtime API**: two additions, `patchNullable` and `patchSingleton`, so `updateKotlinAbi`
  runs once and its diff is those two entries. Widening a compare helper's `List<T>` parameter to
  `List<T>?` changes Kotlin metadata only — the JVM signature is unchanged, so no existing caller
  breaks and the ABI dump does not move for it. The `DifferBuilder` widenings are the same: a
  `KProperty1<T, List<E>>` is a `KProperty1<T, List<E>?>`, so every existing call site compiles
  unchanged.
- **Generated API surface**: the *shape* of a generated `apply` changes only for a property that could
  not compile before, so every class that compiles today regenerates byte-identical. A sealed type
  gains branches only when it has an `object` subclass, which was previously rejected. No generated
  type is added or removed.
- **Annotation semantics**: `@Diffable` on a sealed type no longer requires its `object` subclasses to
  be annotated. `@DiffKey`, `@DiffIgnore` and `@DiffWith` on a property of an unannotated class fail
  the build. Nothing that compiled and generated code before means anything different afterwards.
- **Hand-written parity**: the DSL accepts a nullable collection property through the same builders,
  and a hand-written differ for a sealed type already handles an undeclared singleton subtype by the
  existing *undeclared subtype* rule — so parity holds for both, and a scenario pins each.
- **Docs**: `errors.md` gains three verbatim messages and amends one; `diffing.md` states the nullable
  collection rule and the `object` rule under *Sealed types*; `annotations.md`'s rejection table gains
  three rows; `hand-written.md`'s builder table notes nullable acceptance; the FAQ answers "why can I
  not annotate my `data object`?" in one line pointing at the rule.
- **Dependencies**: none added. No version bumped.
