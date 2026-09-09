## Why

Two defects a code review found in `add-value-type-comparison`, both in the same place: resolution
decides how a property is compared by reading *a* declaration, and in two cases it reads the wrong one
or forgets which file it came from. Neither is loud. Both give a wrong answer that compiles.

```
  the declaration resolution reads               what it gets wrong
  ---------------------------------------------  ------------------------------------------------
  a value class / enum in the author's module    its file joins no dependency set, so editing it
                                                 leaves a stale generated differ that compiles
  a sealed parent's annotated property           the subclass differ reads the override, which
                                                 Kotlin gave none of the parent's annotations
```

The first is a stale incremental build: the differ a clean build refuses is the differ an incremental
build keeps. The second is an annotation that silently does nothing — the exact failure mode the
processor conventions exist to prevent, and the one thing `@DiffAsValue` was added to make possible.

## What Changes

**A value classification taken off a declaration in the author's own module records that
declaration's file.** `declaredValueSources()` returns the declaring file only for `@DiffAsValue`,
while a type is also a value for being an `enum class` or a `value class` — both of which the author
declares, in a file of their own. So `@JvmInline value class Sku(val code: String)` in `Sku.kt`
contributes nothing to `OrderDiff.kt`'s `Dependencies(aggregating = false, …)`. Turn `Sku` into a
`data class` and rebuild: `Order.kt` is untouched, KSP does not regenerate, and the stale
`compareValue("sku", …)` still compiles — comparing as one opaque value a type that a clean build
refuses outright with *kdiff cannot compare sku of type demo.Sku*. Enums have carried the same hole
since before `@DiffAsValue` existed; the function's own KDoc already claims the behaviour this change
gives it.

**BREAKING — a comparison annotation on a property a sealed parent declares is honoured on a subclass
that overrides it.** `@Diffable` on a sealed type emits the parent's own properties only into the
type-swap branch; two instances of one subclass delegate wholly to that subclass's differ, which reads
`getDeclaredProperties()` — and a subclass's `override val` *is* a declared property, carrying none of
the parent's annotations, because Kotlin does not inherit them onto an override. So `@DiffAsValue val
meta: Meta` on a sealed parent is honoured across a subclass swap and ignored for the common
same-subtype case, with no diagnostic either way. Resolution will walk to the overridden declaration
when the override itself carries no comparison annotation, so `@DiffAsValue`, `@DiffIgnore` and
`@DiffWith` on a parent property mean the same thing in both branches.

`@DiffWith` reaches this only by luck today — an unannotated override fails to resolve and the build
stops — so `@DiffAsValue` and `@DiffIgnore` are where the wrong answer currently ships.

**Not in scope**

- Any other annotation. The tracking annotations are read off the class, not a property's declaring
  type, and `@DiffKey` is read off the element type — neither has this shape.
- A diagnostic for an annotation that cannot be honoured. Honouring it is the answer here; an error
  would be the answer only if the annotation were genuinely unreachable, and it is not.
- Widening `aggregating` anywhere. Both fixes add a file to an existing non-aggregating set, which is
  what `Dependencies` is for.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `diff-generation`:
  - ADDED *A generated differ is regenerated when a declaration it read changes* — every declaration
    resolution consulted joins the generated file's originating set, so an incremental build cannot
    keep a differ a clean build would refuse.
  - ADDED *A comparison annotation on an overridden property is honoured on the override* — the
    annotation a sealed parent declares means the same thing whichever branch compares the property.
- `diff-application`:
  - MODIFIED *A property compared as a value is applied as a value* — a property that inherits its
    value declaration from an overridden parent property round-trips like one that carries it
    directly, so comparison and application stay symmetric.

`change-tracking` needs no delta: a tracking scope is resolved from the annotated class's own
declarations and reads no comparison annotation. `build-quality-gates` needs none either — the gates
are unchanged, and no dump moves.

## Impact

- **Modules**: `kdiff-processor` (both fixes: `Resolution.kt` and the property-resolution path
  `DiffProcessor.kt` shares between comparison and application), `kdiff-sample` (a fixture for the
  sealed-override shape), docs. `kdiff-annotations` and `kdiff-runtime` are untouched.
- **Public API**: unchanged. No published module gains or loses a declaration, so no `api/*.api` dump
  moves and `updateKotlinAbi` is not run.
- **Generated API surface**: unchanged in shape — the same object, the same three interfaces, the same
  two functions. The *body* changes for one shape: a sealed subclass overriding an annotated parent
  property now emits that property's declared comparison instead of descending into it.
- **Annotation semantics**: changed, in the direction of the annotation's stated meaning.
  `@DiffAsValue` and `@DiffIgnore` on a sealed parent property now apply to overriding subclasses too.
  Code with no sealed type, or whose subclasses carry their own annotations, behaves identically.
- **Breaking, in one place**: a diff over two instances of one subclass of a sealed type whose parent
  declares `@DiffAsValue` on an overridden property reports one change at the property where it
  reported changes beneath it, and `@DiffIgnore` there now excludes the property where it did not.
  Both were the annotation being ignored, so the new behaviour is what the annotated source always
  asked for — but a consumer asserting on the old paths sees them move. One `CHANGELOG.md` entry with
  the migration.
- **Incremental builds**: a differ is regenerated in cases where it previously was not. Strictly more
  regeneration, never less, and only for a file the differ genuinely read.
- **Dependencies**: none added. No version bumped.
