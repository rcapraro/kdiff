## 1. Module scaffold

- [x] 1.1 Add `kdiff-tutorial` to `settings.gradle.kts` and create
  `kdiff-tutorial/build.gradle.kts` applying the KSP and `application` plugins, depending on the three
  library modules with the processor on `ksp(...)` (design D1) — verify
  `./gradlew :kdiff-tutorial:compileKotlin` succeeds and `gradle/libs.versions.toml` is unchanged
- [x] 1.2 Confirm the new module is not published and not held to `explicitApi()` — verify
  `./gradlew publishToMavenLocal` produces artifacts for the three published modules only, with no
  `kdiff-tutorial` publication

## 2. Domain model

- [x] 2.1 Add `tutorial/domain/Identifiers.kt` with `PersonId` and `AddressId` as single-property
  `@Diffable data class`es, each overriding `toString` to return `value` so keyed paths read
  `addresses[id=A1]` rather than `addresses[id=AddressId(value=A1)]` (design D9, constraints 2 and 3) —
  verify a keyed-list diff renders the short form
- [x] 2.2 Add `tutorial/domain/Address.kt`: `@Diffable data class Address(@DiffKey val id: AddressId,
  val line1: String, val city: String, val postalCode: PostalCode, val country: Country, val kind:
  AddressKind)` plus the nested `PostalCode`, `Country` and the `AddressKind` enum — verify a change
  inside an address reports at `addresses[id=A1].postalCode.value`
- [x] 2.3 Add `tutorial/domain/ContactMethod.kt` as a `@Diffable` sealed interface declaring
  `val label: String`, with `Email`, `Phone` and `Postal` data-class subclasses — verify a subclass swap
  at one index reports both a `TypeChanged` and a `label` comparison, per the sealed-parent rule
- [x] 2.4 Add `tutorial/domain/Employment.kt` as a `@Diffable` sealed interface declaring **no**
  properties, with `Employed`, `SelfEmployed` and `Retired` data classes — no `data object`, which
  `@Diffable` rejects (design D9, constraint 1) — verify a subclass change produces the type change
  alone
- [x] 2.5 Add `tutorial/money/Money.kt`: a plain (non-data) class with a hand-written `MoneyDiffer`
  implementing both `Differ` and `Patcher` via `differ { }` plus `groupByProperty`/`patchValue`
  (design D7) — verify `MoneyDiffer` round-trips a change through `apply`
- [x] 2.6 Add `tutorial/domain/PersonState.kt`: `@Diffable @Trackable` with `id`, `name: FullName`,
  `nickname: String?`, `@TrackDepth(2) addresses`, `contacts`, `employment`,
  `@DiffWith(MoneyDiffer::class) salary`, `tags: Set<String>`, `externalIds: Map<String, String>`,
  `@TrackIgnore lastSeenAt` and `@DiffIgnore version` (design D9) — verify
  `./gradlew :kdiff-tutorial:kspKotlin` generates a differ for every annotated type
- [x] 2.7 Verify the generated `PersonStateDiffer.trackScope` matches design D10 exactly — that
  `lastSeenAt` is absent through `@TrackIgnore`, `version` is absent through `@DiffIgnore`, and
  `addresses` carries depth 2 — with a Kotest spec asserting the emitted `trackedFields`

## 3. Aggregate and events

- [x] 3.1 Add `tutorial/event/PersonEvent.kt`: a sealed `PersonEvent` carrying `personId`, with typed
  events per concern each carrying its own before/after — `PersonRenamed`, `NicknameChanged`,
  `AddressAdded`, `AddressRemoved`, `AddressesReordered`, `AddressEdited`, `ContactsReplaced`,
  `EmploymentChanged`, `SalaryAdjusted` (design D5) — verify no event exposes a `Change` or a
  `FieldPath`, keeping kdiff out of the event vocabulary
- [x] 3.2 Add `tutorial/domain/Person.kt`: a mutable aggregate with a private constructor, a
  `rehydrate` factory, a `snapshot: PersonState`, and recorded events (design D6) — verify `events`
  returns a defensive copy
- [x] 3.3 Implement one aggregate method per concern, each reading `before` from its own state before
  mutating and each guarded against a no-op call (design D5) — verify a Kotest spec shows that calling
  `rename` with the current name records nothing
- [x] 3.4 Verify each method works when called directly, with no diff involved — a Kotest spec that
  drives the aggregate without a tracker, proving the domain does not depend on kdiff

## 4. Command flow

- [x] 4.1 Add `tutorial/command/PersonDto.kt` with `PersonDto` and `projectOnto(current): PersonState`,
  carrying identity and version across untouched (design D2) — verify the projection of an unchanged
  DTO produces a state that diffs clean against the original
- [x] 4.2 Add `tutorial/command/UpdatePerson.kt` and `tutorial/app/PersonRepository.kt` (interface plus
  an in-memory map) — verify a load-modify-save round trip
- [x] 4.3 Add `tutorial/app/UpdatePersonHandler.kt` using a `Tracker` over the current state, updated
  with the projection, so the type's declared scope decides what counts as a change (design D3) — verify
  a command that touches only `lastSeenAt` emits **no** events, while a diff of the same two states
  reports it
- [x] 4.4 Implement the dispatch: a local `FieldPath.root()` helper, a `when` on the root segment, and an
  exhaustive inner `when` over `Change` for the keyed `addresses` list handling `Added`, `Removed`,
  `Moved` and deeper edits (design D4) — verify a single command that renames, adds one address, removes
  another, reorders a third and edits a fourth emits one event per operation, in change order
- [x] 4.5 Verify the `else` branch is reached and is correct: a command changing only `tags` or
  `externalIds` is tracked and audited but emits no domain event (design D4) — a Kotest spec asserting
  the audit callback fired and `events` is empty
- [x] 4.6 Add the narrower hand-written `trackScope { }` variant for `name` alone, and the
  `onFieldChange`-wired tracker variant, with the reason the latter stops at scalars (design D3, D4) —
  verify both compile and are covered by a spec

## 5. Runnable entry point

- [x] 5.1 Add `tutorial/app/Main.kt` submitting a seed and two commands and printing the emitted events
  (design D1) — verify `./gradlew :kdiff-tutorial:run` prints a stream containing `PersonRenamed`,
  `AddressAdded` and `AddressesReordered`, and capture the real output for the tutorial
- [x] 5.2 Verify the printed output is stable across runs, so the tutorial can quote it — check no map
  or set iteration order leaks into the output

## 6. The tutorial

- [x] 6.1 Write `docs/tutorial.md` introducing the model in layers — scalars and nesting, then the keyed
  list, then the sealed types, then the collections — never showing all nine properties in one block
  (design, Risks) — verify every Kotlin sample carries a `from:` marker into `kdiff-tutorial`
- [x] 6.2 Write the projection section, stating plainly that a differ compares two instances of one type
  and that this is why a DTO must be projected before it can be diffed (design D2) — verify the sample
  traces to `PersonDto.kt`
- [x] 6.3 Write the tracking section, showing that the tracking scope is where "what counts as a change"
  is decided, with `@TrackIgnore lastSeenAt` as the worked case (design D3) — verify the sample traces
  to `PersonState.kt` and `UpdatePersonHandler.kt`
- [x] 6.4 Write the dispatch section, including why the outer `when` needs an `else` and why the inner
  `when` over `Change` does not (design D4) — verify the sample traces to `UpdatePersonHandler.kt`
- [x] 6.5 Write the events section, explaining why events carry domain values rather than the `Change`
  that produced them (design D5) — verify the sample traces to `PersonEvent.kt` and `Person.kt`
- [x] 6.6 Write the both-routes section: the annotated domain, and `Money` compared by a hand-written
  differ through `@DiffWith` (design D7) — verify the sample traces to `Money.kt`
- [x] 6.7 Add the three type-resolution constraints as a short section a reader hits *before* they trip
  over them — no `data object` in a sealed hierarchy, `value class` is not a value type, and keys render
  through `toString` (design D9) — verify each claim against `openspec/specs/diff-generation/spec.md`
- [x] 6.8 Quote the real `./gradlew :kdiff-tutorial:run` output captured in 5.1, and link onward to the
  six reference guides rather than restating them
- [x] 6.9 Link the tutorial from `README.md`'s documentation table and from `docs/architecture.md` —
  verify both links resolve

## 7. Harness input fix

- [x] 7.1 Declare the verified source trees as inputs of `:kdiff-sample:test` —
  `kdiff-sample/src`, `kdiff-tutorial/src` and `kdiff-runtime/src/main`, the last of which
  `docs/diffing.md` and `docs/patching.md` already cite (design D8) — verify the inputs are declared
- [x] 7.2 Prove the fix by perturbation, not assertion: change a distinctive line in a
  `kdiff-tutorial` source file that a marked sample cites, run `./gradlew check`, and confirm the docs
  check **re-runs and fails** rather than reporting `UP-TO-DATE`; restore and confirm it passes
  (design D8) — report both observed outcomes
- [x] 7.3 Repeat 7.2 for a `kdiff-runtime/src/main` file cited by `docs/diffing.md`, since that gap
  exists today independently of the tutorial

## 8. Verification

- [x] 8.1 Confirm no file under `kdiff-annotations`, `kdiff-runtime` or `kdiff-processor` `src/` changed
  — verify by listing every file this change touched
- [x] 8.2 Confirm the generated sources of `kdiff-sample` are byte-identical to before this change,
  proving the new module changed nothing for the existing one — verify with
  `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and a comparison
- [x] 8.3 Confirm `Dependencies(aggregating = false, originatingFile)` still holds and is observable in
  the new module: touching `Address.kt` regenerates `PersonStateDiff.kt`, and touching an unrelated file
  does not (design D11) — report what was observed
- [x] 8.4 Verify every internal link in `docs/tutorial.md` resolves and every `from:` marker names a
  file that exists
- [x] 8.5 Run `./gradlew check` and report the result verbatim
