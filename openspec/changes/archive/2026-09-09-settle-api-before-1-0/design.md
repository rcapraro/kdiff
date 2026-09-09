## Context

See `proposal.md` — *Why*. What the code looks like where each decision lands.

`Patched<T>` is declared in `Patch.kt` beside the helpers and is the return type of all nine of them.
`PatchResult<T>` is declared in `Patcher.kt` as the return type of `Patcher.apply`, and additionally
carries `isClean` and `getOrThrow()`. `patchNested` and `patchNestedNullable` call `patcher.apply`, get
a `PatchResult`, and rebuild it as a `Patched`. Generated code reads only `.value` and `.failures` off
whatever the helper returns.

`Diff` is `data class Diff(val changes: List<Change>) : Iterable<Change>` with `size`, `isEmpty()`,
`isNotEmpty()`, `plus`, `tree()`, `render()` and `EMPTY`. Its ABI entry lists `component1`, `copy` and
`copy$default`.

`Differ<T>` and `Patcher<T>` each declare one abstract function and nothing else.

`compareMap` and `patchMap` build and read `Segment.Key("key", k)`; the string appears twice in
`Compare.kt` and once in `Patch.kt`, and once in each of two docs pages as rendered output.

## Goals / Non-Goals

**Goals**

- One result type for applying, so composition needs no conversion and a hand-written patcher reads
  like a generated one.
- Every data class in the published API is one by decision, against a written criterion.
- The ABI diff of this change is the complete list of what moved, and nothing else.
- A reader of `api-stability.md` knows what 1.0 will promise before it is tagged.

**Non-Goals**

- Any behavioural change to comparison, application, tracking or routing.
- Anything additive that is not a constant for an existing literal. Additions go in their own change.

## Decisions

### D1 — Remove `Patched`, return `PatchResult` from the helpers

`PatchResult` is the survivor because it is the one a consumer already sees: it is what `Patcher.apply`
returns, and it carries `isClean` and `getOrThrow()`, which a helper's result may as well have too.
`Patched` was internal-facing API that had to be public for generated code to name it; nothing about it
was distinct.

`patchNested` and `patchNestedNullable` return `patcher.apply(...)`'s result directly. Everything else
constructs `PatchResult` where it constructed `Patched`. The processor is untouched: the generated
`val xPatched = patchValue(...)` binds a `PatchResult` now, and `.value` / `.failures` resolve as before.

Alternative — keep `Patched` as a `typealias` for `PatchResult` — rejected. A typealias keeps the second
name alive in KDoc, in IDE completion and in the ABI dump's synthetic accessors, and the whole point is
one name. Pre-1.0 is when a rename is cheap.

### D2 — `Diff` becomes a plain class; the criterion for the rest

`Diff` keeps its constructor, its `changes`, and equality and hash code over `changes`. It loses `copy`
and `component1`. It gains an explicit `toString()` returning `renderChanges(changes)` (D3).

The criterion, written into `api-stability.md`: **a type stays a data class when its properties are
its whole meaning, destructuring them is a use, and the set will not grow.** Applied:

| type | data class? | why |
|---|---|---|
| `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved` | yes | closed vocabulary; `(path, before, after)` destructuring is idiomatic; the fields cannot grow without a new variant |
| `Segment.Field`, `Index`, `Key` | yes | same |
| `PatchResult` | yes | `val (value, failures) = differ.apply(...)` is the natural read |
| `PatchFailure` | yes | `(change, reason)` |
| `DiffNode` | yes | `(segment, changes, children)`; a tree node's shape is fixed |
| `TrackedField` | yes | `(name, depth)` |
| `Diff` | **no** | a container with behaviour; `copy(changes = …)` is the constructor spelled longer; nothing destructures a diff |

Alternative — strip `data` from all of them, per the general Kotlin API-design advice — rejected. The
advice exists because `copy` and `componentN` fix a type's shape; every type above has a shape fixed
already by a closed vocabulary or by what it is. Removing `data` from `ValueChanged` would remove
destructuring that consumers rely on, for no gained freedom.

### D3 — `Diff.toString()` is `render()`

A diff printed in a log line, a Kotest failure or a debugger reads today as
`Diff(changes=[ValueChanged(path=city, before=Paris, after=Nice)])`. The same diff renders as
`city  "Paris" -> "Nice"`. The second is what every reader wants, and `render()` exists because the
first was not it.

Multi-line `toString` is unusual and is accepted here: a diff of several changes *is* several lines,
and a single-line form would have to invent a third rendering. `render()` stays as the explicit name so
call sites that want the text on purpose say so. An empty diff renders as `no changes`, which is also
what `toString` says.

Alternative — a compact `Diff(3 changes)` — rejected: it answers "how many" and a reader of a failure
wants "which".

### D4 — `fun interface` for `Differ` and `Patcher`

Kotlin marks a single-abstract-method interface `fun` to allow SAM conversion. Adding the modifier
changes Kotlin metadata only; the class file and the ABI dump are unchanged. `Tracked<T>` has a property
rather than a function and cannot be one.

The documented risk — a lambda differ bypasses the descent bound — already exists for
`object : Differ<T>` and is documented in `errors.md` and `architecture.md`. The lambda form makes the
same shape shorter; the same sentence covers it, and `hand-written.md` gains it beside the `object` form.

### D5 — `Segment.Key.MAP_ENTRY`

```kotlin
public data class Key(public val property: String, public val value: Any?) : Segment {
    public companion object {
        /** The [property] a map entry's key segment carries: an entry is identified by its key, not by a property. */
        public const val MAP_ENTRY: String = "key"
    }
}
```

The value stays `"key"` so every rendered path and every existing assertion is unchanged. `compareMap`
and `patchMap` read the constant. A companion on a data class adds `Key.Companion` to the ABI dump,
which is the expected entry.

Alternative — a top-level `MAP_KEY` constant — rejected: it belongs to the segment kind that carries it.

### D6 — What `api-stability.md` says

A page, not a spec, because it is a promise about process rather than behaviour. Sections:

1. **What is recorded** — the public API of the three published modules, as `api/*.api`; a change that
   removes or alters an entry is breaking; before `1.0.0` a minor may do so with a migration note in
   the changelog, after it a major must.
2. **What is closed** — `Change` and `PatchFailure.Reason`; adding a case is breaking; the six declared
   exception types and why there is no supertype.
3. **What is a data class** — the D2 table and its criterion.
4. **What generated code promises** — `object <Type>Differ` in the type's package, implementing
   `Differ<T>`, `Patcher<T>` and, when `@Trackable`, `Tracked<T>`; nothing else in the generated file is
   API, including its private members and the exact runtime helpers it calls.
5. **What is not API** — message text, `render()` output layout, `toString` layout, the order of
   failures within one `apply`.
6. **Platform** — Kotlin/JVM, JVM 21 target, no reflection library.
7. **Decided and not done** — `Diff<T>`: a type argument would make `plus` across types, `EMPTY`, and
   the lifting of nested diffs all awkward, and `route<T>` already gives a typed surface where dispatch
   needs one; `TypeChanged` with `KClass` sides: the instances are already carried and `before::class`
   is one expression away; a `KdiffException` supertype: a marker interface cannot be caught and the
   two roots are different stdlib types on purpose.

### D7 — Sequencing against the other pending changes

`close-generation-gaps` adds `patchNullable` and `patchSingleton` returning `Patched`; if it lands
first, D1 renames their return type too, and the task list says so. If this change lands first, that
change writes them against `PatchResult`. Neither order needs a third change.

## Risks / Trade-offs

**A consumer used `Diff.copy`** → nothing in the docs, the sample or the tutorial does, and a diff with
different changes is a different diff, so the replacement is the constructor. One line in the changelog.

**A consumer named `Patched` in a hand-written patcher** → rename to `PatchResult`; the members are the
same. One line in the changelog.

**Multi-line `toString` surprises a logging framework** → it prints multiple lines. A consumer who wants
one line has `changes.toString()` or `size`. Stated in `diffing.md`.

**`fun interface` invites bypassing the descent bound** → already possible, already documented; the docs
sentence is extended to name the lambda form.

**The stability page overpromises** → it promises only what the ABI gate already enforces and what the
closed vocabularies already state. Everything else is listed as not API.

## Migration Plan

A minor version with two mechanical source-level breaks, both stated in `CHANGELOG.md` with their
one-line migration. Rollback is pinning the previous runtime; generated code is compatible either way,
since it reads only `.value` and `.failures`.

## Documentation

- `docs/api-stability.md` — new, per D6; linked from `README.md` *Status* and `docs/README.md`.
- `docs/patching.md` — the `PatchResult` declaration block is re-cited from `Patcher.kt`; any mention of
  `Patched` becomes `PatchResult`.
- `docs/hand-written.md` — the lambda form beside `object : Differ<T>`, with the descent-bound sentence
  extended; `MAP_ENTRY` named where hand-written map paths are shown.
- `docs/diffing.md` — *Viewing a diff* notes that `toString` is `render()`.
- `docs/errors.md` and `docs/architecture.md` — the bypass sentence names the lambda form.
- `CHANGELOG.md` under `[Unreleased]` — **BREAKING** *Changed* for `Patched` and for `Diff`'s `copy`,
  each with its migration; *Added* for `fun interface`, `MAP_ENTRY` and the stability page.
