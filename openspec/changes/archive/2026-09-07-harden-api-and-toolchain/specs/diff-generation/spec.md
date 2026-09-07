## MODIFIED Requirements

### Requirement: A diff result reports an ordered list of changes

`diff` SHALL return a `Diff` describing how `after` differs from `before`. The `Diff` SHALL expose
the changes it found as an ordered list, and SHALL report directly whether it found none.

A `Diff` SHALL additionally be usable as an ordered collection of its changes in its own right: it
SHALL be iterable, SHALL report how many changes it holds, and SHALL report emptiness and
non-emptiness in the shape the Kotlin standard library uses for a collection, so that the stdlib
collection operators apply to it without the caller reaching for the underlying list first. The list
SHALL remain reachable for a caller that wants it.

A `Diff` SHALL be a value: comparing the same pair of instances twice SHALL produce equal results,
and calling `diff` SHALL NOT modify either instance.

#### Scenario: Result exposes its changes and its emptiness

- **WHEN** a caller obtains a `Diff` from any differ
- **THEN** the `Diff` exposes an ordered list of changes
- **AND** the `Diff` reports whether that list is empty
- **AND** an empty list and "reports empty" always agree

#### Scenario: A diff is iterated and counted directly

- **WHEN** a caller iterates a `Diff` and reads its count
- **THEN** the iteration yields the same changes, in the same order, as its list of changes
- **AND** the count equals the size of that list

#### Scenario: Standard collection operators apply to a diff

- **WHEN** a caller filters, maps or folds a `Diff` with a standard library operator
- **THEN** the operator sees the diff's changes in report order

#### Scenario: Diffing is repeatable and does not mutate its inputs

- **WHEN** a caller calls `diff(a, b)` twice with the same two instances
- **THEN** both calls return equal results
- **AND** `a` and `b` are unchanged

## ADDED Requirements

### Requirement: A diff can be combined and narrowed by property reference

A caller SHALL be able to build a diff from changes it already holds, to combine two diffs into one,
and to narrow a diff to the changes belonging to one property — all without matching a property name
as text.

Combining SHALL preserve order: the changes of the first diff SHALL precede those of the second, each
in its own report order. Combining SHALL NOT deduplicate, reorder or reconcile conflicting changes;
it concatenates.

Narrowing SHALL be offered in two forms: the changes reported *at* a property, meaning the property
itself changed; and the changes reported *at or beneath* a property, meaning that property or anything
inside it changed. Both SHALL name the property by reference rather than by text, so that no string is
matched and renaming the property reaches the call site, and both SHALL return a `Diff`, so the
results narrow and combine further.

A `Diff` carries no type argument, so narrowing SHALL check that the property belongs to the diffed
type only when the caller states that type. Stating it SHALL make a property of an unrelated type a
compile error; leaving it to be inferred SHALL compile and select nothing, the property having then
determined the type itself. This limit SHALL be documented wherever narrowing is, because the failure
it allows is silent. Routing SHALL NOT share it: a routing states its type at the call site, and every
property it names SHALL be checked against that type.

An empty diff SHALL be reachable as a constant, so a caller with nothing to report need not construct
one.

#### Scenario: Two diffs combine in order

- **WHEN** a caller combines a diff reporting a change at `reference` with a diff reporting a change
  at `billing.city`
- **THEN** the result reports both changes, the one at `reference` first

#### Scenario: Narrowing at a property excludes what lies beneath it

- **WHEN** a diff reports a change at `billing` and a change at `billing.city`
- **AND** a caller narrows it to the changes at the `billing` property
- **THEN** the result reports only the change at `billing`

#### Scenario: Narrowing beneath a property includes the property itself

- **WHEN** a diff reports a change at `billing`, one at `billing.city` and one at `reference`
- **AND** a caller narrows it to the changes at or beneath the `billing` property
- **THEN** the result reports the change at `billing` and the one at `billing.city`, in that order
- **AND** the change at `reference` is absent

#### Scenario: Narrowing with the type stated rejects a property of another type

- **WHEN** a caller narrows a diff of `Order`, stating `Order` as the type, by a property reference
  belonging to `Address`
- **THEN** the code does not compile

#### Scenario: Narrowing with the type inferred accepts one, and selects nothing

- **WHEN** a caller narrows a diff of `Order` by a property reference belonging to `Address`, without
  stating the type
- **THEN** the code compiles
- **AND** the result reports no changes

#### Scenario: An empty diff is available as a constant

- **WHEN** a caller reads the empty-diff constant
- **THEN** it reports no changes
- **AND** it is equal to a diff built from an empty list of changes

### Requirement: A refused comparison raises a declared, inspectable error

Where the library refuses an input rather than describing it, the failure SHALL be raised as a
declared exception type of the library's own, carrying the facts of the refusal as inspectable
properties rather than only inside its message.

The declared type for a list holding two or more elements that share a key SHALL carry the name of the
list property, the name of the key property and the duplicated key value. Its message SHALL name the
same three things, so a reader of a stack trace is no worse off than before.

Each declared refusal type SHALL be a subtype of the exception the library raised before this change,
so a caller catching that broader type continues to catch it.

#### Scenario: A duplicate key raises the declared type

- **WHEN** a comparison is asked to match a list by key and two elements of that list share a key
- **THEN** the library raises its declared duplicate-key exception
- **AND** that exception reports the list property, the key property and the duplicated key value as
  inspectable properties

#### Scenario: A duplicate key is still caught as an illegal argument

- **WHEN** a caller catches `IllegalArgumentException` around such a comparison
- **THEN** the duplicate-key exception is caught

### Requirement: A cyclic object graph is refused rather than exhausting the stack

Comparing SHALL descend into nested values only to a bounded depth. When the bound is
exceeded, the library SHALL raise a declared exception of its own naming the path at which it stopped,
rather than exhausting the call stack.

The bound SHALL be high enough that no honest data model reaches it, and the exception's message SHALL
distinguish a genuine cycle — the same instance re-entered along the path — from a graph that is
merely deeper than the bound, so the reader knows whether their model is wrong or their bound is.

The path SHALL identify where the descent stopped. It SHALL be permitted to carry the deepest stretch
of the descent rather than every step of it: recording every step would cost on a path that carries no
error, and the deepest steps are what name the structure that would not terminate.

A self-referencing but acyclic structure SHALL continue to compare normally, reporting at the nested
paths it reaches, for as long as it stays within the bound.

Detecting this SHALL NOT change the changes reported for any structure that stays within the bound,
and SHALL NOT change the order in which they are reported.

#### Scenario: A cycle is reported instead of overflowing the stack

- **WHEN** two instances whose graph contains a cycle are compared
- **THEN** the library raises its declared cyclic-structure exception
- **AND** the exception names the path at which the descent stopped
- **AND** no `StackOverflowError` is raised

#### Scenario: A deep but acyclic structure says so

- **WHEN** two instances nested deeper than the bound, with no instance repeated, are compared
- **THEN** the declared cyclic-structure exception is raised
- **AND** its message states that no instance was re-entered, distinguishing depth from a cycle

#### Scenario: A self-referencing structure within the bound is unaffected

- **WHEN** two `Node(name, next: Node?)` chains three levels deep are compared and differ at the
  innermost `name`
- **THEN** one value change is reported at `next.next.name`
- **AND** no exception is raised

#### Scenario: Reported changes are unchanged for ordinary models

- **WHEN** any model that stays within the bound is compared before and after this change
- **THEN** the same changes are reported, at the same paths, in the same order

### Requirement: A hand-written differ that describes no comparison is rejected where it is built

A hand-written differ SHALL fail where it is built when it names no property and declares no subtype,
because such a differ reports every pair of instances as equivalent however much they differ — the
same mistake the library rejects as a compile error for an annotated class that offers nothing to
compare.

The failure SHALL name what is missing.

This SHALL NOT reject a differ that names only subtypes, nor one that names only properties: either
alone describes a comparison.

#### Scenario: An empty hand-written differ is rejected

- **WHEN** a differ is built with an empty block
- **THEN** building it fails with an error stating that it names no property and no subtype

#### Scenario: A differ naming only subtypes is accepted

- **WHEN** a differ is built naming two subtypes and no property of its own
- **THEN** building it succeeds
- **AND** it dispatches on the runtime subtype as declared

### Requirement: A builder block cannot reach the members of an enclosing builder

Where one of the library's builder blocks encloses another — a routing framed under a property, a
routing over a collection's elements, a differ that nests a differ — the inner block SHALL NOT expose
the members of the outer block. Calling an outer builder's member from an inner block SHALL be a
compile error.

Reaching the outer builder deliberately SHALL remain possible by naming its receiver explicitly, which
is the standard Kotlin escape from a marked scope.

Nothing about which changes a correctly-written routing or differ receives SHALL change; this closes a
way of writing one that never had a defined meaning.

#### Scenario: An outer routing's handler cannot be registered from a nested frame

- **WHEN** a routing over `Order` frames `billing` and, inside that frame, names a property of `Order`
- **THEN** the code does not compile

#### Scenario: An element routing cannot register a property handler

- **WHEN** a routing over a collection's elements calls a member belonging to the enclosing routing
- **THEN** the code does not compile

#### Scenario: An explicitly qualified receiver still reaches the outer builder

- **WHEN** an inner block names the outer builder's receiver explicitly and calls its member
- **THEN** the code compiles
- **AND** the handler is registered on the outer routing

### Requirement: A builder block runs exactly once, so a caller can initialise a value in it

Every function of the library that takes a builder block SHALL state that it invokes that block
exactly once, so that a `val` declared outside the block can be assigned inside it and read after it
without the compiler reporting it as possibly uninitialised.

#### Scenario: A value is initialised inside a builder block

- **WHEN** a caller declares an uninitialised `val`, assigns it inside a `differ` or routing block,
  and reads it after the block
- **THEN** the code compiles
