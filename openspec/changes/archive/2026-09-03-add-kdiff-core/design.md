## Context

See `proposal.md` — Why. Requirements are in `specs/diff-generation/spec.md`.

`add-kdiff-skeleton` fixed the parts this change must not disturb: four modules with the processor
compile-time-only for consumers, `object <Type>Differ : Differ<Type>` generated into `<Type>Diff.kt`
in the annotated class's package, diagnostics through `KSPLogger.error(message, symbol)`, and
`Dependencies(aggregating = false, ...)` on every generated file. The differ's signature does not
change here — only its body, and the runtime types it builds results from.

Two constraints shape everything below. First, the runtime must stay reflection-free, so anything
the generated code cannot state literally has to be resolved at compile time. Second, generated
code is compiled into consumers and can only be fixed by recompiling them, so it must stay thin.

## Goals / Non-Goals

**Goals**

- Comparison for every property shape in the spec, each one an independently compile-tested case.
- Path construction that survives arbitrary nesting, so a change three levels down reads back to
  the root.
- A hand-written differ that is indistinguishable from a generated one, so the two compose in
  either direction.
- Diagnostics for every shape kdiff refuses, reported at the property or declaration at fault.

**Non-Goals**

- Patch application. `apply(before, changes) -> after` is the next change; nothing here should make
  it harder, which is why changes carry enough information to reconstruct a value.
- A longest-common-subsequence alignment for unkeyed lists. Positional comparison is what the spec
  requires; LCS is a later refinement if noisy diffs prove to be a problem in practice.
- Cycle detection. Documented as unsupported, per the skeleton's design.
- Performance work. Correctness across shapes first; the generated code is straight-line and can be
  measured later.

## Decisions

### D1 — The runtime grows; the generated code stays a straight-line sequence of comparisons

The split established in the skeleton holds and gets more load-bearing. Everything that is the
same for every type lives in `kdiff-runtime`:

| Lives in the runtime | Why not generated |
|---|---|
| `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved` | The change vocabulary is closed and type-independent. |
| `Segment.Index`, `Segment.Key`, path prefixing | Path arithmetic is identical everywhere and is easy to get subtly wrong; generating it would multiply one bug across every differ. |
| The list/set/map comparison algorithms | Keyed matching, positional walking and membership are generic over the element differ. Generating them per property would emit the same loop dozens of times. |
| `Diff.tree()`, `Diff.render()` | Computed from changes alone; the type being diffed is irrelevant. |
| The `differ<T> { }` DSL | Hand-written differs are runtime constructs by definition. |

**Generated — only what the runtime cannot know without reflection:** which properties exist, in
what order, what each is called, how to read it, and which differ handles it. Concretely, a
generated `diff` is a sequence of calls into runtime helpers, one per property.

The consequence worth stating: the collection algorithms take a `Differ<E>` and a key extractor as
parameters. Generated code supplies them; the loop itself is written once, in the runtime, where a
fix ships as a dependency bump rather than a recompile of every consumer.

*Alternative considered*: generating the loops inline for speed, avoiding a lambda per element.
Rejected for now — it triples the size of generated files for an unmeasured gain, and it is exactly
the kind of cleverness that makes generated code unreadable, which the charter names as a goal.

### D2 — Paths are built by prefixing, not by threading a prefix down

A nested differ knows nothing about where it is being used: `AddressDiffer` always reports
`street`, never `address.street`. The caller prefixes.

So delegation is: call the nested differ, then map each returned change onto the same change with
`Segment.Field("address")` prepended to its path. The runtime provides that prefixing operation;
generated code names the segment.

This is what makes a hand-written differ compose exactly like a generated one — neither has to
cooperate with its parent — and it is why `Change` needs to be able to produce a copy of itself at
a new path. Each `Change` subtype therefore exposes a way to rebuild itself with a different path.

*Alternative considered*: passing a `FieldPath` prefix into `diff` as a parameter. Rejected — it
changes the `Differ` interface the skeleton froze, it pushes path bookkeeping into every
hand-written differ, and it makes a differ's output depend on its caller.

### D3 — Type resolution: the full policy, and where each case is decided

Every property is resolved once, in this order. The first match wins. `KSTypeReference.resolve()`
is called once per property and the resulting `KSType` is passed down, per the charter.

| Order | Property shape | Generated comparison | Refused? |
|---|---|---|---|
| 1 | Carries `@DiffIgnore` | nothing emitted | — |
| 2 | Carries `@DiffWith(D::class)` | delegate to `D`, prefix paths | error if `D` is not an `object` implementing `Differ<thatType>` |
| 3 | Primitive, `String`, or enum | `!=`, emit `ValueChanged` | — |
| 4 | Type is `@Diffable` | delegate to `<Type>Differ`, prefix paths | — |
| 5 | `List<E>` where `E` resolves by 3/4/2 | keyed if `E` has `@DiffKey`, else positional | error if `E` itself resolves to nothing |
| 6 | `Set<E>` | membership by `equals` on `E` | — |
| 7 | `Map<K, V>` | by key; `V` resolved recursively by this table | error if `V` resolves to nothing |
| 8 | anything else | — | **error**: names the property and its type, points at `@DiffWith` |

**Nullability** is orthogonal and applied on top of whichever row matched: a nullable property is
guarded, and a null on either side short-circuits to `ValueChanged`. This is why nullability is not
its own row — it composes with all of them. The spec's reasoning is the one that matters: a
property's path exists on both sides or neither, so `Added`/`Removed` stay reserved for collection
elements and map entries.

**Enums** need no special casing beyond row 3. They are values with correct `equals`, and treating
them as anything richer would report a change inside an enum constant, which is meaningless.

**Generics** are refused at the annotated class, not at the property: `@Diffable data class Box<T>`
cannot be given a differ because it would need a `Differ<T>` that only the use site knows. Refusing
is honest. Supporting it needs a differ with constructor parameters, which contradicts the `object`
shape the skeleton froze, and is its own change if ever wanted.

**Sealed types** are resolved by a separate path, described in D4.

**Cycles** are a data property, not a type property, and are not statically detectable in general.
Documented as unsupported.

### D4 — Sealed dispatch compares the parent's own properties on a subclass swap

An annotated sealed type generates a differ whose body is a `when` on the pair of runtime types:

- both the same subclass → delegate to that subclass's differ, return its changes unchanged.
- different subclasses → emit `TypeChanged(path, oldTypeName, newTypeName)`, then compare **the
  properties the sealed parent itself declares**, emitting those as ordinary changes.

The second half is the decision the user asked for, and the "properties the sealed parent itself
declares" definition is what keeps it well-defined. The murky version — "properties the two
subclasses have in common" — would make a change's meaning depend on which pair of subclasses
happened to be compared, and two same-named properties on unrelated subclasses are not the same
property. Restricting to the parent's own declarations means the set of comparable properties is a
property of the sealed type alone, known at compile time, identical for every pair.

When the parent declares nothing, a swap produces the `TypeChanged` alone, which is the common case
for a marker-style sealed interface.

Subclass-only properties are deliberately not descended into: there is no correspondence between
`Card.last4` and `Transfer.iban`, so any change reported there would be an artifact of the two
types being compared rather than a difference in the data.

Every subclass must be `@Diffable` — dispatch has to cover every branch, and a missing one is a
branch the processor cannot generate. That is a diagnostic, not a silent fallback.

### D5 — `@DiffWith` names an object, and the processor verifies its type

`@DiffWith(MoneyDiffer::class)` on a property. The processor checks that the named class is an
`object` and that it implements `Differ<P>` for that property's type `P`; if not, that is an error
at the property. Passing the check, generated code references the object by name — the same shape
as delegating to a generated differ, which is why the two compose with no special case.

Requiring an `object` rather than accepting any expression is what keeps generated code able to
name it. A hand-written differ built by the DSL is therefore held in an object:

```kotlin
object MoneyDiffer : Differ<Money> by differ({
    field(Money::amount)
    field(Money::currency)
})
```

The DSL itself uses `KProperty1` references. `.name` and `.get()` on a property reference are plain
stdlib — no `kotlin-reflect` dependency — so the no-reflection goal holds for hand-written differs
too.

*Alternative considered*: a runtime `DifferRegistry` keyed by `KClass`. Rejected — it moves
resolution to runtime, turns a compile error into a runtime failure, and reintroduces a lookup on
every comparison.

### D6 — Incremental processing: originating files are every file the generation reads

This is the decision most likely to cause a bug that CI never sees, and the skeleton's design set
the rule specifically so this change could extend it: **originating files are every file read to
produce the output, not the file carrying the annotation.**

In this change a generated `<Type>Diff.kt` depends on:

1. the file declaring the annotated type — as before;
2. the containing file of every nested `@Diffable` type it delegates to, because adding a property
   to the nested type changes what the outer differ must produce;
3. for a sealed type, the containing file of every subclass, because the `when` must cover them and
   the parent's own property comparison depends on the parent's declarations;
4. the containing file of every type named by `@DiffWith`.

All of them go into one `Dependencies(aggregating = false, *files)`. `aggregating` stays `false`:
a differ is still a function of a specific, enumerable set of files, not of the whole module. An
unrelated `@Diffable` class appearing elsewhere must not invalidate it — which the skeleton
verified and this change re-verifies with nesting in play.

The failure mode if this is wrong: edit a nested type, rebuild incrementally, and the outer differ
silently keeps comparing the old property set. Clean builds hide it completely. Hence a dedicated
verification task rather than trust.

**How this is verified, and why not by timestamps.** At KSP 2.3.11 the Gradle worker that runs KSP2
(`KspAAWorkerAction.execute`) begins by deleting the whole generated-output directory —
`gradleCfg.outputBaseDir.get().asFile.deleteRecursively()`, carrying an explicit
`// TODO: support incremental processing`. Every generated file is therefore rewritten on every
run, whatever `Dependencies` declares. `ksp.incremental` still defaults to `true` and KSP's own
incremental engine still runs and records, so the effect is confined to the Gradle task's output
handling.

Two consequences. First, file timestamps and content hashes prove nothing here in either direction:
regeneration is guaranteed, so observing it is not evidence of a correct dependency, and the
absence of regeneration cannot be observed at all. Second, the real contract is still recorded, and
is inspectable: building with `-Pksp.incremental.log=true` writes
`build/kspCaches/<sourceSet>/logs/kspSourceToOutputs.log`, whose accumulated source-to-outputs map
is exactly the set of originating files this decision is about. That map is what the verification
tasks assert on.

For this change the map reads:

```
src/main/kotlin/demo/Model.kt:    PaymentDiff.kt CardDiff.kt TransferDiff.kt OrderDiff.kt
src/main/kotlin/demo/Address.kt:  OrderDiff.kt AddressDiff.kt
src/main/kotlin/demo/Unrelated.kt: UnrelatedDiff.kt
```

`Address.kt -> OrderDiff.kt` is the cross-file dependency this decision exists for: `OrderDiffer`
delegates to `AddressDiffer`, so editing `Address.kt` must regenerate `OrderDiff.kt`. And an
unrelated annotated class is an input to its own output only — never to an existing one — which is
`aggregating = false` doing its job.

The upstream TODO makes correct dependencies latent rather than inert: they are recorded correctly
today and become load-bearing the moment KSP's Gradle integration stops wiping the directory.

### D7 — `tree()` and `render()` are derived views, computed from the flat list

Both are functions of `changes` alone, computed on demand, with the flat list remaining the single
source of truth. Neither is stored, and diffing does not build them.

`tree()` groups by common path prefix. The spec's round-trip requirement — collecting every change
from the tree yields exactly the flat list — is the invariant that keeps the two views honest, and
it is directly testable.

`render()` writes one line per change: the path, the kind, and the values involved. Keeping it a
plain function of the list means the renderer can change without touching generation.

*Alternative considered*: making the tree the primary representation and deriving the list.
Rejected — the flat list is what generated code naturally produces and what patch application will
consume next change; a tree would have to be assembled during diffing and flattened again.

## Sample of the generated file

For this input:

```kotlin
package demo

@Diffable
data class Address(@DiffKey val id: String, val street: String, val city: String)

@Diffable
data class Person(
    val id: String,
    val name: String,
    @DiffIgnore val lastSeen: String,
    val address: Address?,
    val addresses: List<Address>,
)
```

the generated `demo/PersonDiff.kt` is shaped like this — a straight-line sequence of runtime calls,
one per compared property, and nothing else:

```kotlin
package demo

import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.compareKeyedList
import io.github.kdiff.runtime.compareNested
import io.github.kdiff.runtime.compareValue
import io.github.kdiff.runtime.Change
import kotlin.collections.buildList

public object PersonDiffer : Differ<Person> {
  override fun diff(before: Person, after: Person): Diff = Diff(
    buildList<Change> {
      compareValue("id", before.id, after.id)
      compareValue("name", before.name, after.name)
      compareNested("address", before.address, after.address, AddressDiffer)
      compareKeyedList("addresses", before.addresses, after.addresses, AddressDiffer) { it.id }
    },
  )
}
```

`lastSeen` is absent because it is ignored — an ignored property emits nothing rather than emitting
a skipped comparison. `address` is nullable, so `compareNested` is the null-guarding overload. The
key extractor `{ it.id }` is the one thing per keyed list the runtime cannot know.

For a sealed type:

```kotlin
@Diffable
sealed interface Payment { val amount: String }

@Diffable
data class Card(override val amount: String, val last4: String) : Payment

@Diffable
data class Transfer(override val amount: String, val iban: String) : Payment
```

`demo/PaymentDiff.kt`:

```kotlin
public object PaymentDiffer : Differ<Payment> {
  override fun diff(before: Payment, after: Payment): Diff = Diff(
    buildList<Change> {
      when {
        before is Card && after is Card -> addAll(CardDiffer.diff(before, after).changes)
        before is Transfer && after is Transfer -> addAll(TransferDiffer.diff(before, after).changes)
        else -> {
          typeChanged(before, after)
          compareValue("amount", before.amount, after.amount)
        }
      }
    },
  )
}
```

The `else` branch is where D4's decision shows: the type change, then the sealed parent's own
declared properties, and nothing from either subclass.

## Risks / Trade-offs

- **The largest risk is scope: the whole type-resolution surface plus two views in one change** →
  Mitigated structurally, not by hope. Each row of D3's table is a separate compile-tested scenario
  and a separate task, so a failure localises to one property shape. The tasks are ordered so the
  runtime model and paths land and are tested before any generation depends on them.

- **Incremental staleness from incomplete originating files (D6)** → Invisible on clean builds and
  on CI. Mitigated by a dedicated task that edits a nested type and asserts the outer differ is
  regenerated, and by a second that adds an unrelated annotated class and asserts the outer differ
  is not.

- **`Change` needs to rebuild itself at a new path for prefixing (D2)** → Every new `Change`
  subtype must implement it, and a subtype that forgets silently loses path information for
  everything nested beneath it. Mitigated by making it part of the `Change` contract rather than a
  helper, so the compiler requires it, and by testing prefixing at three levels of nesting.

- **A `Set` reports only membership, never element modification** → A user who expects `Set<Address>`
  to report a changed street will be surprised. This is inherent: a modified element in a set is
  indistinguishable from one removed and another added, because set elements have no identity.
  Documented in the spec as a requirement rather than left implicit.

- **Positional comparison of unkeyed lists is noisy for a head insert** → Every subsequent index
  reports a change. Accepted deliberately: it is what the spec requires, `@DiffKey` is the escape,
  and LCS is a later refinement with its own edge cases.

- **Generated files get much larger, and readability is a stated goal** → Mitigated by D1: the body
  is one call per property with no inline loops, so a generated file stays roughly as long as the
  class it diffs.

## Migration Plan

The only consumer is `kdiff-sample`, in this repository, and it is updated in this change. Its
existing test asserts a diff is always empty; that assertion is inverted, and the sample gains
models covering each property shape.

No published artifacts exist, so no external migration. Rollback is reverting the change: the
skeleton's generated shape is unchanged, so nothing downstream is stranded.

## Open Questions

- Whether `render()` should gain a colour or unified-diff mode. Deferrable: it is additive, changes
  no requirement here, and is better decided once someone reads real output.
- Whether unkeyed lists should eventually use an LCS alignment. Cannot be settled before there is
  real usage to judge the noise against; it would be a spec change to this capability, not a
  silent improvement.
