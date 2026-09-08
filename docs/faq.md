# FAQ

Questions whose answers are otherwise spread across several pages. Each answer is short and links to
the page that owns the rule.

If you are holding an error message, [the error page](errors.md) is the faster route.

## Comparison

### Why is my property not being compared?

Four possibilities, in the order worth checking:

1. It carries `@DiffIgnore`, which excludes it from comparison entirely.
2. Its class is described by a hand-written `differ { }` that does not name it — the builder compares
   what you name and nothing else, and naming nothing at all is
   [rejected where the differ is built](errors.md#2-building-a-differ-a-scope-or-a-routing).
3. Its type is one kdiff cannot compare, in which case the build failed with
   [`kdiff cannot compare <prop> of type <T>`](errors.md#a-property-kdiff-cannot-compare) rather than
   comparing it silently.
4. It is not a `val` in the constructor. A property declared in the class body is compared, but cannot
   be [rebuilt by a patch](errors.md#cause-a--your-model-cannot-rebuild-that-property).

kdiff never falls back to comparing a rich type as an opaque value, because that produces a diff that
is technically correct and useless. → [Annotation reference](annotations.md)

### Why did my reordered list not report a move?

Its element type declares no `@DiffKey`, so there is no identity to recognise a moved element by. Add
one and a reorder reports as `Moved`.

Note what an unkeyed reorder *does* report: two lists of the same length are compared index by index,
so swapping two elements reports a value change at each position — not an addition and a removal. One
comparison reports additions or removals, never both. →
[Tell a move from an add and a remove](how-to.md#tell-a-move-from-an-add-and-a-remove)

### Why did I get an addition and a removal for something I changed?

Three shapes report that way, and none of them is an unkeyed list reorder:

- **A `Set`.** Set elements have no stable identity, so a modified element is indistinguishable from
  one removed and another added. A set never reports a move and never reports a change *inside* an
  element. → [Sets](diffing.md#sets)
- **A keyed list whose key value itself changed.** The element's identity changed, so it is a
  different element: the old key is removed and the new one added.
- **A `Map` entry whose key changed**, for the same reason.

If elements need to keep their identity while changing, they need a `@DiffKey` that does not change
with them.

### Why did two edits to one list report more changes than I made?

An unkeyed list is matched by position after the tail both sides already agree on is excluded. One
contiguous insertion or deletion reports as exactly that; two scattered edits agree at neither end and
smear, because recovering that would need an edit-distance search kdiff deliberately does not do.
`@DiffKey` is the answer wherever elements have an identity. →
[What kdiff does not do](architecture.md#what-kdiff-does-not-do)

### Why was a null becoming a value reported as a change rather than an addition?

A property's path exists on both sides or on neither, so `Added` and `Removed` are reserved for
collection elements and map entries. Null on both sides is not a change at all. →
[Values, enums and nullables](diffing.md#values-enums-and-nullables)

### Can I compare every `BigDecimal` in my model by `compareTo`?

Not in one declaration. Custom comparison is per property, not per type: `@DiffWith` points one
property at one differ, and there is no global registration. →
[Compare a value your own way](how-to.md#compare-a-value-your-own-way)

### Can I add a sixth `Change` variant?

No — and that is a guarantee rather than a limitation. `Change` is sealed and closed so a `when` over
it can be exhaustive and stay that way, which makes adding a variant a breaking change to every
consumer. `PatchFailure.Reason` is closed on the same terms. →
[The change vocabulary is closed](diffing.md#the-change-vocabulary-is-closed)

### Is a `Diff` a `List`?

It is an `Iterable<Change>`, so `map`, `filter` and `groupBy` work on it directly. It is deliberately
not a `List`: a `Diff` equals only another `Diff`, and advertising list-ness while equalling no list
would mislead. `diff.changes` is the `List` when you need one. →
[What you get back](diffing.md#what-you-get-back)

### What happens if my object graph contains a cycle?

Comparing and applying descend at most 512 levels and then raise `CyclicStructureException`, naming
where they stopped and whether an instance was genuinely re-entered. A cycle is a diagnosis rather
than a `StackOverflowError` — but the guard does not make a cyclic graph comparable. A self-reference
through a nullable property is not a cycle and compares fine. →
[`CyclicStructureException`](errors.md#cyclicstructureexception)

## Tracking

### Why does `@Trackable(depth = 1)` report an added list element but not a change inside one?

Depth counts **property steps only**. An index or a key identifies a sibling, not a level of nesting,
so `addresses[id=A3]` is one step while `addresses[id=A2].street` is two. If keys counted, a depth of
1 on a list property would report nothing at all. → [Depth](tracking.md#depth)

### Why is a change deeper than the limit not reported at its nearest ancestor?

Depth filters; it never rewrites a path or rolls a change up. Reporting "`billing` changed" would need
a change *at* `billing` carrying both `Address` values, which cannot be read without reflection, and a
valueless variant would break the closed `Change` vocabulary. Say `under(Order::billing)` and coalesce
in your own callback. → [Depth filters — it does not roll up](tracking.md#depth-filters--it-does-not-roll-up)

### Is a `Tracker` thread-safe?

No. `update` reads the baseline, compares, dispatches and writes it back, and making that atomic would
mean holding a lock across your callbacks. Confine one to a thread. A `TrackScope` is immutable and
can be shared freely. → [Tracking](tracking.md)

### Why is there no `@TrackWith`?

Because there is nothing for one to do. `@DiffWith` exists because a property's *type* may be
uncomparable by kdiff's rules; tracking has no such failure mode, since whatever produced a change, it
arrives with an ordinary `FieldPath` and selection and depth apply to it unchanged. →
[Tracking needs no escape hatch of its own](hand-written.md#tracking-needs-no-escape-hatch-of-its-own)

## Patching

### Why is there no `Patcher` builder to match `differ { }`?

A capability gets a builder exactly when it only needs to *name* properties. Comparing and tracking
read and name properties; patching must **construct** the property's owner, and no builder can know a
constructor. So a hand-written patcher is written out in full. →
[The axis that decides the API's shape](architecture.md#the-axis-that-decides-the-apis-shape)

### Why does `apply` return failures instead of throwing?

So you keep what applied. `value` is always usable and `failures` says exactly what is missing. A
caller who wants all-or-nothing says so at the call site with `getOrThrow()`. →
[Require the whole patch](how-to.md#require-the-whole-patch)

### My `@DiffWith` property will not patch. Why?

Its differ can only compare, and kdiff will not attempt to reconstruct a type it was only taught to
compare. The change is reported as `UnpatchableProperty` and the rest of the instance still patches.
Implement `Patcher` on the same object to fix it. →
[Make a `@DiffWith` property patchable](how-to.md#make-a-diffwith-property-patchable)

## Platform, build and cost

### Does kdiff use reflection? Do I need `kotlin-reflect`?

No to both. Comparison is generated at compile time, and the DSL's property references are Kotlin
standard library — reading `name` and calling `get` needs no `kotlin-reflect`, so the library adds no
such dependency. → [Hand-written differs](hand-written.md#differ--)

### Will the code generator end up on my runtime classpath?

Not if you declare it correctly. `kdiff-processor` goes on the `ksp` configuration, never
`implementation`; it runs inside the compiler. `kdiff-runtime` depends only on the Kotlin standard
library. → [Install](../README.md#install)

### Which platforms are supported?

Kotlin/JVM only. There are no multiplatform targets, and the annotations are not designed for Java
consumers. The build requires JDK 21 or later **and** a JVM target of 21 — the builder entry points
are `inline` so they can state they run your block exactly once, which Kotlin will not inline into a
module compiling for an older target.

Nothing in this repository tests any other environment, so no claim is made about one. →
[What kdiff does not do](architecture.md#what-kdiff-does-not-do)

### Can I serialise a `Diff` and send it somewhere?

It depends entirely on your payload, and kdiff neither provides nor tests a serialiser. What the types
hold: a `Change` carries the compared values as `Any?`, and a `Segment.Key` retains the key **as its
own value** rather than as text — which is what makes a keyed element reconstructable, and also what
means the diff is only as serialisable as the values inside it. →
[Paths](diffing.md#paths)

### How fast is it?

Every collection comparison is a small, fixed number of passes and none of them searches, so cost is
linear in the number of compared properties actually reached. There is no quadratic pairwise matching
and no edit-distance search anywhere in the library, so no collection size makes a comparison fall off
a cliff.

No figure is quoted, here or anywhere in the documentation: no benchmark ships with the library. The
claim is the shape of the work, not a measurement. →
[What a comparison costs](architecture.md#what-a-comparison-costs)

### Can I use kdiff on a type I cannot annotate at all?

Yes, in both directions — `differ { }` compares it and `trackScope { }` tracks it, and both are
indistinguishable from the generated equivalents to anything consuming them. That is a spec'd
requirement rather than a convention. →
[Compare a type you cannot annotate](how-to.md#compare-a-type-you-cannot-annotate)

## Where to go next

- [How do I…](how-to.md) — the recipes several of these answers point at
- [Errors](errors.md) — every message kdiff produces
- [Tutorial](tutorial.md) — a worked application end to end
- [Architecture](architecture.md) — the reasoning behind the answers above
- [Documentation index](README.md) — everything else
