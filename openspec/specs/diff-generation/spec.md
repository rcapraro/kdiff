## Purpose

Defines what annotating a Kotlin class makes available to the annotating project: the differ that
becomes callable for that class, the result that differ returns when comparing two instances of it,
and the compile errors that reject an annotation the library cannot honour.

## Requirements

### Requirement: A differ is generated for every annotated data class

Annotating a data class with `@Diffable` SHALL make a differ for that class available to the
annotating module at compile time, without the author writing or registering anything else.

The differ SHALL be a singleton named `<Type>Differ`, declared in the same package as the annotated
class, implementing `Differ<Type>`. It SHALL expose `diff(before: Type, after: Type): Diff`.

The differ SHALL be generated only for annotated classes. An unannotated class SHALL NOT gain one.

#### Scenario: Annotated data class gains a differ

- **WHEN** a module contains `@Diffable data class Person(val id: String, val name: String)` in
  package `demo`
- **THEN** the module compiles successfully
- **AND** `demo.PersonDiffer` is available to that module's own code
- **AND** `demo.PersonDiffer.diff(a, b)` accepts two `Person` instances and returns a `Diff`

#### Scenario: Unannotated class gains nothing

- **WHEN** a module contains `data class Order(val id: String)` with no `@Diffable` annotation
- **THEN** the module compiles successfully
- **AND** no `OrderDiffer` exists
- **AND** referring to `OrderDiffer` is a compile error

#### Scenario: Two annotated classes each get their own differ

- **WHEN** a module annotates both `Person` and `Address` with `@Diffable`
- **THEN** both `PersonDiffer` and `AddressDiffer` are available
- **AND** each accepts only instances of its own type

### Requirement: A diff result reports an ordered list of changes

`diff` SHALL return a `Diff` describing how `after` differs from `before`. The `Diff` SHALL expose
the changes it found as an ordered list, and SHALL report directly whether it found none.

A `Diff` SHALL be a value: comparing the same pair of instances twice SHALL produce equal results,
and calling `diff` SHALL NOT modify either instance.

#### Scenario: Result exposes its changes and its emptiness

- **WHEN** a caller obtains a `Diff` from any differ
- **THEN** the `Diff` exposes an ordered list of changes
- **AND** the `Diff` reports whether that list is empty
- **AND** an empty list and "reports empty" always agree

#### Scenario: Diffing is repeatable and does not mutate its inputs

- **WHEN** a caller calls `diff(a, b)` twice with the same two instances
- **THEN** both calls return equal results
- **AND** `a` and `b` are unchanged

### Requirement: Annotating an unsupported declaration is a compile error

`@Diffable` SHALL be honoured on data classes and on sealed classes and sealed interfaces. Applied
to any other declaration, it SHALL fail the compilation with an error that names the offending
declaration and states which declarations `@Diffable` accepts.

The error SHALL be reported at the location of the offending declaration, so an IDE and a build
log both point at the annotation's target rather than at generated code.

An unsupported declaration SHALL never be silently skipped: the build SHALL NOT succeed while
producing no differ for it.

#### Scenario: Annotated regular class is rejected

- **WHEN** a module contains `@Diffable class Person(val name: String)` — a class that is neither a
  data class nor sealed
- **THEN** compilation fails
- **AND** the error message names `Person` and states which declarations `@Diffable` accepts
- **AND** the error is reported at the declaration of `Person`

#### Scenario: Annotated interface is rejected

- **WHEN** a module contains `@Diffable interface Shape` — a plain interface, not a sealed one
- **THEN** compilation fails with the same error, reported at `Shape`

#### Scenario: Annotated sealed interface is accepted

- **WHEN** a module contains `@Diffable sealed interface Payment` whose subclasses are all
  `@Diffable`
- **THEN** compilation succeeds
- **AND** a differ for `Payment` is available

#### Scenario: Annotated sealed class is accepted

- **WHEN** a module contains `@Diffable sealed class Payment` whose subclasses are all `@Diffable`
- **THEN** compilation succeeds
- **AND** a differ for `Payment` is available

#### Scenario: Annotated object is rejected

- **WHEN** a module contains `@Diffable object Registry`
- **THEN** compilation fails with the same error, reported at `Registry`

#### Scenario: Annotated enum class is rejected

- **WHEN** a module contains `@Diffable enum class Status { OPEN, CLOSED }`
- **THEN** compilation fails with the same error, reported at `Status`

#### Scenario: A rejected declaration blocks the build

- **WHEN** a module contains one valid `@Diffable` data class and one `@Diffable` non-sealed
  interface
- **THEN** compilation fails
- **AND** the failure is attributable to the interface, not to the valid data class

### Requirement: Generating differs adds no runtime dependency beyond the result types

A module that annotates classes SHALL need only the annotations and the result types on its
runtime classpath. Whatever performs the generation SHALL NOT appear on a consuming module's
runtime or published dependencies.

#### Scenario: Consumer runtime classpath excludes the generator

- **WHEN** a module annotates a class with `@Diffable` and its build succeeds
- **THEN** its runtime classpath contains the annotations and the result types
- **AND** its runtime classpath contains nothing that performs generation

#### Scenario: Generated code depends only on the result types

- **WHEN** a generated differ is compiled
- **THEN** it references only the annotated class, the Kotlin standard library, and the result types

### Requirement: Value properties are compared by equality

A generated differ SHALL compare each property whose type is a primitive, a `String`, or an enum
using equality, and SHALL report a difference as a value change carrying the old and the new value
at that property's path.

Equal values SHALL produce no change. Properties SHALL be compared in declaration order, and
changes SHALL appear in that order.

#### Scenario: A changed string property is reported

- **WHEN** `PersonDiffer.diff(Person("1", "Ada"), Person("1", "Grace"))` is called on
  `@Diffable data class Person(val id: String, val name: String)`
- **THEN** the result reports exactly one change
- **AND** that change is a value change at path `name` from `"Ada"` to `"Grace"`

#### Scenario: Identical instances produce no changes

- **WHEN** two instances with equal values for every property are compared
- **THEN** the result reports no changes

#### Scenario: Several changed properties are reported in declaration order

- **WHEN** both `id` and `name` differ between the two instances
- **THEN** the result reports two value changes
- **AND** the change at `id` appears before the change at `name`

#### Scenario: An enum property is compared by value

- **WHEN** a property of an enum type holds `OPEN` before and `CLOSED` after
- **THEN** the result reports one value change at that property's path from `OPEN` to `CLOSED`

### Requirement: A nullable property reports a null on either side as a value change

A nullable property SHALL be compared by equality like any other value. A transition from `null` to
a value, or from a value to `null`, SHALL be reported as a value change with `null` on the
corresponding side.

A null-to-null comparison SHALL produce no change.

Appearance and disappearance SHALL NOT be used for properties: a property's path exists on both
sides or on neither, so added and removed are reserved for collection elements and map entries.

#### Scenario: Null becomes a value

- **WHEN** a nullable `nickname` property is `null` before and `"Ada"` after
- **THEN** the result reports a value change at `nickname` from `null` to `"Ada"`
- **AND** the change is not reported as an addition

#### Scenario: A value becomes null

- **WHEN** a nullable `nickname` property is `"Ada"` before and `null` after
- **THEN** the result reports a value change at `nickname` from `"Ada"` to `null`
- **AND** the change is not reported as a removal

#### Scenario: Null on both sides is not a change

- **WHEN** a nullable property is `null` in both instances
- **THEN** the result reports no change at that property

### Requirement: A property can be excluded from comparison

A property annotated `@DiffIgnore` SHALL NOT be compared, and SHALL never contribute a change,
however much its value differs between the two instances.

#### Scenario: An ignored property never produces a change

- **WHEN** `@DiffIgnore val lastSeen: String` differs between the two instances and no other
  property differs
- **THEN** the result reports no changes

#### Scenario: Ignoring one property leaves the others compared

- **WHEN** an ignored property and a compared property both differ
- **THEN** the result reports exactly one change, at the compared property

### Requirement: A nested annotated property is compared field by field

A property whose type is itself `@Diffable` SHALL be compared by delegating to that type's differ.
Each change the nested differ reports SHALL appear at a path prefixed by the property's own
segment, so a change's path reads from the root being diffed.

A nested property that is nullable SHALL be compared by delegation when both sides are non-null,
and as a value change when either side is `null`.

#### Scenario: A change inside a nested type is reported at a nested path

- **WHEN** `Person` has an `address: Address` property and only `address.street` differs
- **THEN** the result reports one value change
- **AND** its path is `address.street`

#### Scenario: Nesting composes to arbitrary depth

- **WHEN** a change occurs three levels down, at `company.address.city`
- **THEN** the result reports that change at path `company.address.city`

#### Scenario: A nested property becoming null is a value change

- **WHEN** a nullable `address` property is an `Address` before and `null` after
- **THEN** the result reports a value change at `address`
- **AND** no changes are reported at paths beneath `address`

### Requirement: A list whose element type declares a key is compared by key

When a list's element type has a property annotated `@DiffKey`, elements SHALL be matched between
the two lists by that key rather than by position.

For each matched pair the differ SHALL report the element's own changes at a path identifying the
element by its key. An element present only in the new list SHALL be reported as added; present
only in the old list, as removed. A matched element whose position differs between the two lists
SHALL be reported as moved, carrying its old and new index.

#### Scenario: A modified element is reported at its key

- **WHEN** the list contains an element with key `A2` in both instances and its `street` differs
- **THEN** the result reports a value change at path `addresses[id=A2].street`

#### Scenario: A new element is reported as added

- **WHEN** an element with key `A3` is present in the new list and absent from the old
- **THEN** the result reports an addition at path `addresses[id=A3]` carrying the new element

#### Scenario: A missing element is reported as removed

- **WHEN** an element with key `A1` is present in the old list and absent from the new
- **THEN** the result reports a removal at path `addresses[id=A1]` carrying the old element

#### Scenario: A reordered element is reported as moved

- **WHEN** the element with key `A2` is at index 0 in the old list and index 1 in the new, and its
  contents are unchanged
- **THEN** the result reports a move at path `addresses[id=A2]` from index 0 to index 1
- **AND** it is not reported as removed and added

#### Scenario: An unchanged list produces no changes

- **WHEN** both lists contain the same elements with the same keys in the same order and no element
  differs
- **THEN** the result reports no changes

### Requirement: A list whose element type declares no key is compared by position

When a list's element type has no `@DiffKey` property, elements SHALL be compared index by index.

An index present in both lists SHALL be compared by the rules for its element type, at a path
identifying the element by its index. An index present only in the new list SHALL be reported as
added; present only in the old list, as removed. A move SHALL never be reported for such a list.

#### Scenario: A changed element is reported at its index

- **WHEN** the element at index 1 differs and the element type has no key
- **THEN** the result reports the change at a path identifying index 1

#### Scenario: A longer new list reports additions at the trailing indices

- **WHEN** the old list has two elements and the new list has three, the first two being equal
- **THEN** the result reports one addition at index 2

#### Scenario: A shorter new list reports removals at the trailing indices

- **WHEN** the old list has three elements and the new list has two, the first two being equal
- **THEN** the result reports one removal at index 2

### Requirement: A set is compared as unordered membership

A property whose type is a `Set` SHALL be compared by membership, not by position. An element in
the new set and not the old SHALL be reported as added; an element in the old and not the new, as
removed.

A set SHALL never report a move, and SHALL never report a change to an individual element: sets are
unordered and their elements have no stable identity to compare against.

#### Scenario: Added and removed members are reported

- **WHEN** the old set is `{"a", "b"}` and the new set is `{"b", "c"}`
- **THEN** the result reports one removal of `"a"` and one addition of `"c"`
- **AND** no change is reported for `"b"`

#### Scenario: Reordering a set is not a change

- **WHEN** the two sets contain the same members
- **THEN** the result reports no changes

### Requirement: A map is compared by entry key

A property whose type is a `Map` SHALL be compared by key. A key present only in the new map SHALL
be reported as added; a key present only in the old, as removed. A key present in both whose value
differs SHALL be compared by the rules for the value's type, at a path identifying the entry by its
key.

#### Scenario: A changed value is reported at its entry key

- **WHEN** the entry `"eur"` maps to a different value in the two instances
- **THEN** the result reports the change at a path identifying entry `"eur"`

#### Scenario: Added and removed entries are reported

- **WHEN** the new map has an entry `"usd"` the old lacks, and the old has an entry `"gbp"` the new
  lacks
- **THEN** the result reports one addition at `"usd"` and one removal at `"gbp"`

### Requirement: An annotated sealed type dispatches on the runtime subclass

`@Diffable` SHALL be honoured on a sealed class or sealed interface whose subclasses are all
themselves `@Diffable`. The generated differ SHALL dispatch on the runtime type of the two
instances.

When both instances are the same subclass, the differ SHALL delegate to that subclass's differ and
report its changes unchanged.

When the two instances are different subclasses, the differ SHALL report a type change carrying the
two type names **and the two values**, and SHALL additionally compare the properties declared by
the sealed parent itself, reporting those as ordinary changes. It SHALL NOT descend into properties
that belong to only one of the two subclasses.

A type change SHALL carry the values because a type name alone cannot be turned back into an
instance: without them a subclass change is a difference that can be reported but never replayed.

When the sealed parent declares no properties of its own, a subclass change SHALL produce the type
change alone.

#### Scenario: The same subclass on both sides delegates

- **WHEN** both instances are `Card` and only `Card.last4` differs
- **THEN** the result reports one value change at `last4`
- **AND** no type change is reported

#### Scenario: A subclass swap reports a type change

- **WHEN** the old instance is a `Card` and the new one is a `Transfer`
- **THEN** the result reports a type change from `Card` to `Transfer`

#### Scenario: A subclass swap carries both values

- **WHEN** the old instance is a `Card` and the new one is a `Transfer`
- **THEN** the reported type change carries the `Card` as its before value and the `Transfer` as
  its after value

#### Scenario: A subclass swap also reports the sealed parent's own properties

- **WHEN** the sealed parent declares `val amount: String`, the old instance is `Card` with amount
  `"10"`, and the new is `Transfer` with amount `"12"`
- **THEN** the result reports a type change from `Card` to `Transfer`
- **AND** a value change at `amount` from `"10"` to `"12"`

#### Scenario: A subclass swap does not descend into subclass-only properties

- **WHEN** the old instance is `Card(last4 = "1234")` and the new is `Transfer(iban = "FR76")`
- **THEN** no change is reported at `last4` or at `iban`

#### Scenario: A sealed-typed property nests

- **WHEN** `Order` has a `payment: Payment` property and the two payments are different subclasses
- **THEN** the type change is reported at path `payment`

### Requirement: A differ can be written by hand for a type that cannot be annotated

The library SHALL provide a way to build a `Differ<T>` in ordinary Kotlin, for a type whose source
cannot be annotated. A hand-written differ SHALL be indistinguishable from a generated one to any
code that consumes it, so the two compose when one type nests the other.

A property SHALL be able to name a hand-written differ with `@DiffWith`, and the generated code
SHALL delegate that property's comparison to it, prefixing its paths like any nested delegation.

#### Scenario: A hand-written differ compares a third-party type

- **WHEN** a differ for `Money` is built by hand naming its `amount` and `currency` properties
- **AND** two `Money` instances differ in `amount`
- **THEN** that differ reports one value change at `amount`

#### Scenario: A property delegates to a hand-written differ

- **WHEN** `Invoice.total: Money` is annotated with `@DiffWith` naming the hand-written `Money`
  differ, and the two totals differ in `amount`
- **THEN** the result reports one value change at path `total.amount`

#### Scenario: A hand-written differ nests inside a generated one

- **WHEN** a hand-written differ is reached through two levels of generated delegation
- **THEN** its changes appear at the full path from the root

### Requirement: A change identifies where it was found

Every change SHALL carry a path locating it relative to the root being diffed. A path SHALL be
composed of segments naming a property, an index into a positional collection, or a key into a
keyed collection.

A key segment SHALL retain the key itself, not a rendering of it, so that an element or entry it
identifies can be located and reconstructed. A key that is not a string SHALL survive in the path
as the value it is.

A path SHALL render in a form a reader can follow back to the source: properties separated by dots,
an index in square brackets, a key as the key property and its value in square brackets. Rendering
a key SHALL use its string form, so rendered paths are unchanged by the key being retained.

#### Scenario: A property path renders with dots

- **WHEN** a change is found at the `street` property of the `address` property
- **THEN** its path renders as `address.street`

#### Scenario: An index path renders with brackets

- **WHEN** a change is found at index 2 of an unkeyed `tags` list
- **THEN** its path renders as `tags[2]`

#### Scenario: A key path renders with the key property and value

- **WHEN** a change is found in the element keyed `A2` by the `id` property of an `addresses` list
- **THEN** its path renders as `addresses[id=A2]`

#### Scenario: A non-string key is retained as its own value

- **WHEN** a change is found at the entry keyed `1` of a map whose keys are integers
- **THEN** the path's key segment holds the integer `1`, not the text `"1"`
- **AND** the path still renders as `[key=1]`

### Requirement: A diff can be viewed as a tree

`Diff` SHALL expose the same changes as a hierarchy that mirrors the shape of the object graph, so
that changes sharing a path prefix are grouped under it.

The tree SHALL contain exactly the changes the flat list contains — neither view invents or drops a
change.

#### Scenario: Changes sharing a prefix are grouped

- **WHEN** a diff contains changes at `address.street` and `address.city`
- **THEN** the tree has a single `address` node with both changes beneath it

#### Scenario: The tree of an empty diff is empty

- **WHEN** a diff reports no changes
- **THEN** its tree has no nodes

#### Scenario: The tree holds the same changes as the flat list

- **WHEN** a diff contains changes at several depths
- **THEN** collecting every change from the tree yields exactly the changes in the flat list

### Requirement: A diff can be rendered as text

`Diff` SHALL render to a human-readable text form in which each change occupies one line showing
its path and what happened, and the kind of each change is distinguishable.

Rendering SHALL NOT alter the diff.

#### Scenario: A value change renders with both values

- **WHEN** a diff contains a value change at `address.street` from `"1 Rue X"` to `"2 Rue Y"`
- **THEN** the rendered text contains a line naming `address.street` and both values

#### Scenario: Each kind of change is distinguishable

- **WHEN** a diff contains an addition, a removal, a move and a type change
- **THEN** each renders on its own line
- **AND** the four kinds can be told apart from the text

#### Scenario: An empty diff renders without claiming changes

- **WHEN** a diff reports no changes
- **THEN** the rendered text asserts no change

### Requirement: A property kdiff cannot compare is a compile error

A property whose type is neither a value type, an annotated type, a supported collection, nor
pointed at a hand-written differ SHALL fail the compilation with an error naming the property and
its type, and pointing at the hand-written differ escape hatch.

Such a property SHALL NOT be silently compared by equality: comparing a rich type as an opaque
value produces a diff that is technically correct and useless.

#### Scenario: An unsupported property type is rejected

- **WHEN** an annotated data class has a property of a third-party type with no `@DiffWith`
- **THEN** compilation fails
- **AND** the error names the property and its type, and mentions the escape hatch
- **AND** the error is reported at the property

#### Scenario: The same property with a hand-written differ compiles

- **WHEN** that property is annotated with `@DiffWith` naming a differ for its type
- **THEN** compilation succeeds

### Requirement: A generic annotated class is a compile error

`@Diffable` on a class with type parameters SHALL fail the compilation with an error naming the
class and stating that type parameters are not supported.

A differ for a generic type would need a differ for each type argument, which cannot be resolved at
the declaration, so rejecting it is preferred to generating something that compiles and misbehaves.

#### Scenario: A generic annotated class is rejected

- **WHEN** a module contains `@Diffable data class Box<T>(val value: T)`
- **THEN** compilation fails
- **AND** the error names `Box` and states that type parameters are not supported
- **AND** the error is reported at the declaration of `Box`

### Requirement: A sealed type with an unannotated subclass is a compile error

`@Diffable` on a sealed type SHALL fail the compilation when any of its subclasses is not itself
`@Diffable`, with an error naming the sealed type and the offending subclass.

Dispatch has to cover every subclass; a missing one would leave a branch that cannot be generated.

#### Scenario: An unannotated subclass is rejected

- **WHEN** `@Diffable sealed interface Payment` has subclasses `Card` annotated and `Transfer` not
- **THEN** compilation fails
- **AND** the error names both `Payment` and `Transfer`

### Requirement: More than one key on a type is a compile error

A type SHALL declare at most one `@DiffKey` property. Two or more SHALL fail the compilation with
an error naming the type and the competing properties.

#### Scenario: Two keys on one type are rejected

- **WHEN** a data class annotates both `id` and `code` with `@DiffKey`
- **THEN** compilation fails
- **AND** the error names the type and both properties

### Requirement: An annotated class can declare a tracking scope

`@Trackable` SHALL be honoured on a class that is also `@Diffable`. It SHALL declare every compared
property of that class as tracked, at the depth the annotation states, exactly as `@Diffable`
declares every property compared.

The generated declaration for such a class SHALL expose that scope as a `TrackScope` — the same type
a hand-written scope produces — so that a tracker can consume either without distinguishing them,
and so that no type structure is read at runtime.

Tracking SHALL be exposed on the same generated declaration that compares the type and applies
changes to it. The declaration's existing name, package, comparison signature and application
signature SHALL NOT change.

Declaring a scope SHALL NOT change how anything is compared: the changes `diff` reports for a class
SHALL be identical whether or not the class is `@Trackable`.

#### Scenario: An annotated class exposes a declared scope

- **WHEN** a module contains
  `@Diffable @Trackable(depth = 1) data class Order(val reference: String, val status: String)`
- **THEN** the module compiles successfully
- **AND** `OrderDiffer` exposes a scope naming `reference` and `status`, each at depth 1
- **AND** a tracker over `Order` created with no field tracks both properties at depth 1

#### Scenario: The scope is reached through the same declaration as comparison and application

- **WHEN** a class is both `@Diffable` and `@Trackable`
- **THEN** comparing it, applying changes to it, and reading its scope are all reached through
  `<Type>Differ`
- **AND** its comparison and application signatures are unchanged

#### Scenario: Declaring a scope leaves comparison unchanged

- **WHEN** the same data class is compiled with and without `@Trackable`
- **THEN** `diff(a, b)` reports the same changes in the same order in both cases

#### Scenario: An unannotated class exposes no scope

- **WHEN** a module contains `@Diffable data class Order(val reference: String)` with no tracking
  annotation
- **THEN** `OrderDiffer` exposes no scope
- **AND** its generated declaration is unchanged from one produced without this capability
- **AND** a tracker over `Order` created with no field tracks every property at unlimited depth

#### Scenario: An annotated sealed type declares a scope

- **WHEN** `@Diffable @Trackable sealed interface Payment` has all its subclasses `@Diffable`
- **THEN** compilation succeeds
- **AND** `PaymentDiffer` exposes a scope covering the properties the sealed parent declares

### Requirement: A property can be excluded from a declared tracking scope

`@TrackIgnore` SHALL exclude a property from its class's declared scope, as `@DiffIgnore` excludes a
property from comparison. The property SHALL still be compared: excluding it from tracking SHALL NOT
change any diff.

There SHALL be no property-level way to opt a property *into* tracking, for the same reason
comparison has none: the class opts in, and properties opt out.

#### Scenario: An excluded property is not in the declared scope

- **WHEN** a module contains
  `@Diffable @Trackable data class Order(val reference: String, @TrackIgnore val status: String)`
- **THEN** `OrderDiffer` exposes a scope naming `reference` and not `status`

#### Scenario: An excluded property is still compared

- **WHEN** that class is diffed with instances whose `status` differs
- **THEN** the diff reports a change at `status`

### Requirement: A property can declare its own tracking depth

`@TrackDepth` SHALL set the depth at which one property of a `@Trackable` class is tracked, taking
precedence over the class's depth for that property alone.

#### Scenario: A property's depth overrides the class's

- **WHEN** a module contains
  `@Diffable @Trackable(depth = 1) data class Order(val reference: String, @TrackDepth(2) val billing: Address)`
- **THEN** `OrderDiffer` exposes a scope with `reference` at depth 1 and `billing` at depth 2

#### Scenario: A property depth applies without a class depth

- **WHEN** a `@Trackable` class states no depth and one property is `@TrackDepth(1)`
- **THEN** that property is tracked at depth 1 and every other property at unlimited depth

### Requirement: An unlimited tracking depth is the default depth

`@Trackable` SHALL state its depth through a `depth` parameter whose default means unlimited: a
`@Trackable` declaration with no explicit depth SHALL track at unlimited depth, excluding nothing on
the grounds of depth.

#### Scenario: A bare annotation declares unlimited depth

- **WHEN** a module contains
  `@Diffable @Trackable data class Order(val reference: String, val billing: Address)`
- **THEN** `OrderDiffer` exposes a scope with unlimited depth for both properties
- **AND** a tracker over `Order` created with no field reports a change at `billing.city`

### Requirement: An invalid tracking depth is a compile error

A `depth` of zero, or any negative value other than the constant that denotes unlimited depth, SHALL
fail the compilation with an error that names the offending declaration and states the values `depth`
accepts.

The error SHALL be reported at the location of the offending declaration, whether that is the class
or the property.

#### Scenario: A zero depth on a class is rejected

- **WHEN** a module contains `@Diffable @Trackable(depth = 0) data class Order(val reference: String)`
- **THEN** compilation fails
- **AND** the error names `Order` and states which values `depth` accepts
- **AND** the error is reported at the declaration of `Order`

#### Scenario: A negative depth other than the unlimited constant is rejected

- **WHEN** a property is annotated `@TrackDepth(-2)`
- **THEN** compilation fails with the same error, reported at that property

### Requirement: Contradictory tracking annotations are a compile error

A property SHALL NOT be annotated both `@TrackIgnore` and `@TrackDepth`: one excludes the property
from the scope and the other configures it within the scope.

A property SHALL NOT be annotated `@TrackIgnore` or `@TrackDepth` while it is also `@DiffIgnore`,
because an ignored property produces no changes and so can never be tracked.

Each SHALL fail the compilation with an error that names the offending property and states which
annotations conflict, reported at that property.

A contradictory annotation SHALL never be resolved by preferring one side: the build SHALL NOT
succeed while silently ignoring one of the two annotations.

#### Scenario: Excluding and configuring the same property is rejected

- **WHEN** a property is annotated both `@TrackIgnore` and `@TrackDepth(2)`
- **THEN** compilation fails
- **AND** the error names the property and states that the two annotations conflict
- **AND** the error is reported at that property

#### Scenario: Configuring an ignored property is rejected

- **WHEN** a property is annotated both `@DiffIgnore` and `@TrackDepth(2)`
- **THEN** compilation fails with an error naming the property and stating that an ignored property
  cannot be tracked

#### Scenario: Excluding an ignored property from tracking is rejected

- **WHEN** a property is annotated both `@DiffIgnore` and `@TrackIgnore`
- **THEN** compilation fails with the same error, reported at that property

### Requirement: A tracking annotation with nothing to configure is a compile error

`@Trackable` SHALL be honoured only on a class that is also `@Diffable`: no declaration is generated
for an unannotated class, so nothing could expose the scope.

`@TrackIgnore` and `@TrackDepth` SHALL be honoured only on a property of a `@Trackable` class: with no
declared scope, there is nothing for either to exclude from or configure, and the annotation would
have no effect.

Each SHALL fail the compilation with an error naming the offending declaration and stating what the
annotation requires, reported at that declaration. The library SHALL NOT accept a tracking annotation
that silently does nothing, and SHALL point the author at the annotation that is missing.

#### Scenario: A trackable class that is not diffable is rejected

- **WHEN** a module contains `@Trackable data class Order(val reference: String)` with no `@Diffable`
- **THEN** compilation fails
- **AND** the error names `Order` and states that `@Trackable` requires `@Diffable`
- **AND** the error is reported at the declaration of `Order`

#### Scenario: A property annotation without a trackable class is rejected

- **WHEN** a module contains
  `@Diffable data class Order(val reference: String, @TrackIgnore val status: String)` and the class
  is not `@Trackable`
- **THEN** compilation fails
- **AND** the error names `status` and states that `@TrackIgnore` requires a `@Trackable` class

#### Scenario: A property depth without a trackable class is rejected

- **WHEN** the same class instead annotates a property `@TrackDepth(2)` and is not `@Trackable`
- **THEN** compilation fails with the corresponding error, reported at that property

#### Scenario: A rejected tracking annotation blocks the build

- **WHEN** a module contains one correctly trackable class and one class with a contradictory
  tracking annotation
- **THEN** compilation fails
- **AND** the failure is attributable to the contradictory annotation, not to the valid class

### Requirement: Declaring a tracking scope adds no runtime dependency

A declared scope SHALL be expressed in terms of the result types a consumer already needs. A module
that declares a tracking scope SHALL need only the annotations and the result types on its runtime
classpath, and its generated declaration SHALL reference nothing else.

#### Scenario: A tracked module's generated code depends only on the result types

- **WHEN** a generated declaration exposing a scope is compiled
- **THEN** it references only the annotated class, the Kotlin standard library, and the result types

#### Scenario: A tracked module's runtime classpath excludes the generator

- **WHEN** a module declares a tracking scope and its build succeeds
- **THEN** its runtime classpath contains nothing that performs generation
- **AND** it contains no reflection library
