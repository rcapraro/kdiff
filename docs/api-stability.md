# API stability

What `1.0.0` will promise, what it will not, and which questions were answered "no" and why. Written
before the tag rather than after it, so that adopting kdiff before `1.0.0` is a decision made with the
same information a reader will have afterwards.

**Every promise on this page takes effect at `1.0.0`.** Until then a minor version may still break
anything here, with a migration note in [the changelog](../CHANGELOG.md) — including a promise on this
page, which is what "written before the tag" is for. Read it as the shape the library is committing to,
not as a guarantee already in force.

## 1. What is recorded

The public API of the three published modules — `kdiff-annotations`, `kdiff-runtime` and
`kdiff-processor` — is recorded as a dump in each module's `api/` directory, and `./gradlew check`
fails when the code and the dump disagree. Adding or removing a public declaration therefore cannot
happen quietly: the dump moves in the same commit, and that diff is the review surface for the change.

A change that **removes or alters** an entry in one of those dumps is breaking. Before `1.0.0` a minor
version may make one, with a migration note in [the changelog](../CHANGELOG.md); after `1.0.0` only a
major version may. Additions are not breaking, and §2 says what may be added to.

**The dump records more than it promises.** It is a detector, not a description: two kinds of entry are
in it because the platform requires them to exist, not because they are yours to call.

| in the dump | why it is there | for example |
|---|---|---|
| a declaration published so that an `inline` function can reach it | `route { }` and `onEach { }` are inlined into your module and have to reach the routing internals from there | `ChangeRoutes.register`, `ChangeRoutes.dispatch`, `ElementRoutes.dispatch`, and the routing classes' constructors |
| the accessors an inline `value class` lowers to | `FieldPath` is a `value class`, so reading `Change.path` compiles to a getter whose name carries a hash of the signature | `FieldPath.box-impl`, `FieldPath.constructor-impl`, and the mangled `getPath-…` on each `Change` variant |

They are recorded like anything else, so their removal still fails the build — which is what a
mechanical dump is for, and why they are not filtered out of it. What they are not is API: nothing in
this documentation names one. The mangled accessors are also the concrete reason for §6's "not designed
for Java consumers" — `change.getPath()` is not a method that exists.

`kdiff-processor`'s dump holds a single declaration, `DiffProcessorProvider`, which the compiler loads
through the module's service registration. Nothing else in that module is public, so the module makes
no other promise — and a dump of exactly one entry is what fails the build the day a helper class
becomes public by accident.

## 2. What is closed, and what may grow

Two vocabularies are sealed, so nothing outside the library declares a case of either. They are not
closed on the same terms, because a caller does different things with them.

**`Change` is closed.** `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved`; a sixth variant is
breaking, on the same terms as a removal. A change is what a caller *dispatches* on — `Diff.route`, a
`when` over the variants, `tree()`, `render()` — and handling them exhaustively is the reason the type
is sealed at all, so growing it would take away the thing it is for. →
[diffing.md](diffing.md#the-change-vocabulary-is-closed)

**`PatchFailure.Reason` may gain a case in a minor version.** Fourteen today. A reason is what a caller
*reports*, not what it dispatches on, and both releases that taught kdiff to apply a shape it used to
refuse wanted new ones — so freezing the set for the length of `1.x` would make the next supported
shape choose between a major version and a reason that misdescribes itself.

Two things bound what an addition can do. Nothing outside the library can declare a case, so the set
stays inspectable and every case still carries its facts as properties. And rendering is total:
`PatchFailure.toString()` covers every case, including one declared after your code was written, so a
caller that logs failures cannot be affected by an addition. A caller that branches writes an `else`:

<!-- illustrative -->
```kotlin
when (val reason = failure.reason) {
    is PatchFailure.Reason.NoElementForKey -> reinstate(failure.change)
    else -> log(failure)
}
```

**If you already wrote an exhaustive `when` with no `else`**, on the strength of `0.7.0` calling this
vocabulary closed, add one. That code compiles today and against every release that adds nothing; the
release that adds a case makes it a compile error on recompilation, and — because Kotlin lowers an
exhaustive `when` to a throw on the branch it thinks unreachable — a `NoWhenBranchMatchedException` at
runtime if it is *not* recompiled. This is the one direction in which §1's "additions are not breaking"
does not hold, and it is why the loosening is happening before `1.0.0` rather than after it.

→ [errors.md](errors.md#3-changes-that-did-not-apply)

The refusals are declared types rather than a message to match on:

| Type | Extends | Raised when |
|---|---|---|
| `DuplicateDiffKeyException` | `IllegalArgumentException` | two elements of a keyed list share one key |
| `CyclicStructureException` | `IllegalArgumentException` | a structure is deeper than `MAX_DESCENT` |
| `PatchFailedException` | `IllegalStateException` | `PatchResult.getOrThrow()` is asked for a value it has not got |

There is deliberately **no common supertype**. A marker interface cannot be caught, and a shared class
would have to root at one of those two stdlib types and misfile the other. The first two extend
`IllegalArgumentException` because that is what the runtime raised before they were declared, so a
`catch` written against it keeps working.

## 3. What is a data class

A data class fixes a type's shape: `copy` and `componentN` are public API, so a property cannot be
added without breaking them. kdiff keeps `data` exactly where that cost buys something, by one
criterion:

> **A type stays a data class when its properties are its whole meaning, destructuring them is a use,
> and the set will not grow.**

| Type | Data class? | Why |
|---|---|---|
| `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved` | yes | a closed vocabulary; `(path, before, after)` destructuring is idiomatic, and the fields cannot grow without a new variant, which §2 already makes breaking |
| `Segment.Field`, `Segment.Index`, `Segment.Key` | yes | the same |
| `PatchResult` | yes | `val (value, failures) = OrderDiffer.apply(…)` is the natural read |
| `PatchFailure` | yes | `(change, reason)` |
| `DiffNode` | yes | `(segment, changes, children)`; a tree node's shape is fixed |
| `TrackedField` | yes | `(name, depth)` |
| `Diff` | **no** | a container with behaviour. `copy(changes = …)` is the constructor spelled longer, nothing destructures a diff, and both would fix `Diff`'s shape forever |

`Diff` keeps its constructor, its `changes`, and equality and hash code over those changes. Its
`toString()` is its [rendering](diffing.md#viewing-a-diff).

## 4. What generated code promises

`@Diffable` on a type generates, in that type's own package, exactly this:

<!-- illustrative -->
```kotlin
public object <Type>Differ : Differ<Type>, Patcher<Type>          // and Tracked<Type> with @Trackable
```

That declaration — its name, its package, the interfaces it implements and their members — is the
promise. **Nothing else in the generated file is API**: not its private members, not the local variable
names inside `apply`, not which runtime helpers it calls, and not the layout of the emitted text. Those
change whenever a fix or an optimisation calls for it, and a consumer that reads the generated source
rather than the interfaces is reading an implementation.

**The runtime helpers are contract.** `groupByProperty` and `unmatchedFailures`, the `compare*` family,
the `patch*` family, and `unpatchable` and `notConstructorProperty` beside them, are public for two
reasons, and only the first is about generated code. The second is that
`Patcher` has no builder — a builder cannot know how to construct the property's owner — so composing
those helpers *is* the hand-written route, and
[hand-written.md](hand-written.md#when-the-dsl-is-not-enough) teaches it as such. Demoting them would
demote the escape hatch.

They evolve by addition rather than by change: a new helper, or a new overload of one. `0.6.0` is the
shape of that — four compare helpers were widened to accept a nullable side, source- and
binary-compatibly, and nothing in the dump moved.

**What may grow.** Three additions are open inside `1.x`, and each says what code written before it
still does:

| addition | what a consumer written before it does |
|---|---|
| a further `PatchFailure.Reason` case | keeps compiling if it branches with an `else`; §2 |
| a member on `Differ`, `Patcher` or `Tracked`, carrying its own implementation | keeps compiling; an `object : Patcher<T>` inherits the implementation, and `Differ<T> { … }` still converts, because a `fun interface` admits non-abstract members. A member *without* an implementation is breaking |
| a further capability interface on the generated object | keeps compiling; the object's name, its package and the members of the interfaces it already implements do not move |

A new helper, a new overload and a new annotation are additions in the ordinary sense of §1.

## 5. What is not API

- **Message text.** Every diagnostic and every failure sentence is quoted in [errors.md](errors.md) so
  it can be recognised, and any of them may be reworded. Branch on a `PatchFailure.Reason` case or an
  exception type, never on a string.
- **`render()` and `tree()` output layout.** Column alignment, padding and the wording of `ADDED`,
  `REMOVED`, `MOVED` and `TYPE` are for a human reader.
- **`toString()` layout**, on any type — `Diff` included, whose `toString` is `render()` and inherits
  that freedom.
- **The order of failures within one `apply`.** Which changes failed is API; the sequence they are
  reported in is not.

## 6. Platform

**kdiff targets JVM 21.** Chosen, not inherited: it is the platform the library is built for, and two
things follow from it rather than justify it.

- The class files require a JVM 21 to load, since that is what the toolchain emits.
- A consuming module must also **compile** at target 21. Several entry points are `inline` — so that
  they can state they run your block exactly once — and Kotlin refuses to inline bytecode built for a
  higher target than the code being compiled. They are `differ { }`, `trackScope { }`, `tracker { }`,
  `Diff.route` and `ChangeRoutes.onEach`, so the requirement reaches further than the hand-written
  route: a consumer who only ever routes a generated differ's diff inherits it too.

Kotlin/JVM only. There are no multiplatform targets, and the annotations are not designed for Java
consumers — §1's mangled `value class` accessors are the concrete reason. No reflection library:
property references are stdlib, so `kotlin-reflect` is not a dependency and is not required at runtime.
`kdiff-annotations` and `kdiff-runtime` each depend on no kdiff module and no third-party library,
carrying nothing but the Kotlin standard library; `kdiff-processor` is compile-time only, applied
through the `ksp` configuration, and is never on a consumer's runtime classpath.

## 7. Kotlin and KSP

Code generation is a compiler plugin, so kdiff is coupled to the compiler in two places a consumer can
feel. Neither is a version range the library can widen by declaring one; both are stated so the
coupling is a known cost rather than a surprise.

```
  your Kotlin compiler                 your KSP plugin
        |                                    |
        | reads kdiff-runtime's and          | loads kdiff-processor, which is built
        | kdiff-annotations' metadata        | against one symbol-processing-api
        v                                    v
  a compiler older than the one        KSP's plugin version tracks Kotlin's, so a
  that built them refuses them         later Kotlin means a later KSP running a
  outright                             processor built for an earlier one
```

Each release names the pair it was built against — the badge in the [README](../README.md) and the
install snippet carry them, and the [changelog](../CHANGELOG.md) entry says so when they move. From
that:

- `kdiff-annotations` and `kdiff-runtime` require a Kotlin compiler **at least** the version they were
  built with. An older one reports *"class file was compiled with a newer version of Kotlin"*.
- `kdiff-processor` expects the KSP plugin that matches **your** Kotlin version, which is the plugin
  you apply anyway. It is built against one `symbol-processing-api` and KSP's own API is not versioned
  as stable, so a Kotlin minor is not assumed to work until it has been tried.
- **A Kotlin minor is tracked by a kdiff minor.** When Kotlin moves, kdiff follows with a release
  naming the new pair. Nothing else about the library's promises changes with it.

Only the declared pair is tested. Nothing in this repository compiles against another Kotlin version,
so no claim is made about one.

## 8. Decided, and not done

Four questions that get asked, with the answer and the reason, so that the next person to ask finds
one.

**A typed `Diff<T>`.** Declined. A type argument would make `plus` across two diffs of different types
awkward, would leave `EMPTY` needing a type it does not have, and would fight the lifting of a nested
differ's changes into its parent — which is exactly a diff of one type becoming part of a diff of
another. Where dispatch needs a type, [`Diff.route<T> { }`](diffing.md#deciding-what-a-change-means-route)
already provides one and checks every property it names against it.

**`TypeChanged` carrying `KClass` sides rather than type names.** Declined. The change already carries
both instances, so `before::class` is one expression away, and a name is what survives being logged,
serialised or compared across a process boundary.

**A common `KdiffException` supertype.** Declined, per §2: a marker interface cannot be caught, and the
two roots are different stdlib types on purpose.

**An experimental tier — a `@KdiffExperimental` opt-in marker.** Declined. A marker buys the ability to
ship something without promising it. It costs an `@OptIn` at every call site that touches the marked
API, it makes two halves of one DSL read differently where they meet, and it becomes the place a
decision is deferred to instead of made. kdiff's contract is small and §4 already leaves three
directions open additively, so the marker would guard against a risk the shape of the API handles.

The cost of not having one, stated plainly: **an addition in `1.x` is permanent from the release that
makes it.** That is a reason to add slowly, which is a discipline rather than a mechanism.

## Where to go next

- [Errors](errors.md) — every message, quoted, with what to change
- [Architecture](architecture.md) — the module graph and [what kdiff does not do](architecture.md#what-kdiff-does-not-do)
- [Changelog](../CHANGELOG.md) — the only description of what a version changed
