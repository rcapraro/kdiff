## Context

See `proposal.md — Why` for motivation. This change declares `skip_specs: true`; the constraints it
must respect are the existing requirements in `openspec/specs/`, which the tutorial exercises from the
outside rather than changing.

Unlike the two preceding documentation changes, the schema's design rules have real subjects here: the
tutorial model deliberately exercises nullability, collections, maps, enums, nested `@Diffable` types
and sealed hierarchies, and building it hit constraints worth writing down before anyone starts. Those
are D9 through D12.

What already exists and shapes the approach:

- The root `build.gradle.kts` applies the Kotlin plugin, `group` and `version` to **every** subproject,
  and applies `explicitApi()` and `maven-publish` only to the three in `publishedModules`. A new module
  is therefore unpublished and not held to explicit API without any extra work.
- `kdiff-sample` is the only place the library is consumed the way a consumer consumes it, and its
  `DocumentationSamplesSpec` verifies documentation samples against source.
- `VALUE_TYPES` in the processor is primitives plus `String`. Nothing else is a scalar.
- `@Diffable` is accepted on data classes and sealed types, and rejected on objects.

## Goals / Non-Goals

**Goals:**

- A reader can follow one page start to finish and end up understanding the pattern, not just the API.
- The model is rich enough that every comparison rule in `diff-generation` shows up somewhere a reader
  can see it working, without becoming a catalogue.
- Both authoring routes appear in the same application, doing the job each is actually for.
- The example runs, so the tutorial can quote real output.

**Non-Goals:**

- **A framework.** No dispatcher abstraction, no event bus, no generic command pipeline. The moment the
  wiring becomes reusable machinery it stops teaching and starts hiding.
- **A persistence story.** An in-memory repository behind an interface.
- **Event sourcing.** Events are emitted, not the source of truth. Rehydrating from events would be a
  tutorial about event sourcing that happened to use kdiff.
- **Adding to `kdiff-runtime`.** Conveniences the tutorial wants are recorded as follow-ups (proposal,
  *Recorded, not fixed*).
- **CQRS read models, validation frameworks, or a web layer.**

## Decisions

### D1. A new module, with `application` for `run`

```kotlin
// kdiff-tutorial/build.gradle.kts
plugins {
    alias(libs.plugins.ksp)
    application
}

dependencies {
    implementation(project(":kdiff-annotations"))
    implementation(project(":kdiff-runtime"))
    ksp(project(":kdiff-processor"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

application { mainClass.set("tutorial.app.MainKt") }
```

`application` ships with Gradle, so `gradle/libs.versions.toml` is untouched and the project's rule
against version moves inside a feature change is not engaged.

Package layout mirrors the layers the tutorial explains, so a reader can navigate by concept:

```
tutorial/domain/    PersonId, FullName, Address, ContactMethod, Employment, PersonState, Person
tutorial/money/     Money and its hand-written differ — the un-annotatable type
tutorial/command/   PersonDto, UpdatePerson
tutorial/event/     PersonEvent hierarchy
tutorial/app/       PersonRepository, UpdatePersonHandler, Main
```

Not published, and not held to `explicitApi()` — both fall out of `publishedModules` (Context), which
keeps the tutorial source readable rather than ceremonially annotated.

### D2. The DTO is projected onto current state, and that is the whole trick

`Differ<T>` compares two instances of **one** type. A command's DTO is not the aggregate's state — it
carries no identity, no version, and only the fields a caller may set. So there is nothing to diff
until the DTO is turned into a state:

```kotlin
data class PersonDto(
    val name: FullName,
    val addresses: List<Address>,
    val contacts: List<ContactMethod>,
    val employment: Employment,
    val salary: Money,
    val tags: Set<String>,
    val externalIds: Map<String, String>,
)

fun PersonDto.projectOnto(current: PersonState): PersonState = current.copy(
    name = name,
    addresses = addresses,
    contacts = contacts,
    employment = employment,
    salary = salary,
    tags = tags,
    externalIds = externalIds,
)
```

`current.copy(...)` carries identity and version across untouched. Diffing `current` against the
projection then yields exactly the set of things this command asks to change — which is the sentence
the whole tutorial exists to earn.

The tutorial states this explicitly, because a reader who tries to diff a DTO against an aggregate
directly will not get a type error they can interpret; they will simply have no differ to call.

*Alternative rejected:* a DTO shaped identically to `PersonState`, so no projection is needed. It
removes the projection but teaches something false — a command that can set `version` and `id` is not
a command — and hides the constraint that makes the pattern work.

### D3. The command is *tracked*, not merely diffed — and the scope is the point

```kotlin
private val tracker = tracker(PersonStateDiffer, current) {
    onChange { before, after, changes -> /* audit */ }
}
val diff = tracker.update(desired)
```

Using a `Tracker` rather than calling `PersonStateDiffer.diff` directly is not decoration. The tracking
scope declared by `@Trackable` is what decides which fields count as *meaningful* change, and the
tutorial uses that: `PersonState.lastSeenAt` is `@TrackIgnore`, so a command that only touches it
produces no events at all, while a diff would still report it.

That is a genuinely useful thing to teach — "what counts as a change" is a domain decision, and the
annotation is where it lives.

The handler shows the declared scope as the default, and a hand-written `trackScope { }` alongside it
for the case where a caller wants a narrower view than the type declares (D7).

### D4. Dispatch is an exhaustive `when` per concern, over an inexhaustive `when` on the root

Two nested decisions, and only the inner one can be exhaustive:

```kotlin
private fun FieldPath.root(): String? =
    (segments.firstOrNull() as? Segment.Field)?.name

private fun AddressId.of(change: Change): AddressId? =
    (change.path.segments.getOrNull(1) as? Segment.Key)?.value as? AddressId
```

```kotlin
diff.changes.forEach { change ->
    when (change.path.root()) {
        "name" -> person.rename(desired.name)

        "addresses" -> when (change) {
            is Added -> person.addAddress(change.value as Address)
            is Removed -> person.removeAddress(change.value as Address)
            is Moved -> person.reorderAddress(keyOf(change), change.from, change.to)
            else -> person.editAddress(keyOf(change), desired.addressBy(keyOf(change)))
        }

        "contacts" -> person.replaceContacts(desired.contacts)
        "employment" -> person.changeEmployment(desired.employment)
        "salary" -> person.adjustSalary(desired.salary)

        // Audit-only: tracked, reported, but no aggregate method to call.
        else -> Unit
    }
}
```

`when` on a `String?` cannot be exhaustive, so the `else` is unavoidable — and the tutorial makes it a
teaching point rather than an apology. Not every tracked field is a domain operation. `tags` and
`externalIds` change, are reported, and are recorded by the audit callback without the aggregate having
an opinion. A reader who expects one method per field needs to be told that early.

The inner `when` over `Change` **is** exhaustive by construction: `Change` is sealed and closed, so a
sixth kind would fail to compile here. Since a keyed list is the only place `Moved` can arise, this is
also the natural spot to show why the vocabulary being closed is worth something.

*Alternative rejected:* a `Map<String, handler>` registry. Less code at the call site, but a missing key
is a silent no-op at runtime instead of a visible branch, and the `when` doubles as a readable statement
of what the command is permitted to do.

*Alternative rejected:* one scoped tracker per concern with `onFieldChange` wired straight to an
aggregate method. It is the most declarative option and reads beautifully for scalars, but it cannot
express the keyed-list cases — `onFieldChange` flattens `Added`/`Removed`/`Moved` into a `(path, before,
after)` triple, and `reorderAddress` needs the two indices as indices. The tutorial shows this variant
for `name` and says why it stops there.

### D5. Events are typed, and the aggregate reads `before` from itself

```kotlin
sealed interface PersonEvent {
    val personId: PersonId
}

data class PersonRenamed(
    override val personId: PersonId,
    val before: FullName,
    val after: FullName,
) : PersonEvent

data class AddressesReordered(
    override val personId: PersonId,
    val addressId: AddressId,
    val from: Int,
    val to: Int,
) : PersonEvent

data class EmploymentChanged(
    override val personId: PersonId,
    val before: Employment,
    val after: Employment,
) : PersonEvent
```

The before/after in an event comes from the aggregate's own state at the moment the method runs, not
from the `Change` that triggered dispatch:

```kotlin
fun rename(to: FullName) {
    if (to == state.name) return
    record(PersonRenamed(state.id, before = state.name, after = to))
    state = state.copy(name = to)
}
```

Why not pass the `Change` through: an event is domain vocabulary and outlives the mechanism that
produced it. A subscriber should not have to know what a `FieldPath` is, and the same aggregate method
must work when called from somewhere that never computed a diff. Keeping kdiff at the boundary is the
architectural point of the whole example — the diff decides *which* method to call, and stops there.

Each method is also idempotent-guarded (`if (to == state.name) return`), which matters because dispatch
may call a method for a change that a previous change in the same diff already satisfied.

### D6. The aggregate holds mutable state and records events

```kotlin
class Person private constructor(private var state: PersonState) {
    private val recorded = mutableListOf<PersonEvent>()

    val events: List<PersonEvent> get() = recorded.toList()
    val snapshot: PersonState get() = state

    companion object {
        fun rehydrate(state: PersonState): Person = Person(state)
    }
}
```

A mutable aggregate with a private constructor and a `rehydrate` factory is the ordinary shape, and it
keeps the tutorial about kdiff rather than about persistent data structures. `snapshot` is how the
handler gets the new state back to the repository, and it is a `PersonState` — the same type the differ
compares, which is what lets the next command diff against it.

### D7. Both routes, each doing the job it is for

**Annotations** for everything the tutorial owns — the whole domain model.

**The DSL** for `Money`, which stands in for a type from someone else's library: a plain class, not a
data class, so `@Diffable` would reject it outright.

```kotlin
class Money(val amount: String, val currency: String)

object MoneyDiffer :
    Differ<Money> by differ({
        field(Money::amount)
        field(Money::currency)
    }),
    Patcher<Money> { /* groupByProperty + patchValue, as in docs/hand-written.md */ }
```

reached from the domain by `@DiffWith(MoneyDiffer::class) val salary: Money`. Making it *patchable*
rather than compare-only is deliberate: the compare-only case is already demonstrated in
`docs/hand-written.md`, and a salary that could not be applied would be a distraction here.

`trackScope { }` appears in the handler as the narrower-than-declared alternative (D3), which is the
situation a hand-written scope is actually for.

### D8. The harness must declare the sources it verifies, not just the markdown

`:kdiff-sample:test` declares `README.md`, `CONTRIBUTING.md` and `docs/` as inputs. It does not declare
the source trees the samples are checked against. That is currently harmless only because those sources
belong to `kdiff-sample`, whose test task already re-runs when they change.

A sample pointing into `kdiff-tutorial` breaks that: editing tutorial source would leave the test
`UP-TO-DATE` and `check` would pass against a stale result.

```kotlin
inputs.files(
    rootProject.fileTree("kdiff-sample/src"),
    rootProject.fileTree("kdiff-tutorial/src"),
    rootProject.fileTree("kdiff-runtime/src/main"),
)
    .withPropertyName("verifiedSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
```

`kdiff-runtime/src/main` is in the list because `docs/diffing.md` and `docs/patching.md` already cite
`Differ.kt` and `Patcher.kt`. That gap exists today and this change closes it.

This is a fix, and it gets proved the way the harness itself was: perturb a source file, watch the check
fail, restore, watch it pass. Asserting the inputs are declared is not the same as showing the task
re-runs.

### D9. Type resolution: what the model exercises, and three constraints it ran into

The model is chosen so each rule in `diff-generation` appears once, in a place where it means something:

| Rule | Where it shows up |
|---|---|
| value / `String` | `FullName.given`, `Address.line1` |
| enum | `Address.kind: AddressKind` (`HOME`, `WORK`) |
| nullable | `PersonState.nickname: String?`, exercised in both directions |
| nested `@Diffable` | `PersonState.name`, `Address.postalCode`, `Address.country` |
| keyed list | `PersonState.addresses`, giving `Added` / `Removed` / `Moved` / keyed edits |
| positional list | `PersonState.contacts`, whose elements are sealed |
| set | `PersonState.tags` |
| map | `PersonState.externalIds` |
| sealed type | `Employment` as a property, `ContactMethod` as list elements |
| `@DiffIgnore` | `PersonState.version` — mechanical, never a domain change |
| `@DiffWith` | `PersonState.salary: Money` |
| `@Trackable` / `@TrackIgnore` / `@TrackDepth` | the class, `lastSeenAt`, `addresses` |

**Depth 2 at the class, not 1 — corrected during implementation.** This design first specified
`@Trackable(depth = 1)`, and running the example proved it wrong: `name` is a nested `FullName`, so a
rename reports at `name.family`, two property steps, which depth 1 filters out. A rename emitted **no
event at all**. Any property whose type is a value object needs depth ≥ 2 to be visible, which is most
of a DDD model.

So the class declares `depth = 2`, and `addresses` widens to `UNLIMITED_DEPTH` because an address nests
a further level — `addresses[id=A1].postalCode.value` is three steps and would be invisible even at 2.
This is now the tutorial's sharpest lesson rather than a silent bug, and it is exactly the failure mode
the "depth filters, it does not roll up" requirement implies but does not make vivid.

Three constraints found while designing it, each of which would otherwise be discovered as a confusing
compile error at implementation time:

1. **A sealed hierarchy cannot contain a `data object`.** `@Diffable` on a sealed type requires every
   subclass to be `@Diffable`, and `@Diffable` is rejected on objects. So the obvious
   `data object Unemployed : Employment` is not available. `Employment` is therefore `Employed(employer,
   since)`, `SelfEmployed(businessName)` and `Retired(since)` — all data classes.
2. **An inline `value class` is not a value type.** `VALUE_TYPES` is primitives plus `String`, and a
   `value class` cannot be a data class, so `@JvmInline value class PersonId(val value: String)` is
   neither scalar nor annotatable. Identifiers are single-property `@Diffable data class`es instead:
   `PersonId(value)`, `AddressId(value)`.
3. **A key renders through `toString`.** `Segment.Key` retains the key as its own value, and a path
   renders it with `toString`. With a data class id that gives `addresses[id=AddressId(value=A1)].city`.
   So `AddressId` and `PersonId` override `toString` to return `value`, and paths read
   `addresses[id=A1].city`. The tutorial shows both renderings, because it is the kind of detail that
   makes generated paths unusable in a log if nobody mentions it.

`ContactMethod` declares `val label: String` on the sealed parent, so a subclass swap reports a
`TypeChanged` *and* compares `label` — demonstrating the parent-properties rule. `Employment` declares
nothing, so a subclass change there produces the type change alone. Having both is deliberate.

### D10. The generated shape a reader will see

Reviewable before implementation. For `PersonState`:

```kotlin
public object PersonStateDiffer : Differ<PersonState>, Patcher<PersonState>, Tracked<PersonState> {
  override val trackScope: TrackScope<PersonState> = trackScopeOf(
      TrackedField("id", 2),
      TrackedField("name", 2),
      TrackedField("nickname", 2),
      TrackedField("addresses", -1),  // @TrackDepth(UNLIMITED_DEPTH)
      TrackedField("contacts", 2),
      TrackedField("employment", 2),
      TrackedField("salary", 2),
      TrackedField("tags", 2),
      TrackedField("externalIds", 2),
      // lastSeenAt absent: @TrackIgnore. version absent: @DiffIgnore.
  )

  override fun diff(before: PersonState, after: PersonState): Diff = Diff(
    buildList<Change> {
      compareNested("id", before.id, after.id, PersonIdDiffer)
      compareNested("name", before.name, after.name, FullNameDiffer)
      compareValue("nickname", before.nickname, after.nickname)
      compareKeyedList("addresses", "id", before.addresses, after.addresses, AddressDiffer) { it.id }
      comparePositionalList("contacts", before.contacts, after.contacts, ContactMethodDiffer)
      compareNested("employment", before.employment, after.employment, EmploymentDiffer)
      compareNested("salary", before.salary, after.salary, MoneyDiffer)
      compareSet("tags", before.tags, after.tags)
      compareMap("externalIds", before.externalIds, after.externalIds, null)
    },
  )
  // apply(...) as generated
}
```

A task verifies the emitted `trackScope` against this, since it is the one part of the generated output
this change's annotations actually determine.

### D11. Incremental processing

Every generated file keeps `Dependencies(aggregating = false, originatingFile)`; nothing in this change
goes near `DiffProcessor`.

Worth noting for a reader, and worth a task to confirm rather than assume: the tutorial's model is
spread over several files, and a generated differ's dependencies are the annotated class's own file plus
the files of the types it delegates to. So `PersonStateDiff.kt` depends on the files declaring
`Address`, `ContactMethod`, `Employment` and the rest — touching `Address.kt` regenerates
`PersonStateDiff.kt`, and touching an unrelated file does not. That is the intended behaviour of
`aggregating = false` with declared originating files, and the module's file layout makes it observable.

### D12. What lives in the runtime versus what is generated — as the tutorial shows it

Nothing moves. The tutorial is a place where the split becomes visible to a reader, so it names it: the
generated differ is a straight-line sequence of calls into `kdiff-runtime` helpers, and the hand-written
`MoneyDiffer` calls the same `groupByProperty` and `patchValue`. That is why a hand-written differ is
indistinguishable from a generated one, and the tutorial points at the generated file so a reader can
see it for themselves.

## Risks / Trade-offs

- **The model is large enough to obscure the lesson** → the tutorial introduces it in layers: scalars
  and nesting first, then the keyed list, then the sealed types, then the collections. D9's table is for
  the design's benefit, not the reader's; no single code block shows all nine properties at once.
- **The tutorial page will be long** → it is a tutorial, read start to finish, and splitting a narrative
  across pages is worse than length. The six existing reference guides stay short and it links to them
  rather than restating them.
- **Casts in the dispatch** (`change.value as Address`) → unavoidable, because `Added.value` is `Any?`;
  the vocabulary is closed over kinds, not typed per element. The tutorial shows the casts honestly and
  confines them to one file rather than hiding them behind a helper that would only move the problem.
- **`else -> Unit` reads like an oversight** → it is explained where it appears, and `tags` /
  `externalIds` exist specifically so the branch has a real reason to be there.
- **The harness fix is invisible if only the happy path is run** → D8's verification perturbs source and
  requires the failure to be observed. This is the second time this exact defect has appeared, which is
  why it gets a demonstrated failure rather than an assertion.
- **A new module is a permanent maintenance cost** → it is also the integration test for the library's
  ergonomics: if a future change makes kdiff awkward to use, `kdiff-tutorial` is where it shows.

## Migration Plan

None required. Additive: a new unpublished module, one new documentation page, two links, and a
build-input fix that can only cause more work to run, never less. Rollback is deleting the module and
the page and reverting `settings.gradle.kts` and one test-task configuration block.
