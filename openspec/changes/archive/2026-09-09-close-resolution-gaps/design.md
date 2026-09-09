## Context

See `proposal.md` — *Why*. What the code looks like where each fix lands.

`resolve(property)` in `DiffProcessor.kt` is the ordered table its own KDoc describes — an earlier
change's D3, not this one's — and the single place a property's comparison is decided. Both directions
read it: `dataClassBody` and `sealedBody` for comparison, `resolvedProperties` for application. Its
first four rows read annotations off `property` directly — `diffWithTarget(property)`,
`property.hasAnnotation(DIFF_WITH)`, `property.hasAnnotation(DIFF_AS_VALUE)` — and the fifth reads the
property's *type*: `if (type.isValueType()) return Comparison.ByValue(type.declaredValueSources())`.

Every `Comparison` carries `sources: List<KSFile>`, documented as "files this comparison reads, which
must become originating dependencies of the output". `resolve`'s callers drain them into a `sources` set
that becomes `Dependencies(aggregating = false, *sources)`. That mechanism is right as far as it goes;
what is missing is one of its inputs, and — for what an annotation search reads — a place to put it.

`isValueType()` is `declaration.isIntrinsicValueType() || declaration.hasAnnotation(DIFF_AS_VALUE)`, and
`isIntrinsicValueType()` is `qualifiedName in VALUE_TYPES || classKind == ENUM_CLASS ||
isInlineValueClass()`. `declaredValueSources()` filters on `hasAnnotation(DIFF_AS_VALUE)` alone.

`comparableProperties()` is `getDeclaredProperties().filterNot { it.hasAnnotation(DIFF_IGNORE) }`, so
`@DiffIgnore` is applied by a filter *before* `resolve` is ever called. A subclass's `override val` is a
declared property, and Kotlin puts none of the overridden declaration's annotations on it.

## Goals / Non-Goals

**Goals**

- A comparison decision cannot outlive the declaration it was read from.
- One property is compared one way, whichever branch of a sealed dispatch reaches it.
- One place decides which declaration a property's comparison annotations come from, so the two call
  sites cannot drift.
- Comparison and application stay symmetric for free, by both reading the same resolution.

**Non-Goals**

- Any new annotation, or any new diagnostic for a shape that now works.
- `aggregating = true` anywhere. Both fixes add a file to a non-aggregating set.
- Inheriting the tracking annotations, or `@DiffKey`. Neither is read off a property's overridden
  declaration; see D5.

## Decisions

### D1 — `declaredValueSources()` returns the file of any user-declared value type

```kotlin
internal fun KSType.declaredValueSources(): List<KSFile> =
    declarationOrNull()?.takeIf { it.isIntrinsicallyDeclaredValue() || it.hasAnnotation(DIFF_AS_VALUE) }
        ?.file().orEmpty()
```

where the new predicate is `classKind == ENUM_CLASS || isInlineValueClass()` — that is,
`isIntrinsicValueType()` minus the `VALUE_TYPES` list. The split is the point: a `BigDecimal` is a value
because the JDK declares it and the author cannot edit it, so its file is neither available nor
interesting. An enum and a `value class` are declarations in the author's module, and editing one has to
regenerate whatever read it.

Splitting `isIntrinsicValueType()` rather than testing the negation of `VALUE_TYPES` keeps the reason in
the name: the question is "did the author declare this", not "is this absent from a list".

Alternative — record the file for every value type including the standard-library ones — rejected.
`KSFile` for a class from a compiled dependency is null anyway, so it would add a null-safe call that can
never fire and imply a dependency on a JDK file that cannot change within a build.

### D2 — One function decides which declaration carries a property's comparison annotations

```kotlin
/**
 * The declaration a property's comparison annotations are read from: itself when it carries one, and
 * the property it overrides otherwise.
 *
 * Kotlin does not inherit annotations onto an `override`, so a sealed parent's `@DiffAsValue` reaches
 * the subclass differ only if it is looked for. Resolved once and passed around, because
 * `findOverridee()` walks the hierarchy.
 */
internal fun KSPropertyDeclaration.comparisonDeclaration(): KSPropertyDeclaration
```

It returns `this` when `this` carries any of `@DiffWith`, `@DiffAsValue` or `@DiffIgnore`, and otherwise
follows `findOverridee()` — repeating while the overridee carries none, so a three-level hierarchy
resolves. It returns `this` when there is no overridee.

**All three annotations are taken from one declaration, never merged.** An override carrying
`@DiffIgnore` while its parent carries `@DiffAsValue` means the override's word is final — not a
property that is both a value and ignored, which is a conflict the processor already rejects when both
are written on one declaration. Choosing the declaration rather than the annotation is what keeps that
conflict unreachable through inheritance.

`resolve(property)` then reads its annotations off `property.comparisonDeclaration()` and its type off
`property.type` — the type always comes from the override, which is the property actually being
compared. `comparableProperties()` filters on the same function, so `@DiffIgnore` is inherited by the
filter that applies it.

Alternative — read the parent's properties in the subclass by walking `getAllProperties()` — rejected:
it would change which properties a subclass compares and in what order, and the override is already the
right declaration for everything except its annotations.

### D3 — The unit is the annotation search, not the declaration it settles on

An annotation read from another file is a declaration the output depends on, exactly as in D1. The rule
is *record the declaration you read*, and reading includes reading an **absence** — which is what makes
the obvious version of this decision wrong, in two independent ways.

**What gets recorded.** Recording only what `comparisonDeclaration()` settles on leaves two holes, both
of them the staleness D1 exists to prevent:

- A property excluded by an inherited `@DiffIgnore` is filtered out before resolution, so it produces
  no `Comparison` at all and nothing carries its files. Removing `@DiffIgnore` from the parent would
  never regenerate the subclass's differ.
- A property annotated nowhere in its chain settles on itself, so nothing else is recorded. *Adding*
  `@DiffAsValue` to the parent would never reach the subclass's differ.

So `comparisonChain()` returns the property and each property it overrides, stopping at the first that
carries a comparison annotation — nothing above that is read — and `comparisonSources()` is that
chain's files. `generate()` drains it for **every declared property**, not the compared ones, which is
the only place that sees the excluded ones. The per-`Comparison` route is then unnecessary and is not
taken: `declaredValue` and `diffWithTarget` carry only the files of the type or differ they name, as
they did before this change.

**How inherited-ness is told.** By identity, `declared !== property`, and never by the source list
being non-empty. `file()` is `listOfNotNull(containingFile)`, and **`containingFile` is null for a
declaration KSP read from a class file**: a shared module may depend on `kdiff-annotations` without
applying the processor, and its declarations then reach a consumer as class files with no file of their
own. Conflating the two reports a local override for an annotation it does not carry, and refuses it —
leaving the consumer no differ over an upstream declaration it cannot edit. The flag is a flag; the
file list stays a file list.

D1 and this are the same rule, and it was missing at two of the four kinds of declaration a comparison
reads: the property's own type, for an enum or a `value class` (D1), and the annotation chain (here). A
nested `@Diffable` type and a `@DiffWith` target already recorded theirs, which is why those two are
where the rule was visible enough to copy.

### D4 — Which diagnostics read the annotation as written, and which as it takes effect

The "annotation with nothing to configure" diagnostics exist to catch an annotation whose author
believed it did something, so they read the annotations as **written**. An inherited `@DiffAsValue`
arriving at a subclass whose property type is already a value must not be reported against a subclass
that wrote nothing: one mistake in the parent would otherwise be reported once for every subclass
overriding the property, none of them the line to edit.

Three places depart from that, and they do not depart the same way.

**`reportsHonourableTrackingAnnotations` reads the annotation as it takes effect.** Its `@DiffIgnore`
check asks about effect rather than authorship: `@TrackDepth` on a property that produces no changes can
never fire, and after D2 a subclass override can inherit the exclusion that silences it. So that one
check reads `comparisonDeclaration()`. Its message already names the property and both annotations, and
needs no new wording.

**`declaredValue` suppresses an inherited rejection outright.** Its refusal is a courtesy about
redundancy: `@DiffAsValue` on something already a value changes nothing, and the property compares as a
value either way. So an inherited one is never reported and never fails a build — there is nothing to
salvage by refusing, because the outcome is already what the annotation asked for.

**`diffWithTarget` suppresses only what someone else will say.** Its refusal is a genuine inability: a
`@DiffWith` naming an unusable differ leaves the property uncomparable, and returning nothing without
saying why is the silent fallback the processor conventions forbid. So the message is withheld only when
the declaration carrying it is in this compilation, where its own class's resolution gives it once and
at the right line. A declaration from a class file is reported by nobody, so it is reported against the
override — the only line the consumer can act on.

### D5 — Why only the three comparison annotations

`@DiffKey` is read off a collection's *element* type, never off the property holding the collection, so
a property that overrides another has no key declaration to inherit. `@Trackable`, `@TrackIgnore` and
`@TrackDepth` describe a scope belonging to the annotated class; a subclass declares its own scope or
none, and inheriting a parent's tracking would widen a scope, which is the failure direction the
tracking design treats as dangerous.

### D6 — Sequencing against `publish-to-maven-central`

Independent. That change touches publishing configuration and no processor source; neither ordering
needs a third change.

## Risks / Trade-offs

**The behaviour change is silent for the author who was relying on the old paths.** A consumer asserting
on `meta.title` for a sealed subclass gets `meta` instead, with no compile error. Mitigated only by the
changelog entry, which is why the proposal marks it BREAKING rather than calling it a fix. The
counter-argument is that the old behaviour ignored the annotation, so the assertion was pinning a bug.

**`findOverridee()` costs a hierarchy walk per property.** Called once per property per generated file,
during resolution, which already resolves a type per property — `KSTypeReference.resolve()` is the
expensive call and this is not it. Guarded further by returning early when the property carries an
annotation of its own, which is the common case for an annotated property at all.

**More regeneration.** D1 and D3 both widen originating sets, so an edit to a value class or an
annotated sealed parent now regenerates the differs that read it. That is the fix, and it is strictly
more work in exchange for a build that agrees with a clean one. No `aggregating = true` is introduced,
so the widening stays proportional to what each file actually read.

**A three-level hierarchy resolves to the nearest annotated declaration.** `A` declares
`@DiffAsValue val x`, `B : A` overrides it plainly, `C : B` overrides it plainly: `C` inherits from `A`
through `B`. That is the Kotlin reader's expectation, and the loop makes it so; the alternative —
stopping at the first overridee whether or not it is annotated — would make `B`'s plain override silently
cancel `A`'s annotation.

## Migration Plan

A minor version with one behavioural break, confined to a sealed type whose parent declares a
comparison annotation on a property its subclasses override. `CHANGELOG.md` carries it under
**BREAKING** *Fixed* with the shape spelled out, since a reader has to recognise their own model in it
rather than a symbol name. Rollback is pinning the previous processor; generated code is unaffected
either way, because the change is in what gets generated rather than in what it calls.

## Documentation

- `docs/annotations.md` — `@DiffAsValue`, `@DiffIgnore` and `@DiffWith` each gain a line saying the
  annotation is honoured on a subclass that overrides the property, and that an annotation on the
  override wins.
- `docs/diffing.md` — the sealed-type section notes that a parent's comparison annotations apply in
  both branches of the dispatch.
- `docs/errors.md` — no new diagnostic, so no new quoted message. The `@TrackDepth`/`@DiffIgnore`
  conflict entry gains a sentence that the `@DiffIgnore` may be inherited from an overridden property.
- `CHANGELOG.md` under `[Unreleased]` — **BREAKING** *Fixed* for the sealed-override behaviour with its
  migration, and *Fixed* for the stale incremental build.
