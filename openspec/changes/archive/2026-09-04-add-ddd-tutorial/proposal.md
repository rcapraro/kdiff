## Why

The six guides in `docs/` explain kdiff feature by feature. None of them shows the shape of a real
application, and the one worked example — `kdiff-sample`'s `Order` — exists to exercise every
comparison rule, not to demonstrate a design. A reader who understands `@Diffable`, `apply` and
`tracker { }` individually still has to invent the interesting part for themselves: what a diff is
*for*.

There is a specific pattern worth showing, because it is the one that makes kdiff more than a
convenience. Given the current state of an aggregate and a command carrying the state a caller
*wants*, the diff between them is precisely the set of things that command is asking to change.
Dispatching on that diff turns an incoming DTO into domain operations, and the before/after each
change carries becomes the payload of the domain event the operation emits.

This change adds a tutorial that builds that end to end, against a domain model deliberately rich
enough to exercise nested value objects, sealed hierarchies, a keyed list, a positional list of
sealed elements, a set and a map — and which uses both authoring routes, annotations and the DSL.

## What Changes

- **New module `kdiff-tutorial`.** A runnable DDD example: domain, commands, events and a command
  handler. Not published — the root build publishes only the three named modules, so a new module is
  excluded automatically.
- **New: `docs/tutorial.md`.** A single linear narrative, since a tutorial is read start to finish.
  Every Kotlin sample carries a `from:` marker into `kdiff-tutorial`, so the existing documentation
  harness verifies it.
- **New: a domain model that earns the word "complex."** `PersonState` with a nested `FullName` and
  `PersonId`, a keyed `List<Address>` (itself nesting `PostalCode` and `Country`), a positional
  `List<ContactMethod>` whose elements are a sealed hierarchy, a sealed `Employment`, a
  `Set<String>`, a `Map<String, String>`, a nullable property, and a `@DiffIgnore` property.
- **New: both authoring routes, side by side.** The domain is annotated; `Money` stands in for a type
  whose source cannot be annotated and is compared by a hand-written `differ { }` plus a hand-written
  `Patcher`, reached through `@DiffWith`. The handler shows the declared tracking scope and a
  hand-written `trackScope { }` alternative.
- **New: the command flow.** `UpdatePerson(id, PersonDto)` → the DTO is projected onto the current
  state to produce the desired state → a `Tracker` reports what actually changed → an exhaustive
  `when` on each change's root segment calls the matching aggregate method → the aggregate emits
  typed events carrying before and after.
- **New: `run` support** on the tutorial module, so `./gradlew :kdiff-tutorial:run` submits commands
  and prints the resulting event stream.
- **Modified: the documentation harness inputs.** See below — this is the one thing in the change
  that is a fix rather than an addition.

**Modules affected:** a new `kdiff-tutorial`; `settings.gradle.kts` to include it; and
`kdiff-sample/build.gradle.kts` for the harness input fix. `kdiff-annotations`, `kdiff-runtime` and
`kdiff-processor` are untouched.

**The generated API surface does not change.** No annotation is added, removed or altered, and no
generated declaration changes shape. `@Diffable`, `@DiffKey`, `@DiffIgnore`, `@DiffWith`,
`@Trackable`, `@TrackIgnore` and `@TrackDepth` behave exactly as `diff-generation` specifies. The
tutorial is a *consumer* of the library at its published API. **Not BREAKING.**

### A defect this change has to fix to be trustworthy

`DocumentationSamplesSpec` lives in `kdiff-sample` and declares `README.md`, `CONTRIBUTING.md` and
`docs/` as task inputs. It does not declare the *source trees it verifies against*. That is invisible
today because the only sources it checks belong to `kdiff-sample` itself, whose test task already
re-runs when they change.

The moment a sample points into `kdiff-tutorial`, that stops being true: editing tutorial source
would leave `:kdiff-sample:test` `UP-TO-DATE`, and `check` would pass on a stale result — the same
class of failure that made the harness briefly inert when it was first written. So this change
declares the verified source trees as inputs, and proves it by perturbing tutorial source and
observing the failure.

### Assumptions

- **"Which field is moved" is read two ways, both honoured.** Loosely it means "which field changed",
  and the dispatch handles every change kind. Literally, `Moved` is kdiff's own change kind for a
  keyed element that changed position — and since a list of addresses was asked for, the tutorial
  makes `addresses` keyed so `Added`, `Removed` and `Moved` each dispatch to a distinct aggregate
  method. If only the loose reading was meant, the reorder path is still correct, just unused.
- **The DTO does not mirror the aggregate one-to-one.** It carries no identity and no version, and is
  projected onto the current state to yield the desired state. Diffing requires both sides to be the
  same type, so the projection is what makes the pattern work at all; the tutorial says so explicitly
  rather than leaving a reader to discover it.
- **Persistence is an in-memory map.** A repository interface with a map behind it. A real store would
  add nothing to what the tutorial teaches and a great deal to what it has to explain.
- **Events are recorded on the aggregate and returned by the handler**, not published to a bus. Where
  they go is an application concern the tutorial does not need an opinion on.

### Recorded, not fixed

Writing a tutorial means using the library as a consumer for the first time, which tends to surface
missing conveniences. One is already visible: reading a change's root property means
`(change.path.segments.firstOrNull() as? Segment.Field)?.name`, and the dispatch needs it for every
change. The tutorial will show a small local helper for it.

Any such finding is recorded as a follow-up, not fixed here. Adding to `kdiff-runtime`'s public API
would change library behaviour, need a spec delta, and turn a tutorial into an API change.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. This change declares skip_specs: true. -->

A tutorial, an example module and a build-input fix change no observable behaviour of the library.
`openspec/specs/` describes what kdiff does — diff generation, diff application, change tracking —
and this change describes how to use it. No requirement changes, and none is invented to satisfy
validation. The tutorial's value as verification is that it exercises the *existing* requirements
from the outside.

## Impact

- **New:** `kdiff-tutorial/` (build script, `src/main` domain/command/event/app, `src/test` specs) and
  `docs/tutorial.md`.
- **Modified:** `settings.gradle.kts` (include the module); `kdiff-sample/build.gradle.kts` (declare
  verified source trees as harness inputs); `README.md` and `docs/architecture.md` gain a link to the
  tutorial.
- No change to any file under `kdiff-annotations`, `kdiff-runtime` or `kdiff-processor`.
- `gradle/libs.versions.toml` untouched — the `application` plugin ships with Gradle, and the module
  needs only the existing Kotest dependencies.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
