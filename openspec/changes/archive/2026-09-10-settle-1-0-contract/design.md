## Context

The library is feature-complete for `1.0.0`. What is not settled is the wording of the promise, and
the wording has consequences that outlive any implementation: after the tag, every sentence in
`docs/api-stability.md` costs a major version to soften.

Two constraints shape every decision below.

**JVM 21 is the target, decided.** Not a constraint to work around and not a cost to minimise. What
follows from it is stated rather than re-derived: class files a JVM 21 loads, and `inline` entry points
that a consumer must *compile* at target 21 to use. Nothing here removes an `inline`, lowers
`jvmTarget`, or reopens `callsInPlace`.

**Room to evolve beats a tight contract.** Where a promise can be written two ways, the one that
leaves the library able to grow wins, provided the looser wording still lets a caller write code that
cannot silently break. That principle is what separates the two closed vocabularies below, and it is
why no experimental tier is introduced.

## Goals / Non-Goals

**Goals**

- State the promised surface and the recorded surface as two things, so the dump can stay the gate it
  is without being read as the promise.
- Name the four directions in which the library may grow inside `1.x`, each with what a consumer
  written before the growth still does.
- Write down the Kotlin and KSP compatibility position, which nothing in the repository states today.
- Reframe JVM 21 as a target.

**Non-Goals**

- Any mechanical check. Every gate that would defend this contract is `guard-the-release`; this change
  is what those gates would be defending.
- Any change to comparison, application or tracking behaviour. Not one diff moves.
- Renaming, re-packaging or re-tiering the runtime helpers. Investigated and rejected — see D4.
- The `1.0.0` bump. This change makes the tag defensible; it is not the tag.

## Decisions

### D1 — The record is a detector; the promise is prose

The ABI dump answers "did the surface move?". It cannot answer "is this mine to call?", because three
kinds of entry are in it that no consumer should name:

```
  entry in kdiff-runtime.api                          why it is there
  --------------------------------------------------  --------------------------------------------
  ChangeRoutes.register, ChangeRoutes.dispatch,       @PublishedApi internal — `route {}` and
  ElementRoutes.dispatch, the three internal          `onEach {}` are inline and must reach them
  constructors of the routing classes                 from a consumer's compilation unit

  FieldPath.box-impl, FieldPath.constructor-impl,     value class lowering; `Change.path` is
  getPath-XiclW14 on all five Change variants         mangled because FieldPath is a value class

  DiffProcessor and its four members                  a KSP entry point the compiler loads through
  (in kdiff-processor.api)                            META-INF/services
```

**Chosen**: keep the dump exactly as it is produced and checked, and add to `api-stability.md` §1 a
paragraph naming these three categories. Removal of any of them still fails the build, which is what
the dump is for.

**Rejected — annotate or filter them out of the dump.** The KGP ABI validation filters are a coarse
instrument, and a filtered entry is one whose disappearance stops failing the build. The dump's value
is that it is mechanical and complete; the discrimination belongs in prose, which is where every other
"this is not API" statement in §5 already lives.

**Consequence for §1's own wording**: the sentence "the public API … is recorded as a dump" becomes
"the dump records the public API and, unavoidably, three kinds of entry that are not part of it".

### D2 — `DiffProcessor` becomes internal

The third row above is the one category that can simply be removed rather than described.
`DiffProcessorProvider.create` returns `SymbolProcessor`; nothing outside the module names the
implementation.

**Chosen**: `internal class DiffProcessor`. `kdiff-processor.api` then holds one class. The module's
own tests are in the same module, so `internal` is reachable from them; and the processor's tests reach
it through kctfork and the service registration anyway.

**Rejected — drop `explicitApi()` and the dump from `kdiff-processor` entirely.** The dump holding
exactly one declaration is a stronger statement than no dump: it fails the build the day a helper
class is accidentally made public, which is precisely the drift worth catching in a module nobody
reads the API of.

This is recorded as **BREAKING** because a recorded declaration disappears. The repository applies that
rule without exception, and the alternative — an exception for "nobody could have called it" — is a
judgement the next removal would cite.

### D3 — `Change` stays closed; `PatchFailure.Reason` opens

The two vocabularies look alike and are used for opposite things.

```
  Change                                    PatchFailure.Reason
  --------------------------------------    --------------------------------------
  what a caller DISPATCHES on               what a caller REPORTS
  route {}, when(change), tree(), render    log it, count it, surface it to a user
  a sixth variant breaks every exhaustive   a fifteenth case breaks an exhaustive
  `when` -- and exhaustive dispatch is      `when` -- and exhaustive matching is
  the reason the type is sealed             rare; rendering is the common use
  5 cases, stable since 0.1.0               14 cases; 0.6.0 and 0.7.0 each wanted
                                            new ones for newly supported shapes
```

**Chosen**: `Change` keeps the old terms — adding a variant is breaking. `Reason` stays sealed, so
nothing outside the library declares one, but a new case ships in a minor.

The reason this is safe to loosen is that the total function already exists: `PatchFailure.toString()`
renders through an `internal describe()` that the library keeps exhaustive on its own side. A caller
that logs failures is structurally unable to break. A caller that branches is asked, in KDoc and in
`docs/patching.md`, to write an `else`.

**Rejected — keep `Reason` closed and accept a major version for a new case.** Both releases since the
vocabulary was declared wanted to add to it. Freezing it for the length of `1.x` would make the next
supported shape choose between a major version and a reason that lies about itself, and a reason that
lies is how `UnknownProperty` starts meaning four different things.

**Rejected — make `Reason` an open interface or a string-carrying data class.** Sealing is what makes
the cases inspectable and stops a consumer inventing one; the change here is to the *versioning*
promise, not to the type.

**Rejected — a `Reason.Unrecognised` catch-all case declared now.** It would have to be constructed by
something, and the only candidate is a future case being downcast into it, which is worse than a new
case: a caller would branch on a shape that hides what actually happened.

### D4 — The runtime helpers are contract, and the exploration that said otherwise was wrong

An earlier reading of this surface proposed treating the twenty-odd `compare*`/`patch*` helpers as a
machine-facing tier — public because generated code names them, not because a human should.

That is not what the documentation says. `docs/hand-written.md` teaches `groupByProperty` plus one
`patch*` helper per property as *the* way to write a `Patcher`, because `Patcher` deliberately has no
builder — a builder cannot know how to construct the property's owner. The helpers are the escape
hatch's vocabulary, and demoting them would demote the escape hatch.

**Chosen**: state in §4 and §5 that they are contract, and state why, so the next reader does not
re-open it. Their evolution direction is addition — a new helper, or a new overload of one — which
0.6.0 already demonstrated by widening four compare helpers to accept a nullable side without moving
the dump.

### D5 — The capability interfaces may grow, with defaults

`Differ` and `Patcher` are `fun interface`s; `Tracked` is an ordinary one. A Kotlin `fun interface`
admits non-abstract members, so a member *with a body* can be added to any of them without breaking a
hand-written `object : Patcher<T>` and without stopping `Differ<Money> { a, b -> … }` converting.

**Chosen**: promise that in a minor version such an interface may gain a member carrying its own
implementation, and that a generated object may declare a further capability interface. Both are
additive, and the second is the axis `CLAUDE.md` already directs extension along — comparison,
application and tracking reached through one declaration.

The boundary is explicit in the spec: a member *without* an implementation is a breaking change and is
released as one.

### D6 — No experimental tier

Declined, per the constraint above. Recorded on the page with its reasoning rather than left for the
question to be asked again:

An opt-in marker buys the ability to ship something without promising it. Its costs are that every
consumer of the marked API carries an `@OptIn`, that the marked and unmarked halves of one DSL read
differently at the call site, and that "experimental" becomes the place a decision is deferred to
instead of made. kdiff's contract is small and its four growth directions are additive, so the
mechanism would guard against a risk the shape of the API already handles.

The honest cost is written down too: an addition in `1.x` is permanent from the release that makes it.
That is a reason to add slowly, which is a discipline, not a mechanism.

### D7 — Kotlin and KSP compatibility, as a policy rather than a matrix

Two distinct couplings, and the page states neither today:

```
  consumer's Kotlin compiler          consumer's KSP plugin
        |                                   |
        | reads kdiff-runtime's             | loads kdiff-processor, which was
        | Kotlin metadata (2.4)             | compiled against symbol-processing-api 2.3.11
        v                                   v
  a compiler older than the one       KSP's plugin version is lockstep with Kotlin's;
  that built the runtime refuses      a consumer on a later Kotlin runs a later KSP
  it outright                         against a processor built for an earlier one
```

**Chosen**: state the pair kdiff was built against, state that the runtime and annotations require a
Kotlin compiler at least that version, state that the processor expects the KSP plugin matching the
consumer's Kotlin, and state the policy — a Kotlin minor is tracked by a kdiff minor, and a kdiff
release names the pair it was built against.

**Rejected — a tested compatibility matrix.** Compiling the sample against a neighbouring Kotlin minor
means resolving the KSP plugin for that minor and pinning a second toolchain, for a result that is a
snapshot of two versions rather than a policy. `guard-the-release`'s consumer build exercises the
declared pair through real artefacts, which is the part that can silently rot; the rest is a promise
about what the maintainer does when Kotlin moves.

### D8 — JVM 21, stated as a target

Today README and FAQ derive the requirement from `inline`: *"its builder entry points are `inline` so
they can state that they run your block exactly once — which Kotlin will not inline into a module
compiling for an older target"*. True, and it reads as an accident with a workaround behind it.

**Chosen wording shape**: kdiff targets JVM 21. Two things follow — the class files require a JVM 21 to
load, and because several entry points are `inline`, a consuming module must also *compile* at target
21. The mechanism stays in the FAQ for the reader who hits the compiler's message and searches for it.

The coupling is wider than the builders, and the docs should say so: `Diff.route`, `Diff.at`,
`Diff.under` and `ChangeRoutes.onEach` are `inline` in the runtime, so a consumer who never writes a
hand-written differ inherits the constraint too.

## Risks / Trade-offs

**Loosening `Reason` weakens a promise made in 0.7.0** → It is loosened before `1.0.0`, which is when
the changelog says such a thing may happen, and it is loosened in the direction that cannot silently
break a caller: an exhaustive `when` that stops compiling is a compile error the day the consumer
upgrades, not a wrong answer at runtime. The changelog entry says so, and `docs/patching.md` shows the
`else`.

**"May grow" promises are only as good as the discipline behind them** → Each is bounded in the spec by
what a pre-existing consumer still does, which is a statement about behaviour rather than an intention.
`guard-the-release`'s consumer build is where a future addition gets checked against a build that
predates it.

Two of them are **not** mechanically checked today, found while implementing rather than here, and
worth writing down so the archive is not read as claiming more than was built:

- *A reason declared later reaches a caller's `else`.* The spec's scenario holds for every case that
  exists, and a caller shape that routes an unnamed reason is tested. But no test can present a case
  that does not exist yet, and enumerating the sealed hierarchy to detect a new one needs
  `kotlin-reflect`, which `kdiff-runtime` refuses. What does hold is that a new case cannot ship
  without a rendering: `describe()`'s exhaustive `when` fails to compile first.
- *A capability interface gaining a defaulted member breaks nothing.* The language behaviour this rests
  on is pinned by a test. The failure it guards against — an *abstract* member being added instead — is
  held by KDoc and review, because a test can only demonstrate the safe case.

Both are the kind of promise whose enforcement is a compile error somewhere in the library rather than
an assertion in a spec. That is weaker than a gate and stronger than an intention, and it is the honest
description of what `1.0.0` will be resting on.

**Declaring `DiffProcessor` internal is a breaking entry in the changelog for nobody's benefit** →
Accepted. The rule that a recorded removal is breaking is worth more than the tidiness of this one
entry, and the entry itself is one sentence saying no consumer can be affected.

**A written Kotlin policy invites the question of what happens when Kotlin 2.5 ships** → That is the
question the policy exists to have an answer to, and the answer — a kdiff minor tracks it — is one the
project can keep. Saying nothing does not make the coupling go away; it makes it a surprise.

## Migration Plan

For a consumer on `0.7.0` there is nothing to do. No runtime declaration moves, no generated file
changes, no annotation means anything different.

| if you | then |
|---|---|
| match `PatchFailure.Reason` exhaustively with no `else` | add one; a later minor may add a case |
| log or render failures | nothing; rendering stays total |
| named `DiffProcessor` in Kotlin | you were constructing a symbol processor outside a compiler |
| compile at JVM target below 21 | unchanged — this was already required, and now says why |

Release: one minor version. The `!` and the `BREAKING CHANGE:` footer go on the commit that makes
`DiffProcessor` internal, naming the recorded declaration removed and stating that no consumer path
existed to it.

## Open Questions

None blocking. One deliberately deferred: whether `1.0.0` should also promise a support window for
patch releases of `0.x` — declined for now, because the changelog and the release page already say
what each version is, and a support window nobody has asked for is a promise with a cost and no
claimant.
