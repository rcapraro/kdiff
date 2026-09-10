## Why

`docs/api-stability.md` states what `1.0.0` will promise, and reading it against the code turns up
three places where the promise is either wider than the library can keep or narrower than it needs to
be. Each is a sentence to write now or a major version to spend later:

```
  the page says                                what is actually true
  -------------------------------------------  ------------------------------------------------
  the recorded dump is the public API          the dump also records inlining artefacts, value
                                               class lowering, and a KSP entry point nobody calls
  PatchFailure.Reason is closed; adding a       every release that supported a new shape wanted a
  case is breaking                             new reason. Fourteen frozen for all of 1.x is the
                                               constraint most likely to bite first
  Kotlin/JVM only, JVM 21 toolchain            says nothing about which Kotlin compiles it, and
                                               presents JVM 21 as fallout from `inline` rather
                                               than as the platform the library targets
```

None is a defect. All three are answers a reader will want in two years, and the honest answer should
be "we decided" rather than "the first implementation exposed it".

## What Changes

**The recorded surface and the promised surface are stated as two different things.** The ABI dump
stays the gate it is — nothing about how it is produced or checked moves — but `api-stability.md` gains
the three categories of entry the dump records without promising: members published only so an `inline`
function can reach them, the accessors a `value class` lowers to, and a module's code-generation entry
point. A reader of the dump can then tell a promise from an artefact, which today they cannot.

**BREAKING — `DiffProcessor` becomes internal**, leaving `kdiff-processor`'s recorded surface as the
one declaration its artefact exists to provide, `DiffProcessorProvider`. The processor is reached
through the `ksp` configuration and its service registration; nothing constructs it. Its dump shrinks
by one class. *Migration*: none is possible to need — a consumer that named `DiffProcessor` was
constructing a symbol processor outside a compiler.

**`PatchFailure.Reason` may gain a case in a minor version.** It stays sealed, so nothing outside the
library implements it, and every case keeps carrying its facts as properties. What changes is the
promise around it: a caller matching on reasons SHALL be written with a catch-all branch, and
`PatchFailure`'s rendering stays total so a caller that only reports failures is never affected. A
`Change` variant stays closed on the old terms — a `Change` is what a caller *dispatches* on, and
exhaustive routing is the point of the type; a `Reason` is what a caller *reports*.

**The capability interfaces may grow.** `Differ`, `Patcher` and `Tracked` may gain a member with a
default implementation in a minor version, and a generated object may come to declare a further
capability interface. Both are additive: a hand-written `object : Patcher<T>` keeps compiling, and
`Differ<Money> { a, b -> … }` keeps converting, because a `fun interface` admits non-abstract members.
This is the axis `CLAUDE.md` already says to extend along; it becomes a promise rather than a habit.

**The runtime helpers are contract, deliberately, and say why.** `groupByProperty`, the seven `compare*`
and the ten `patch*` helpers are public because generated code names them *and* because
`docs/hand-written.md` teaches them as the way to write a `Patcher` — the one capability with no
builder. They evolve by addition: a new helper or a new overload, never a changed signature.

**JVM 21 is stated as a target, not as a consequence.** The requirement is unchanged and the mechanism
is unchanged. What changes is the framing: kdiff targets JVM 21, from which two things follow — class
files a JVM 21 loads, and `inline` entry points a consumer must *compile* at target 21 to use. Today
the README derives the requirement from `inline` alone, which makes it read as an accident that might
be negotiated away.

**A Kotlin and KSP compatibility policy is written down.** Which Kotlin compiler the published metadata
requires, which KSP plugin the processor expects, and that a Kotlin minor is tracked by a kdiff minor.
The repository currently says nothing about the one axis that can break every consumer without a line
of kdiff changing.

**Not in scope**

- An experimental opt-in tier. Considered and declined; the reasoning goes on the page, not into code.
  Room to evolve comes from the four additive directions above, not from a marker.
- Typed handlers (`onValue(Order::status) { before, after -> }`), still the separate change the
  `settle-api-before-1-0` proposal deferred them to.
- Any gate. The checks that would defend this contract mechanically are `guard-the-release`.
- The `1.0.0` version bump and its changelog section. This change makes the tag possible; it is not
  the tag.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `diff-application`:
  - MODIFIED *A change that cannot be applied is reported, never silently dropped* — the reasons are a
    declared set that may grow between versions; a caller matches them with a catch-all branch, and
    the rendering of a failure is total.
- `diff-generation`:
  - ADDED *A capability interface may gain a member without breaking an implementation of it* — the
    additive axis, expressed as what a hand-written implementation and a lambda still do afterwards.
- `build-quality-gates`:
  - MODIFIED *The published API surface is recorded and guarded* — the record contains entries that
    are not the promised surface, and which those are is stated rather than left to be inferred; a
    module whose artefact exists to provide a code-generation entry point records that entry point
    and nothing else.

## Impact

- **Modules**: `kdiff-runtime` (KDoc only — the `Reason` clause and the capability-interface clause);
  `kdiff-processor` (one declaration becomes `internal`, and its recorded dump shrinks by one class);
  `kdiff-annotations` untouched.
- **Public runtime API**: unchanged. No declaration is added, removed or altered in `kdiff-runtime`,
  so its dump does not move.
- **Public processor API**: `DiffProcessor` and its four members leave `kdiff-processor/api/`. Marked
  **BREAKING** because a recorded declaration is removed, which is the rule this repository applies
  without exception; no consumer can be affected.
- **Generated API surface**: unchanged. The generated object, its name, its package and its interface
  members are as they were, and regenerating produces a byte-identical file.
- **Annotation semantics**: unchanged. Every annotated class compiles to what it compiled to before,
  and every diagnostic keeps its text.
- **Docs**: `docs/api-stability.md` is substantially rewritten — §1 gains the recorded-not-promised
  categories, §2 splits the two vocabularies onto different terms, §4 and §5 state why the helpers are
  contract, §6 reframes the platform, a new section states the Kotlin and KSP policy, and §7 gains the
  declined experimental tier. `README.md` (Install, Status), `docs/faq.md` (platform, and a new
  question on what a minor may change), `docs/patching.md` and `docs/errors.md` (how to match a
  reason), `docs/architecture.md` and `CONTRIBUTING.md` follow.
- **Dependencies**: none added. No version bumped.
