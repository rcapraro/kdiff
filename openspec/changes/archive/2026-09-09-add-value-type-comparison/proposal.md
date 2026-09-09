## Why

The processor's idea of a value is primitives, `String`, unsigned types and enums. Everything else is
either `@Diffable`, a collection of those, or a compile error pointing at `@DiffWith`. That rule is
right for a data class with five fields — comparing it as one opaque blob loses the diff — and wrong
for the types a domain model is actually full of:

```
  val total: BigDecimal          val placedAt: Instant          val id: UUID
  val due: LocalDate             val timeout: Duration          @JvmInline value class Email(...)
```

Each of these *is* a value. None can be annotated. Today each costs the author an `object` implementing
`Differ` and a `@DiffWith` on every property that holds one, to express "compare it by equality" — the
one comparison the library already knows how to do. The tutorial's own domain avoids inline value
classes and says so in a comment, which is the library bending the model rather than the other way
round.

There is a second asymmetry hiding behind the first. The hand-written route already lets a caller
compare anything as one value: `field(Order::billing)` compiles and reports one `ValueChanged` at
`billing` however many of the address's properties moved. The annotation route has no way to say the
same thing. The FAQ's answer, "custom comparison is per property, not per type", is a description of
the gap rather than a reason for it.

## What Changes

Three additions, each independently useful and together closing the gap. Nothing about how a value is
compared changes: a value property still reports one `ValueChanged` carrying both sides, and is still
applied by setting it.

**The built-in value set grows to the immutable JDK and Kotlin standard library value types.**
`BigDecimal`, `BigInteger`, `UUID`, `Currency`, `Locale`, `URI`, every `java.time` value type
(`Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
`ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`),
`kotlin.time.Instant` and `kotlin.uuid.Uuid` are compared by equality wherever they appear — as a
property, as a list element, as a map value. The criterion is stated in the docs so the list can be
read rather than remembered: immutable, equal by value, from the JDK or the Kotlin standard library.
`BigDecimal` gets one caveat in writing: equality is `equals`, so `10` and `10.00` differ, and a
numeric comparison still wants `@DiffWith`.

**An inline `value class` is a value.** A class declared `value class` is compared by equality wherever
it appears, without an annotation. It cannot be a data class, its equality is its single property's,
and it exists to be a value; making the author say so again is noise. `kotlin.time.Duration` is one, so
it needs no place on the list above.

**`@DiffAsValue` declares that a type, or one property, compares as a single value.** A new annotation
in `kdiff-annotations`, honoured on a class and on a property:

- On a class: every property of that type, every list element of that type and every map value of that
  type is compared by equality, in every `@Diffable` class that reaches it. This is the "per type"
  declaration the FAQ says does not exist, made as a declaration on the type rather than a registry —
  and made only for a type the author owns, which is the shape `@Diffable` already has.
- On a property: that property alone is compared by equality whatever its type. `@DiffAsValue val
  billing: Address` reports one change at `billing`; `@DiffAsValue val tags: List<String>` reports one
  change at `tags`. It is the annotation counterpart of the DSL's `field`, so the two routes can once
  again describe the same model.

An annotation that could do nothing is a compile error, on the terms the project already applies:
`@DiffAsValue` on an enum, a `value class`, a listed type or a property whose type is already a value
is rejected as having no effect; on a class that is also `@Diffable`, or on a property that also
carries `@DiffWith` or `@DiffIgnore`, it is rejected as a conflict; on a property of a class that is not
`@Diffable` it is rejected as the other three comparison annotations are.

**The "cannot compare" diagnostic names three ways out instead of two.** `@Diffable` on the type,
`@DiffAsValue` on the property, or `@DiffWith` — so an author meeting the error for the first time
learns the cheapest fix.

**Not in scope**

- A registry, a processor option, or any way to declare a comparison for a type one does not own
  beyond `@DiffWith` on the property. The list above is the library's judgement about the standard
  library; everything else is a declaration on a type or a property.
- Changing how the DSL's `field` works. It already is the hand-written half of this change.
- Any change to the `Change` vocabulary, to patching, or to tracking.

## Capabilities

### New Capabilities

None. Every item widens what an existing capability accepts.

### Modified Capabilities

- `diff-generation`:
  - MODIFIED *Value properties are compared by equality* — the definition of a value widens to the
    listed standard-library types and inline value classes.
  - ADDED *A type or a property can be declared to compare as one value* — `@DiffAsValue`, its two
    targets, and every way it is rejected.
  - MODIFIED *A property kdiff cannot compare is a compile error* — the message names the third way out.
- `diff-application`:
  - ADDED *A property compared as a value is applied as a value* — the round-trip over the widened
    value set and over a `@DiffAsValue` property, which is set wholesale.

`change-tracking` needs no delta. A value lies at depth 1 and has nothing beneath it, which is already
how a value property is tracked.

## Impact

- **Modules**: `kdiff-annotations` (one new annotation — the ABI dump gains one entry),
  `kdiff-processor` (the value-type test, `@DiffAsValue` resolution, five diagnostics and one amended
  message), `kdiff-sample` (gains a `BigDecimal`, an `Instant`, a `value class` and a `@DiffAsValue`
  type so the integration test covers all three routes), `kdiff-tutorial` (the comment explaining why
  identifiers are not value classes no longer holds and is revised), docs. `kdiff-runtime` is
  untouched: a value is compared by `compareValue` and applied by `patchValue`, both of which exist.
- **Generated API surface**: unchanged. A property that compiles today generates the same code. A
  property that could not compile today generates a `compareValue` / `patchValue` pair, the shape every
  generated file already contains.
- **Annotation semantics**: nothing that compiled before means anything different afterwards. A
  previously rejected property compiles; a `@DiffAsValue` that could do nothing is a new compile error,
  but the annotation is new, so no existing code carries it.
- **Not breaking.** No signature moves and no message an existing test asserts on changes in substance
  — the amended "cannot compare" message still contains `@DiffWith`, which is the substring
  `DiagnosticSpec` checks. `errors.md` quotes it verbatim and is updated in the same commit.
- **Ordering**: independent of `close-generation-gaps`, but written to follow it. That change adds the
  *comparison annotation with nothing to configure* rule for `@DiffKey`, `@DiffIgnore` and `@DiffWith`;
  this one carries the same scenario for `@DiffAsValue` inside its own new requirement, so either order
  archives cleanly and the processor adds one constant to the same loop.
- **Hand-written parity**: `field` is the counterpart of both the widened value set and `@DiffAsValue`,
  so a hand-written differ can already describe every shape this change admits. A parity scenario pins
  it.
- **Docs**: `annotations.md` gains a `@DiffAsValue` section and the value-type list; `diffing.md`'s
  *Values, enums and nullables* states the widened definition and the `BigDecimal` caveat;
  `architecture.md`'s *Custom comparison is per property, not per type* bullet is rewritten, because the
  per-type half is now false; the FAQ's `BigDecimal` question gets a new answer; `errors.md` gains five
  messages and amends two; `how-to.md`'s *Compare a value your own way* keeps its recipe, which is
  still the route to numeric equality.
- **Dependencies**: none added. No version bumped.
