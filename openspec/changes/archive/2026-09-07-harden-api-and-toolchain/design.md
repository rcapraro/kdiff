## Context

See [proposal.md](proposal.md) — *Why*. The constraints that shape the approach:

**Generated code is a narrow contract.** Reading the sample's generated output settles the blast
radius. A generated differ names exactly: the `Differ`/`Patcher`/`Tracked` interfaces, the
`Diff(List<Change>)` and `PatchResult(value, failures)` constructors, `PatchFailure` as a type
argument to `buildList`, and the runtime helpers by name (`compareValue`, `patchValue`,
`groupByProperty`, `unmatchedFailures`, `trackScopeOf`, …). It never constructs a `PatchFailure`, never
writes a failure reason, and never reads `Diff.isEmpty`. So **no emitter changes**, provided those two
constructors keep their parameter lists — which they do.

**The runtime owns every descent point.** Nesting is reached only through `Compare.kt`
(`compareNested`, `compareNestedNullable`, `compareKeyedList`, `comparePositionalList`, `compareMap`),
`Patch.kt` (`patchNested`, `patchNestedNullable`, `patchKeyedList`, `patchPositionalList`, `patchMap`)
and `Dsl.kt`'s subtype delegation. Generated code calls those; it does not recurse itself. A cycle
guard therefore has exactly eleven call sites, all in one module, and needs no cooperation from the
processor.

**The last change was about allocation on these paths.** `perf: cut allocation on the comparison,
application and tracking paths` (0.3.1) removed intermediate lists and per-change destructuring from
`Compare.kt`, `Patch.kt` and `Tracker.update`. `kdiff-benchmarks` exists to hold that ground. Any guard
added here is measured against it before it lands.

**`explicitApi()` is on for the three published modules, and nothing checks the resulting surface.**
The breaking items in this change are exactly the kind of thing an API dump exists to make visible.

## Goals / Non-Goals

**Goals**

- The public surface reads like a `1.0` surface: typed errors, stdlib-shaped collection semantics,
  scoped DSLs, no duplicated-and-drifting member sets.
- Zero cost to a model that does not hit an error path. The cycle guard is the only addition on a hot
  path, and it is measured.
- No new runtime dependency. `kdiff-runtime` still depends on the Kotlin stdlib alone.
- Every new gate hangs off `./gradlew check`; CI keeps running one command.

**Non-Goals**

- No change to annotation semantics, to which shapes the processor accepts, or to which diagnostics it
  reports.
- No new `Change` variant. The vocabulary stays closed at five.
- No configurable descent bound and no user-supplied identity strategy — a constant, documented, and
  that is the whole knob.
- No `Patcher` builder DSL. The "does it construct?" axis is unchanged.
- No cross-graph identity or structural sharing: the comparison still walks a tree. The guard reports a
  cycle; it does not make one comparable.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.

## Decisions

### 1. `PatchFailure.reason` becomes a sealed `Reason`, and `toString` renders it

The runtime reports twelve distinct reasons today, all as English sentences built at the failure site.
They become twelve `data object`s and `data class`es under one sealed interface, each carrying what its
site already knew:

```kotlin
public data class PatchFailure(public val change: Change, public val reason: Reason) {
    override fun toString(): String = "${change.path}: ${reason.describe()}"

    public sealed interface Reason {
        public data object NotApplicableToValue : Reason
        public data object NotApplicableToKeyedList : Reason
        public data object NotApplicableToPositionalList : Reason
        public data object NotApplicableToMap : Reason
        public data object NothingBeneathNull : Reason
        public data object NoElementForKey : Reason
        public data object NoElementAtIndex : Reason
        public data object NoEntryForKey : Reason
        public data object ElementComparedAsValue : Reason
        public data object EntryComparedAsValue : Reason
        public data object SetElementNotModifiable : Reason
        public data class UnknownProperty(public val type: String) : Reason
        public data class UnpatchableProperty(public val property: String) : Reason
        public data class NotConstructorProperty(public val property: String) : Reason
    }
}
```

`describe()` is an internal `when` over the sealed interface, so the rendered sentence for each case is
the sentence it renders today and the compiler enforces that the list stays complete — the same trick
`Change.withPath` already uses.

*Why sealed data types over an enum with a payload:* three cases carry a value and eleven do not; an
enum would force every case to carry a nullable `property`, which is exactly the shapelessness the
change is removing. *Why not a string plus a code:* a code is a string with extra steps.

*Interaction with `groupByProperty`:* `unmatchedFailures(type)` already takes the type name, so
`UnknownProperty(type)` is a rename of an existing parameter, not new plumbing.

### 2. Refusals become declared exception types, in a new `Errors.kt`

```kotlin
public class DuplicateDiffKeyException internal constructor(
    public val property: String?,
    public val keyProperty: String?,
    public val key: Any?,
) : IllegalArgumentException(...)

public class CyclicStructureException internal constructor(
    public val path: FieldPath,
    public val repeated: Boolean,
) : IllegalArgumentException(...)

public class PatchFailedException internal constructor(
    public val failures: List<PatchFailure>,
) : IllegalStateException(...)
```

The two *refusals* root at `IllegalArgumentException`: that is what the runtime raises today, so every
existing `catch` keeps working — the spec requires it and it costs nothing — and an input the library
declines to interpret is an illegal argument by the stdlib's own reading.
`PatchFailedException` is not a refused input; it is a caller asking a partial result for a value it
does not have, so it roots at `IllegalStateException`. There is deliberately **no** common
`KdiffException` supertype: a marker interface cannot be caught, and a shared abstract class would have
to root at one of the two and misfile the other.

The constructors are `internal`: a caller inspects these, it does not raise them.
`PatchFailedException` is what `PatchResult.getOrThrow()` raises.

*Alternative rejected:* one exception type with a `kind` enum. It saves a file and loses the typed
payload, which is the entire point.

### 3. The cycle guard is a bounded descent counter that starts recording identity near the bound

`Differ<T>.diff(before, after)` has no place to thread a depth through, and adding one would change the
generated signature — which is the one thing this change is not doing. So the counter lives in a
thread-local, touched once per *descent*, not once per property:

```kotlin
/** The deepest nesting kdiff will descend into before refusing the structure. */
public const val MAX_DESCENT: Int = 512

internal object Descent {
    private const val WATCH = MAX_DESCENT - 64

    private val state = ThreadLocal.withInitial { State() }

    inline fun <R> into(segment: Segment, value: Any?, body: () -> R): R { ... }
}
```

`into` increments, runs the body in a `try`/`finally` that decrements, and throws
`CyclicStructureException` when the incremented depth passes `MAX_DESCENT`. Below `WATCH` it records
nothing. From `WATCH` upward it pushes the value's identity and the segment onto a small list, so that
at the bound it can say two things the spec asks for: the path it stopped at, and whether an instance was re-entered
— a genuine cycle — or the model is merely deeper than the bound.

*Why a two-stage guard:* identity tracking on every descent is an `IdentityHashMap` insert per nested
value, on the paths the previous change spent its effort clearing. Counting is an `int`; recording only
starts in the last 64 levels, which no honest model reaches, and a cycle whose period is under 64 is
identified there. A cycle with a longer period than that is still refused, just reported as "no repeat
observed" — honest, and the message says so.

*Why `512`:* deep enough that a hand-written recursive model (`Node.next`, a category tree) will not
trip it, shallow enough to be well inside a default 512 KiB JVM stack given each level is several
frames. It is a documented `public const val` on the runtime, not configurable: a configurable bound is
per-call state, which means threading it through `Differ` — decision 3's whole premise is that we are
not doing that.

*Cost:* one `ThreadLocal.get()` and two `int` writes per nested delegation. Not per property, not per
change. Measured on `kdiff-benchmarks` before the task is closed; the gate is that the existing
benchmarks do not regress beyond noise. If they do, the fallback is to hold the state in a `[ThreadLocal]`
cached in a `@JvmStatic` field read once per top-level `diff` — recorded here so the fallback is not
invented under pressure.

*Where it wraps:* the eleven runtime descent points named in *Context*, and nowhere else.
`compareValue` and `patchValue` do not descend and are not touched.

### 4. `Diff` becomes an `Iterable<Change>`, not a `List<Change>`

```kotlin
public data class Diff(public val changes: List<Change>) : Iterable<Change> {
    override fun iterator(): Iterator<Change> = changes.iterator()
    public val size: Int get() = changes.size
    public fun isEmpty(): Boolean = changes.isEmpty()
    public fun isNotEmpty(): Boolean = changes.isNotEmpty()
    public operator fun plus(other: Diff): Diff = ...
    public fun tree(): DiffNode
    public fun render(): String
    public companion object { public val EMPTY: Diff }
}
```

*Why `Iterable` and not `List<Change>` by delegation:* implementing `List` makes `Diff` equal to no
list (a `data class` equals only a `Diff`) while advertising list-ness, and it drags in `subList`,
`indexOf` and `listIterator` — twenty members of surface for the two anyone wanted. `Iterable` buys
every stdlib operator (`filter`, `any`, `groupBy`, `joinToString`) with one member, and `size` and
`isEmpty()` cover the rest.

`isEmpty` moving from property to function is a breaking rename by any other name, and it is the whole
reason to do it now: `isEmpty` as a property beside stdlib `isEmpty()` is a papercut every consumer
hits once.

`Diff(vararg Change)` is a secondary constructor, not a factory function, so `Diff()` reads as the
empty diff and `Diff(a, b)` as a two-change one.

### 5. Narrowing names properties by reference, reusing `Route`'s vocabulary

```kotlin
public fun <T> Diff.at(property: KProperty1<T, *>): Diff
public fun <T> Diff.under(property: KProperty1<T, *>): Diff
```

Extensions rather than members, because they need `T` and `Diff` is not generic — the same reason
`Diff.route<T>` is an extension. The names are deliberately `route`'s: `on`/`at` is the property
itself, `under` is the property and its subtree. Both compare `path.rootName()` against
`property.name`; `under` keeps a change whose first segment is that field, `at` keeps one whose *only*
segment is. `KProperty1` is stdlib — no `kotlin-reflect`, exactly as `differ { }` and `route { }`
already establish.

*Alternative rejected:* a `FieldPath`-prefix filter. It is more general and nobody can write the
`FieldPath` without matching a string, which is the thing being removed.

### 6. `@KdiffDsl` on six receivers, and one shared scope interface for the two scope builders

```kotlin
@DslMarker
public annotation class KdiffDsl
```

Applied to `DifferBuilder`, `TrackScopeBuilder`, `TrackerBuilder`, `ChangeRoutes`, `ElementRoutes`,
`KeyedElementRoutes`. The marker only bites where blocks nest, which is `route`'s `under`/`onEach` and
a `differ { }` whose `nested`/`subtype` argument is built inline — both reachable today, neither with a
defined meaning.

`TrackScopeBuilder` and `TrackerBuilder` declare the same five members. They become one interface:

```kotlin
@KdiffDsl
public interface ScopeDeclaration<T> {
    public var depth: Int
    public fun field(property: KProperty1<T, *>)
    public fun field(property: KProperty1<T, *>, depth: Int)
    public fun under(property: KProperty1<T, *>)
    public fun except(property: KProperty1<T, *>)
}
```

with one internal implementation both builders hold and delegate to (`by`). `TrackerBuilder` keeps its
own `scope`, `onFieldChange` and `onChange` on top. That is what makes "the two routes reject the same
scope" a structural fact rather than a test that has to be remembered — and it is where the missing
`field(property, depth)` validation gets fixed once instead of twice.

*Why an interface plus delegation, not inheritance:* `TrackerBuilder` is not a `TrackScopeBuilder` (it
does not build a `TrackScope`, it builds a `Tracker`), and delegation keeps the shared state in one
object with one `build()`.

*Breaking:* `TrackScopeBuilder`/`TrackerBuilder` members move to an inherited interface. Source-compatible
for every call site inside a builder block; binary-incompatible, which is what the API dump is for.

### 7. `contract { callsInPlace(block, EXACTLY_ONCE) }` on every builder entry point

`differ`, `trackScope`, both `tracker` overloads, `trackedDiff` (no block — skipped) and `Diff.route`.
Each becomes `public inline fun` with `@OptIn(ExperimentalContracts::class)`; `contracts` are stable in
Kotlin 2.x for this form. Making them `inline` is required for a contract on a lambda parameter and is
free here — the bodies are one line.

*Consequence to watch:* an `inline` public function fixes its body into consumers, so the body must
stay trivial (`Builder<T>().apply(block).build()`). Noted in the runtime KDoc.

### 8. Built-in KGP ABI validation, not the standalone plugin

Kotlin 2.4.10's Gradle plugin ships ABI validation: `kotlin { abiValidation() }`, tasks
`checkKotlinAbi` and `updateKotlinAbi`, dump checked in under `api/`. It is experimental and needs
`@OptIn(ExperimentalAbiValidation::class)`.

Chosen over `org.jetbrains.kotlinx.binary-compatibility-validator` 0.18.2 because the project's own rule
is to prefer the platform over a dependency, this *is* the platform, and the standalone plugin is where
the Kotlin team is migrating away from. Applied to the three published modules only, with
`checkKotlinAbi` wired into each module's `check`.

*Risk accepted:* an experimental DSL can move in a Kotlin upgrade. The mitigation is that the dump
format and the tasks are the contract, both trivially reproducible with the standalone plugin if the
built-in one becomes inconvenient — a one-file swap, recorded in *Risks*.

### 9. `.editorconfig` + ktlint for formatting; detekt 2.0.0-alpha.6 for analysis

- **`.editorconfig`** at the root stating what the sources already do: 4-space indent, 120-column
  limit, final newline, `ij_kotlin_*` import and wildcard settings. It is read by ktlint, by IntelliJ
  and by the Kotlin formatter, so one file serves the gate and the IDE.
- **ktlint** through `org.jlleitschuh.gradle.ktlint` 14.2.0 (ktlint 1.8.0), `ktlintCheck` into `check`,
  `ktlintFormat` as the fix command. Generated sources excluded via the plugin's `filter { exclude }`
  on `**/build/generated/**`.
- **detekt 2.0.0-alpha.6**, plugin id `dev.detekt`, Maven coordinates
  `dev.detekt:detekt-gradle-plugin` (2.x moved both off the 1.x `io.gitlab.arturbosch.detekt`
  namespace, so the 1.x id resolves no 2.x version). Version pinned exactly in
  `gradle/libs.versions.toml`, config checked in at `config/detekt/detekt.yml`, `ignoreFailures` left
  at its default so a finding fails, generated sources excluded via
  `tasks.withType<dev.detekt.gradle.Detekt> { exclude("**/build/generated/**") }`.
- **`allWarningsAsErrors = true`** on the Kotlin compilations lands alongside it: free, native, and
  the repository already has a `fix-compiler-warnings` change behind it.

*Why the alpha over stable 1.23.8:* 2.0.0-alpha.6 is built against **Kotlin 2.4.10, Gradle 9.6.1** —
this project's exact Kotlin and one Gradle patch behind it. Stable 1.23.8 embeds Kotlin 2.0.21, so it
would be analysing 2.4 sources with a four-minor-old parser: the risk of a spurious parse failure is
real, and the rules that need type resolution would be analysing against the wrong compiler. The alpha
removes both problems, and it makes the type-resolving `detektMain`/`detektTest` tasks usable, which
1.x could not safely offer here. Chosen deliberately over stability, on the user's call.

*Which tasks are wired:* `detektMain` and `detektTest` — the source-set variants, which enable type
resolution — rather than the plain `detekt` task, so the rules that need a classpath actually get one.
The plugin's own `check` wiring is verified during the task rather than assumed, and the type-resolving
variants are wired explicitly if it only wires the plain task.

*What pinning an alpha commits us to:* the version is pinned exactly, never a range, and it is a
`check` gate — so an alpha regression breaks the build rather than silently changing what is analysed.
The exposure is bounded by the config being checked in: if `alpha.7` changes a rule's behaviour, the
fix is a config line, and if it changes the plugin's DSL, the fix is to stay on `alpha.6` until 2.0.0
is stable. Handled in *Risks*.

*How formatting adopts — decided against the original plan, on measurement:* the first draft of this
design had the `.editorconfig` written *from* the sources, so adoption would reformat nothing. Running
ktlint proved that unaffordable: even at the gentler `intellij_idea` code style it rewrites **60 of 79
source files, ~4,200 lines**, and honouring "reformat nothing" would mean disabling about fifteen rules
— a gate that checks almost nothing while looking like coverage.

So the `.editorconfig` states `intellij_idea`, and **adoption reformats the sources once, in a commit
that contains nothing else**. That is what answers the original objection: the worry was a mass reformat
burying every other diff, and a commit of its own is exactly where a reviewer can read it in isolation.
A rule the project genuinely rejects — `standard:filename`, which would rename `Patch.kt` to `Patched.kt`
because that file holds one class beside the patch helpers that are its actual subject — is disabled
once in the config with a reason, which is what the spec permits. The handful of violations ktlint
cannot auto-correct are fixed in the sources rather than accommodated in the config.

### 10. Dokka 2.2.0, outside `check`

`org.jetbrains.dokka` 2.2.0 on the three published modules, `dokkaGenerate` run on demand. Deliberately
not wired into `check`: it is an artefact, and paying for it on every local check buys nothing. A later
change can publish it; this one just makes it producible.

## What lives in the runtime versus what is generated

Unchanged in principle, and this change pushes further the same way: every new behaviour is a runtime
declaration the generated code either already reaches or does not need to reach.

| new thing | where | why |
|---|---|---|
| `PatchFailure.Reason` cases | runtime | Every reason is produced by a runtime helper. The processor never writes one. |
| `DuplicateDiffKeyException` | runtime | Raised by `indexByKey`/`patchKeyedList`, both runtime. |
| `CyclicStructureException`, `Descent` | runtime | The eleven descent points are all runtime; generated code does not recurse. |
| `PatchResult.getOrThrow` | runtime | A member of a runtime type. |
| `Diff` collection members, `at`, `under`, `EMPTY` | runtime | `Diff` is a runtime type; generated code only constructs it. |
| `@KdiffDsl`, `ScopeDeclaration` | runtime | The DSL is hand-written by definition. |
| contracts | runtime | On runtime entry points. |

**Generated output after this change is byte-identical to generated output before it.** That is the
claim, and the sample's snapshot specs are what hold it: `Diff(List<Change>)` and
`PatchResult(value, failures)` keep their parameter lists, `PatchFailure` is still the `buildList` type
argument, and every helper keeps its name and signature. If a task finds it must change an emitter, that
is a signal the runtime change went further than designed and should be reconsidered rather than
followed.

## Type resolution: nullability, generics, collections, maps, enums, nested types

No resolution logic changes — the processor is untouched — but each shape meets the new runtime, so each
is a case the specs cover:

- **Nullable nested property**: `compareNestedNullable` is a descent point, so it enters `Descent`. A
  null on either side is still a `ValueChanged` at the property and descends into nothing, so it
  consumes no depth.
- **Generics**: a generic annotated class is still a compile error; nothing here relaxes that. Generic
  *helpers* (`Diff.at<T>`, `ScopeDeclaration<T>`) are ordinary `KProperty1` receivers with the same
  variance the existing builders use — `KProperty1<T, *>` reads any property of `T`.
- **Positional list, keyed list, map, set**: the four collection comparisons are descent points except
  `compareSet`, which never descends into an element (a set element has no identity to descend along) —
  so `compareSet` is *not* wrapped, and neither is `patchSet`. That asymmetry is deliberate and is
  stated in the runtime KDoc.
- **Enums**: compared by value through `compareValue`. No descent, no guard, no change.
- **Nested `@Diffable`**: `compareNested` — one descent step, one depth level, exactly as tracking
  depth already counts it. Guard depth and tracking depth count the same steps but are unrelated
  bounds; the KDoc says so, because "depth" meaning two things in one library is the obvious way to
  confuse a reader.
- **Sealed types**: subtype dispatch in `Dsl.kt` delegates to a subtype differ — a descent point in the
  hand-written route. The generated sealed differ delegates through `compareNested`-shaped calls, so it
  is covered by the same wrapping.

## Incremental processing

Unchanged. Every generated file still declares `Dependencies(aggregating = false, originatingFile)`,
and since the processor is not modified, no dependency edge moves. The runtime is a binary dependency,
so a runtime change recompiles consumers without regenerating anything.

The one build-level interaction: the ABI dump under `api/` is an input to `checkKotlinAbi`, not to
compilation, so it does not enter the KSP dependency graph and cannot cause a regeneration.

## Sample of the generated file after this change

Byte-identical to today's. Reproduced here because "unchanged" is the reviewable claim:

```kotlin
public object AddressDiffer : Differ<Address>, Patcher<Address> {
  private val comparedProperties: Set<String> = setOf("id", "street", "city")

  override fun diff(before: Address, after: Address): Diff = Diff(
    buildList<Change> {
      compareValue("id", before.id, after.id)
      compareValue("street", before.street, after.street)
      compareValue("city", before.city, after.city)
    },
  )

  override fun apply(before: Address, changes: List<Change>): PatchResult<Address> {
    val grouped = groupByProperty(changes, comparedProperties)
    val idPatched = patchValue(before.id, grouped.forProperty("id"))
    val streetPatched = patchValue(before.street, grouped.forProperty("street"))
    val cityPatched = patchValue(before.city, grouped.forProperty("city"))
    return PatchResult(
      before.copy(
        id = idPatched.value,
        street = streetPatched.value,
        city = cityPatched.value,
      ),
      buildList<PatchFailure> {
        addAll(grouped.unmatchedFailures("Address"))
        addAll(idPatched.failures)
        addAll(streetPatched.failures)
        addAll(cityPatched.failures)
      },
    )
  }
}
```

What a *consumer* of that object writes changes — `result.failures.first().reason` is now a case to
match, `diff.isEmpty` is now `diff.isEmpty()` — which is the point.

## Risks / Trade-offs

**The cycle guard costs something on the paths 0.3.1 optimised** → It does, and measurement settled
how much. Two rounds of tuning were applied before the number stopped moving:

1. The first cut stepped once per *element* inside the collection helpers, costing a thread-local read
   per element: **−4% to −10% across every comparison benchmark**.
2. The fallback recorded in decision 3 — fetch the state once per helper call and reuse it — recovered
   most of that.
3. The fix that actually mattered was realising the per-element step bought nothing: what the bound
   constrains is *recursion*, and a nested level enters a collection helper exactly once however many
   elements it holds. Stepping once per helper call left `compareKeyedList` and `compareSetAndMap` at
   parity.

What remains is `compareNested`, one thread-local read and one `try`/`finally` per nested property,
and it cannot be hoisted: `Differ.diff(before, after)` has nowhere to thread state through, which is
the constraint that forced a thread-local in the first place.

| benchmark | before | with guard | change |
|---|---|---|---|
| `compareKeyedList` | 0.256 ± 0.004 | 0.256 ± 0.005 | 0% |
| `compareOneLeafChanged` | 3.667 ± 0.148 | 3.712 ± 0.072 | 0% |
| `compareSetAndMap` | 3.296 ± 0.151 | 3.076 ± 0.181 | −6.7% |
| `compareUnchanged` | 4.334 ± 0.099 | 3.943 ± 0.153 | −9.0% |
| `compareSixLevelsDeep` | 7.451 ± 0.467 | 6.483 ± 0.085 | −13.0% |

**This fails the gate this section originally set** ("no regression beyond noise"), and the cost was
accepted deliberately rather than by omission: a `StackOverflowError` is not a diagnosis, and the cost
falls only on types with nested `@Diffable` properties — flat types and collections pay nothing. The
alternatives weighed and rejected were dropping the guard and putting it behind a system property; the
second was rejected because protection nobody switches on protects nobody.

**A `ThreadLocal` leaks if a descent is abandoned mid-flight** → Every `into` decrements in `finally`, so
an exception unwinds the counter. The identity list is cleared when the counter returns to zero, so a
thread that finished a comparison holds one small empty list, not a graph.

**`512` is a guess about stack frames** → It is. A depth-limited refusal is strictly better than a
`StackOverflowError` at *whatever* depth, so being wrong by 2× still improves on today. A spec scenario
pins that a deep-but-acyclic model says so, which is exactly the case a badly-chosen bound produces.

**detekt 2.0.0-alpha.6 is an alpha in the gate that decides whether a release can be cut** → Accepted
on the user's call, and bounded three ways. The version is pinned exactly, so no upgrade arrives by
itself. The rule config is checked in, so a rule whose behaviour moves in a later alpha is a
one-line config change rather than a scramble. And detekt is not the only analysis in `check` —
`allWarningsAsErrors` and ktlint stand on their own — so if the alpha has to be dropped for a release,
`check` still refuses a warning and a misformatted file. The escape hatch is `ignoreFailures = true`
for exactly one release, with a line in `CONTRIBUTING.md` saying it is set and why; reverting to
stable 1.23.8 is *not* an escape hatch, since its Kotlin 2.0.21 parser is the problem the alpha solves.

**detekt 2.x moved its plugin id and Maven group to `dev.detekt`** → Recorded in decision 9 with the
exact coordinates, because the 1.x id silently resolves nothing for a 2.x version and there is a known
alpha-series bug where the plugin looked for its own artifacts under the old group. Task 1.4 verifies
the plugin and its dependencies actually resolve before any rule config is written.

**ABI validation is experimental in KGP** → The `@OptIn` is one line and the tasks and dump format are
the contract, not the DSL. If a Kotlin upgrade breaks it, swapping in
`binary-compatibility-validator` 0.18.2 is a one-file change against the same checked-in dump layout.

**The breaking surface is wide for a library with published consumers** → All of it is pre-`1.0.0`,
where the README offers no compatibility guarantee, and every break is mechanical:
`isEmpty` → `isEmpty()`, a string comparison → a `when` branch, a `catch` that keeps working. The
migration table below is the whole of it.

**`inline` on public builder entry points fixes their bodies into consumers** → The bodies are
one-liners and are marked in KDoc as such. If a body ever needs to grow, the contract is what has to be
reconsidered, not the body quietly expanded.

**`inline` also couples the consumer's JVM target to the library's** → Found during implementation,
not anticipated here. Kotlin refuses to inline bytecode built for a higher JVM target than the code
being compiled, so `differ { }` in a module compiling at target 1.8 or 17 fails with *"Cannot inline
bytecode built with JVM target 21"*. It surfaced in the processor's compile-testing snippets, which
kctfork compiles at 1.8 by default; `CompileTesting.kt` now pins them to 21 and says why.

The constraint is smaller than it looks: `jvmToolchain(21)` already emits class-file major 65, so a
consumer needed a JVM 21 to load these classes at all. What is new is that they must also *compile*
at target 21 rather than merely run on one. It is recorded in the README's requirements rather than
left for a consumer to discover through that message, and it is the one real cost of the contracts —
worth stating plainly when weighing whether `callsInPlace` earns its place.

**Four axes in one change is a large diff** → Tasks are grouped so each axis is independently
reviewable and independently green: the build gates land first (so every later commit is already
checked by them), then the error types, then `Diff`, then the DSL work, then docs. Each group ends at a
green `./gradlew check`.

## Migration Plan

For a consumer on `0.3.1`, in the order the compiler will report them:

| before | after |
|---|---|
| `if (diff.isEmpty)` | `if (diff.isEmpty())` |
| `diff.changes.filter { … }` | `diff.filter { … }` (the old form still works) |
| `failure.reason == "no element with this key to patch"` | `failure.reason is PatchFailure.Reason.NoElementForKey` |
| `failure.reason` in a log line | `failure` — `toString()` renders path and reason as before |
| `catch (e: IllegalArgumentException)` around a keyed comparison | unchanged; narrow to `DuplicateDiffKeyException` to read the key |
| a `StackOverflowError` on a cyclic model | `CyclicStructureException` |
| `result.value` after checking `isClean` | `result.getOrThrow()` |

Rollback is a version pin: nothing in this change writes state, migrates data or alters generated
output, so `0.3.1` remains a working release for anyone who does not want the new surface.

Release: one minor version with `!` and a `BREAKING CHANGE:` footer on each breaking commit, and a
`CHANGELOG.md` section written before the tag, per the repository's release rule.
