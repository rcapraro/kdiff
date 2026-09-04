## ADDED Requirements

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
two type names, and SHALL additionally compare the properties declared by the sealed parent itself,
reporting those as ordinary changes. It SHALL NOT descend into properties that belong to only one
of the two subclasses.

When the sealed parent declares no properties of its own, a subclass change SHALL produce the type
change alone.

#### Scenario: The same subclass on both sides delegates

- **WHEN** both instances are `Card` and only `Card.last4` differs
- **THEN** the result reports one value change at `last4`
- **AND** no type change is reported

#### Scenario: A subclass swap reports a type change

- **WHEN** the old instance is a `Card` and the new one is a `Transfer`
- **THEN** the result reports a type change from `Card` to `Transfer`

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

A path SHALL render in a form a reader can follow back to the source: properties separated by dots,
an index in square brackets, a key as the key property and its value in square brackets.

#### Scenario: A property path renders with dots

- **WHEN** a change is found at the `street` property of the `address` property
- **THEN** its path renders as `address.street`

#### Scenario: An index path renders with brackets

- **WHEN** a change is found at index 2 of an unkeyed `tags` list
- **THEN** its path renders as `tags[2]`

#### Scenario: A key path renders with the key property and value

- **WHEN** a change is found in the element keyed `A2` by the `id` property of an `addresses` list
- **THEN** its path renders as `addresses[id=A2]`

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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Field comparison is out of scope for this change

**Reason**: This requirement fixed a deliberate, temporary baseline — a generated differ compared
no fields and reported nothing for any pair of instances — so that the generation pipeline could be
proved end to end before comparison semantics were specified. This change introduces those
semantics, which is exactly what the baseline existed to be replaced by.

**Migration**: A generated differ now reports the differences it finds. Code that relied on a diff
always being empty must be updated to handle real changes; the requirements added by this change
describe what a differ reports for each property shape. Nothing else about the generated API
changes — the differ's name, package and signature are unchanged.
