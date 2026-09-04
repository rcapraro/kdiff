## Why

kdiff can say what changed between two instances. It cannot yet use that answer for anything: a
`Diff` is a report, and the only thing a caller can do with it is read it.

Making a diff applicable — `apply(before, changes)` reconstructing the second instance from the
first — turns the result into a value with a use. It also turns the diff into something testable in
a way a report never is: if applying a diff to its own source does not reproduce the target, the
diff was wrong. That round-trip is the strongest correctness statement kdiff can make about the
comparison work already done.

## What Changes

- New `Patcher<T>` interface in `kdiff-runtime`, with `apply(before: T, changes: List<Change>)`.
  Generated differs implement it alongside `Differ<T>`; the generated object keeps its existing
  name, gaining a supertype rather than being renamed.

  `Differ<T>` is deliberately left alone. A hand-written DSL differ has no way to construct its
  type, so requiring it to patch would force a throwing stub into every one and break the rule that
  a hand-written differ is indistinguishable from a generated one.

- Applying returns a result carrying both the patched value and every change that could not be
  applied, each with a reason. Nothing is dropped silently, and a caller that wants strictness
  checks that the failure list is empty.

- **BREAKING** — `TypeChanged` gains the before and after values. It currently records two type
  *names* and nothing else, so a sealed subclass swap cannot be replayed: there is no way to
  reconstruct a `Transfer` from the string `"Transfer"`. This was a gap in the change model, not a
  deliberate limit. Existing code constructing or destructuring `TypeChanged` must be updated.

- **BREAKING** — `Segment.Key` retains the key value instead of its string form. Applying an added
  entry to a `Map<Int, V>` needs the `Int` back, and a key rendered to `"1"` cannot be turned back
  into one. Path rendering is unchanged — it still calls `toString()` — so no rendered output moves.

- Generated `apply` reconstructs through `copy(...)`, rebuilding nested values, keyed and unkeyed
  lists, sets and maps, and substituting a sealed value wholesale on a subclass swap.

- A property annotated `@DiffWith` is patchable when the object it names also implements
  `Patcher` of that property's type. When it implements only `Differ`, changes beneath that
  property are reported as failures rather than applied — the library cannot construct a type it
  was only taught to compare.

- Documented and reported, not silently tolerated: a property declared in a class body rather than
  its constructor can be compared but not patched, because `copy` only takes constructor
  parameters. Such a change becomes a reported failure.

## Capabilities

### New Capabilities

- `diff-application`: what applying a diff does — reconstructing the target instance from the
  source and a list of changes, what each kind of change does when applied, the round-trip
  guarantee, and what happens to a change that cannot be applied.

### Modified Capabilities

- `diff-generation`: two requirements change so that the changes a differ produces carry enough
  information to be replayed. The sealed-dispatch requirement now has a type change carry its
  values, and the change-location requirement now has a key segment retain the key itself while
  rendering unchanged. No comparison behaviour changes; a diff reports exactly what it reported
  before, with more in the changes it emits.

## Impact

- **Affected modules**: `kdiff-runtime` (`Patcher`, the result and failure types, the collection
  rebuild helpers, the two model changes), `kdiff-processor` (generating `apply`, and the sealed
  differ now passing values into `TypeChanged`), `kdiff-sample` (round-trip tests across every
  shape). `kdiff-annotations` is untouched — no new annotation.
- **Affected APIs**: `Patcher`, the patch result and failure types are new. `TypeChanged` and
  `Segment.Key` change shape, which is the breaking part. `Differ`, `Diff`, `FieldPath`, the other
  `Change` subtypes and every annotation are unchanged, as is the generated differ's name, package
  and `diff` signature.
- **Dependencies**: none added.
- **Incremental processing**: unchanged in principle — `apply` is generated into the same
  `<Type>Diff.kt` from the same inputs, so the originating-file set is the one already established.
  A `@DiffWith` target that supplies a patcher stays a dependency exactly as it is today. Note that
  at the pinned KSP version the Gradle worker rewrites all generated output every run, so the
  dependency set must be verified through KSP's own source-to-outputs log rather than by timestamp.
- **Risk**: the round-trip is a total guarantee across every supported shape, which is a strong
  claim and the main source of work here. Collections are where it will be won or lost: rebuilding
  a keyed list from element changes plus additions, removals and moves is the one genuinely
  order-sensitive operation in the library.
