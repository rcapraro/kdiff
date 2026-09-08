## ADDED Requirements

### Requirement: A nullable collection property reports a null on either side as a value change

A property whose type is a nullable `List`, `Set` or `Map` SHALL follow the rule a nullable nested
property follows. When both sides are non-null, the two collections SHALL be compared by the rules for
that collection shape — by key, by position, by membership or by entry key — exactly as a non-null
property of the same type is. When either side is `null` and the other is not, the comparison SHALL
report one value change at the property carrying both sides, and SHALL report nothing beneath it. When
both sides are `null`, nothing SHALL be reported.

Appearance and disappearance SHALL NOT be used: a property's path exists on both sides or on neither,
so added and removed remain reserved for the elements and entries *inside* a collection.

A hand-written differ SHALL be able to describe a nullable collection property with the same builder
that describes the non-null one, and SHALL report the same changes at the same paths as the annotated
declaration of the same model.

Reporting a null transition SHALL NOT exempt the present side from a precondition its shape requires.
Where one side is null and the other is a list matched by key, that list SHALL be examined and a
repeated key SHALL be refused exactly as it is when both sides are present: having no representable
diff is a fact about the list itself, not about what it is being compared against. Reporting the
transition instead would hand the caller a change carrying a list that the very next comparison of it
refuses.

A nullable declaration of a property SHALL therefore refuse whatever the non-null declaration of that
same property refuses.

#### Scenario: A null transition still refuses a repeated key on the present side

- **WHEN** `addresses: List<Address>?` is `null` before and, after, holds two elements sharing key `A1`
- **THEN** the comparison fails with an error naming the list, the key property and the value `A1`
- **AND** no value change is reported at `addresses`

#### Scenario: A repeated key is refused when the present side is the old one

- **WHEN** `addresses: List<Address>?` holds two elements sharing key `A1` before and is `null` after
- **THEN** the comparison fails with the same error

#### Scenario: A null transition is reported when the present side is sound

- **WHEN** `addresses: List<Address>?` is `null` before and, after, holds elements with distinct keys
- **THEN** the comparison reports one value change at `addresses` carrying both sides

#### Scenario: Two null sides examine nothing

- **WHEN** a nullable keyed list is `null` on both sides
- **THEN** the comparison reports no change and raises nothing

#### Scenario: A nullable list becoming a list is a value change at the property

- **WHEN** `tags: List<String>?` is `null` before and `["a", "b"]` after
- **THEN** the result reports exactly one value change at `tags` from `null` to `["a", "b"]`
- **AND** no addition is reported at `tags[0]` or `tags[1]`

#### Scenario: A nullable list becoming null is a value change at the property

- **WHEN** `tags: List<String>?` is `["a"]` before and `null` after
- **THEN** the result reports exactly one value change at `tags` from `["a"]` to `null`
- **AND** no removal is reported beneath `tags`

#### Scenario: Two present nullable lists are compared as lists

- **WHEN** `tags: List<String>?` is `["a", "b"]` before and `["a", "c"]` after
- **THEN** the result reports exactly what a non-null `List<String>` property would: one value change at
  `tags[1]` from `"b"` to `"c"`

#### Scenario: A nullable keyed list descends into its elements when both sides are present

- **WHEN** `addresses: List<Address>?` holds an element keyed `A2` on both sides whose `street` differs
- **THEN** the result reports a value change at `addresses[id=A2].street`

#### Scenario: A nullable set and a nullable map follow the same rule

- **WHEN** `labels: Set<String>?` is `null` before and present after, and `amounts: Map<String, String>?`
  is present before and `null` after
- **THEN** the result reports one value change at `labels` and one at `amounts`, and nothing beneath
  either

#### Scenario: Null on both sides is not a change

- **WHEN** a nullable collection property is `null` in both instances
- **THEN** the result reports no change at that property

#### Scenario: A hand-written nullable collection behaves exactly as a generated one

- **WHEN** a hand-written differ describes `tags: List<String>?` with the positional list builder, and
  the same transitions as above are compared with it and with the generated differ
- **THEN** both report equal changes, at equal paths, in the same order

### Requirement: A collection whose elements are nullable and compared by a differ is a compile error

A `List` whose element type is a nullable annotated type, or a `Map` whose value type is one, SHALL
fail the compilation with an error reported at the property. The error SHALL name the property and the
element type, state that an element compared by a differ cannot be null, and name the two ways out:
making the element type non-null, or pointing the property at a hand-written differ.

A `List` or `Map` whose nullable elements are compared as values SHALL NOT be affected: a value is
compared by equality, which is defined for `null`. A `Set` SHALL NOT be affected whatever its element
type, because a set is compared by membership alone.

The library SHALL NOT let such a property reach generated code: the error SHALL be attributable to the
declaration, never to a file the author did not write.

#### Scenario: A list of nullable annotated elements is rejected at the property

- **WHEN** an annotated data class declares `val stops: List<Address?>` and `Address` is `@Diffable`
- **THEN** compilation fails
- **AND** the error names `stops` and `Address`, states that an element compared by a differ cannot be
  null, and mentions both making the element non-null and `@DiffWith`
- **AND** the error is reported at `stops`

#### Scenario: A map of nullable annotated values is rejected at the property

- **WHEN** an annotated data class declares `val byRegion: Map<String, Address?>` and `Address` is
  `@Diffable`
- **THEN** compilation fails with the corresponding error, reported at `byRegion`

#### Scenario: A list of nullable values compiles

- **WHEN** an annotated data class declares `val notes: List<String?>`
- **THEN** compilation succeeds
- **AND** a `null` element and a `"x"` element at the same index report one value change at that index

#### Scenario: A set of nullable annotated elements compiles

- **WHEN** an annotated data class declares `val visited: Set<Address?>` and `Address` is `@Diffable`
- **THEN** compilation succeeds, and the set is compared by membership

### Requirement: A comparison annotation with nothing to configure is a compile error

`@DiffKey`, `@DiffIgnore` and `@DiffWith` SHALL be honoured only on a property of a class that is
`@Diffable`: no differ is generated for any other class, so nothing could read the annotation, and it
would have no effect.

`@DiffWith` SHALL NOT be combined with `@DiffIgnore` on one property: an ignored property is never
compared, so a differ named for it could never run.

Each SHALL fail the compilation with an error naming the offending property and stating what the
annotation requires, reported at that property. The library SHALL NOT accept a comparison annotation
that silently does nothing, and SHALL point the author at the annotation that is missing or the one
that conflicts.

#### Scenario: An ignored property on a class that is not diffable is rejected

- **WHEN** a module contains `data class Order(val reference: String, @DiffIgnore val note: String)`
  with no `@Diffable`
- **THEN** compilation fails
- **AND** the error names `note` and states that `@DiffIgnore` requires `@Diffable` on `Order`
- **AND** the error is reported at the declaration of `note`

#### Scenario: A key on a class that is not diffable is rejected

- **WHEN** a module contains `data class Address(@DiffKey val id: String, val city: String)` with no
  `@Diffable`, and no other class refers to it
- **THEN** compilation fails with the corresponding error, reported at `id`

#### Scenario: A hand-written differ reference on a class that is not diffable is rejected

- **WHEN** a module contains `data class Invoice(@DiffWith(MoneyDiffer::class) val total: Money)` with
  no `@Diffable`
- **THEN** compilation fails with the corresponding error, reported at `total`

#### Scenario: A hand-written differ reference on an ignored property is rejected as a conflict

- **WHEN** an annotated data class declares `@DiffIgnore @DiffWith(MoneyDiffer::class) val total: Money`
- **THEN** compilation fails
- **AND** the error names `total` and states that `@DiffWith` conflicts with `@DiffIgnore`

#### Scenario: A key on an ignored property is not a conflict

- **WHEN** an annotated data class declares `@DiffKey @DiffIgnore val id: String`
- **THEN** compilation succeeds
- **AND** a list of that type is still matched by `id`, and `id` itself is never compared

#### Scenario: A rejected comparison annotation blocks the build

- **WHEN** a module contains one valid `@Diffable` class and one unannotated class carrying `@DiffIgnore`
- **THEN** compilation fails
- **AND** the failure is attributable to the unannotated class, not to the valid one

## MODIFIED Requirements

### Requirement: Annotating an unsupported declaration is a compile error

`@Diffable` SHALL be honoured on data classes and on sealed classes and sealed interfaces. Applied
to any other declaration, it SHALL fail the compilation with an error that names the offending
declaration and states which declarations `@Diffable` accepts.

The error SHALL be reported at the location of the offending declaration, so an IDE and a build
log both point at the annotation's target rather than at generated code.

An unsupported declaration SHALL never be silently skipped: the build SHALL NOT succeed while
producing no differ for it.

When the offending declaration is an `object`, the error SHALL additionally state that an object in a
`@Diffable` sealed hierarchy needs no annotation of its own, since that is the one place an author is
likely to have reached for it.

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
- **THEN** compilation fails, reported at `Registry`
- **AND** the error names `Registry`, states which declarations `@Diffable` accepts, and states that an
  object in a `@Diffable` sealed hierarchy needs no annotation of its own

#### Scenario: Annotated enum class is rejected

- **WHEN** a module contains `@Diffable enum class Status { OPEN, CLOSED }`
- **THEN** compilation fails with the same error, reported at `Status`

#### Scenario: A rejected declaration blocks the build

- **WHEN** a module contains one valid `@Diffable` data class and one `@Diffable` non-sealed
  interface
- **THEN** compilation fails
- **AND** the failure is attributable to the interface, not to the valid data class

### Requirement: An annotated sealed type dispatches on the runtime subclass

`@Diffable` SHALL be honoured on a sealed class or sealed interface whose subclasses are all either
themselves `@Diffable` or declared as an `object`. The generated differ SHALL dispatch on the runtime
type of the two instances.

When both instances are the same subclass, the differ SHALL delegate to that subclass's differ and
report its changes unchanged. When both instances are the same `object` subclass, the differ SHALL
report nothing: a singleton has no state to compare.

When the two instances are different subclasses, the differ SHALL report a type change carrying the
two type names **and the two values**, and SHALL additionally compare the properties declared by
the sealed parent itself, reporting those as ordinary changes. It SHALL NOT descend into properties
that belong to only one of the two subclasses. This SHALL hold whether either or both subclasses are
objects.

A type change SHALL carry the values because a type name alone cannot be turned back into an
instance: without them a subclass change is a difference that can be reported but never replayed.

When the sealed parent declares no properties of its own, a subclass change SHALL produce the type
change alone.

An `object` subclass SHALL need no annotation of its own. There is nothing an annotation on it could
configure — no property to compare, ignore or key — and the sealed parent's differ knows the subclass
from the hierarchy.

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

#### Scenario: An object subclass is accepted without an annotation

- **WHEN** a module contains `@Diffable sealed interface Payment` with subclasses `@Diffable data class
  Card(...)` and `data object Unpaid : Payment`, and `Unpaid` carries no annotation
- **THEN** compilation succeeds
- **AND** a differ for `Payment` is available

#### Scenario: The same object on both sides reports nothing

- **WHEN** both instances are `Unpaid`
- **THEN** the result reports no changes

#### Scenario: A swap to an object reports the type change and the parent's properties

- **WHEN** the sealed parent declares `val amount: String`, the old instance is `Card` with amount
  `"10"`, and the new is `Unpaid` whose amount is `"0"`
- **THEN** the result reports a type change from `Card` to `Unpaid` carrying both instances
- **AND** a value change at `amount` from `"10"` to `"0"`

#### Scenario: A swap between two objects reports the type change alone when the parent declares nothing

- **WHEN** `@Diffable sealed interface State` has only object subclasses `Idle` and `Busy`, and the
  old instance is `Idle` and the new is `Busy`
- **THEN** the result reports exactly one change: a type change from `Idle` to `Busy`

#### Scenario: A hand-written sealed differ treats an object subtype exactly as a generated one

- **WHEN** a hand-written differ for `Payment` declares subtype differs for `Card` and `Transfer` and
  declares nothing for `Unpaid`
- **THEN** it reports the same changes as the generated `Payment` differ for both instances `Unpaid`,
  for a swap from `Card` to `Unpaid`, and for a swap from `Unpaid` to `Transfer`

### Requirement: A sealed type with an unannotated subclass is a compile error

`@Diffable` on a sealed type SHALL fail the compilation when any of its subclasses that is not an
`object` is not itself `@Diffable`, with an error naming the sealed type and the offending subclass.

Dispatch has to cover every subclass; a missing one would leave a branch that cannot be generated. An
`object` subclass is exempt because its branch names no differ: there is nothing in a singleton to
generate one for.

#### Scenario: An unannotated subclass is rejected

- **WHEN** `@Diffable sealed interface Payment` has subclasses `Card` annotated and `Transfer` not
- **THEN** compilation fails
- **AND** the error names both `Payment` and `Transfer`

#### Scenario: An unannotated object subclass is not rejected

- **WHEN** `@Diffable sealed interface Payment` has subclasses `Card` annotated and `data object Unpaid`
  unannotated
- **THEN** compilation succeeds

#### Scenario: An unannotated data class subclass is still rejected alongside an object

- **WHEN** `@Diffable sealed interface Payment` has subclasses `data object Unpaid` and an unannotated
  `data class Transfer`
- **THEN** compilation fails
- **AND** the error names `Payment` and `Transfer`, and does not name `Unpaid`
