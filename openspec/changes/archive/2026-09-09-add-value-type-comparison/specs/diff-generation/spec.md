## ADDED Requirements

### Requirement: A type or a property can be declared to compare as one value

`@DiffAsValue` SHALL declare that what it annotates is compared by equality, as a single value, and
reported as one value change carrying both sides.

On a class, `@DiffAsValue` SHALL make that type a value wherever a `@Diffable` class reaches it: as a
property, as a list element, as a map value. It SHALL be honoured on a type the annotating module
declares, exactly as `@Diffable` is.

On a property, `@DiffAsValue` SHALL make that property alone compare as one value whatever its type —
a `@Diffable` type, a collection, or anything else — reporting one value change at the property and
nothing beneath it. It is the annotation counterpart of the hand-written builder's `field`, and the two
SHALL report identical changes for the same property.

`@DiffAsValue` SHALL NOT be accepted where it could change nothing or contradict another declaration.
Each such use SHALL fail the compilation with an error at the annotated declaration naming it and
stating why:

- on a class that is also `@Diffable`, as a conflict between comparing as a whole and property by
  property;
- on a class, an enum or a property whose type is already compared as a value, as having no effect;
- on a property that also carries `@DiffWith`, as a conflict, since a property is compared one way;
- on a property that also carries `@DiffIgnore`, as a conflict, since an ignored property is never
  compared;
- on a property of a class that is not `@Diffable`, as requiring `@Diffable`, since no differ is
  generated to honour it.

#### Scenario: A type declared as a value is compared by equality as a property

- **WHEN** a module contains `@DiffAsValue data class Coordinates(val lat: Double, val lon: Double)` and
  `@Diffable data class Place(val name: String, val at: Coordinates)`
- **THEN** compilation succeeds
- **AND** two places whose coordinates differ in `lon` report exactly one value change at `at` carrying
  both `Coordinates` instances
- **AND** no change is reported at `at.lon`

#### Scenario: A type declared as a value is a value inside collections

- **WHEN** a `@Diffable` class declares `val route: List<Coordinates>` and `val byName: Map<String,
  Coordinates>` with `Coordinates` declared `@DiffAsValue`
- **THEN** compilation succeeds
- **AND** a changed element reports one value change at `route[1]`, and a changed entry one value
  change at `byName[key=home]`

#### Scenario: A property declared as a value compares a nested type as a whole

- **WHEN** an annotated data class declares `@DiffAsValue val billing: Address` and `Address` is
  `@Diffable`
- **THEN** two instances whose billing addresses differ in `city` report exactly one value change at
  `billing` carrying both addresses
- **AND** no change is reported at `billing.city`

#### Scenario: A property declared as a value compares a collection as a whole

- **WHEN** an annotated data class declares `@DiffAsValue val tags: List<String>`
- **THEN** `["a", "b"]` against `["a", "c"]` reports exactly one value change at `tags` from
  `["a", "b"]` to `["a", "c"]`
- **AND** no change is reported at `tags[1]`

#### Scenario: A property declared as a value and a hand-written field agree

- **WHEN** the same model is described once with `@DiffAsValue val billing: Address` and once by hand
  with `field(Order::billing)`
- **THEN** both report equal changes, at equal paths, in the same order, for every pair of instances

#### Scenario: A value declaration on a diffable class is rejected

- **WHEN** a module contains `@Diffable @DiffAsValue data class Money(val amount: String)`
- **THEN** compilation fails
- **AND** the error names `Money` and states that `@DiffAsValue` conflicts with `@Diffable`
- **AND** the error is reported at the declaration of `Money`

#### Scenario: A value declaration on something already a value is rejected

- **WHEN** a module contains `@DiffAsValue enum class Status { OPEN, CLOSED }`, or `@DiffAsValue
  @JvmInline value class Email(val value: String)`, or an annotated data class declaring
  `@DiffAsValue val total: BigDecimal`
- **THEN** compilation fails for each
- **AND** each error names the declaration and states that it is already compared as a value

#### Scenario: A value declaration beside a hand-written differ is rejected

- **WHEN** an annotated data class declares `@DiffAsValue @DiffWith(MoneyDiffer::class) val total: Money`
- **THEN** compilation fails
- **AND** the error names `total` and states that `@DiffAsValue` conflicts with `@DiffWith`

#### Scenario: A value declaration on an ignored property is rejected

- **WHEN** an annotated data class declares `@DiffAsValue @DiffIgnore val note: Address`
- **THEN** compilation fails
- **AND** the error names `note` and states that `@DiffAsValue` conflicts with `@DiffIgnore`

#### Scenario: A value declaration on a property of an unannotated class is rejected

- **WHEN** a module contains `data class Order(@DiffAsValue val billing: Address)` with no `@Diffable`
- **THEN** compilation fails
- **AND** the error names `billing` and states that `@DiffAsValue` requires `@Diffable` on `Order`

#### Scenario: A key on a type declared as a value is rejected

- **WHEN** a module contains `@DiffAsValue data class Tag(@DiffKey val id: String, val label: String)`
- **THEN** compilation fails
- **AND** the error names `id` and states that `@DiffKey` requires `@Diffable` on `Tag`

## MODIFIED Requirements

### Requirement: Value properties are compared by equality

A generated differ SHALL compare each property whose type is a value type using equality, and SHALL
report a difference as a value change carrying the old and the new value at that property's path.

A value type is any of:

- a primitive, an unsigned integer type, a `Char`, a `String`, or an enum;
- an inline `value class`;
- one of a documented set of immutable, value-equal types from the JDK and the Kotlin standard library:
  `BigDecimal`, `BigInteger`, `UUID`, `Currency`, `Locale`, `URI`, the `java.time` value types
  (`Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
  `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`),
  `kotlin.time.Instant` and `kotlin.uuid.Uuid`;
- a type declared `@DiffAsValue`.

The criterion for the documented set SHALL be stated where the set is listed: immutable, equal by value,
from the JDK or the Kotlin standard library. A type that is mutable or that names something rather than
being a value SHALL NOT be on it.

A value type SHALL be a value in every position: a property of that type, a list element of that type
and a map value of that type are all compared by equality. Equality SHALL mean the type's own `equals`;
the library SHALL NOT substitute another comparison for any type, so a `BigDecimal` that differs only in
scale reports a change, and a numeric comparison remains a hand-written differ's business.

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

#### Scenario: A standard-library value type compiles and is compared by equality

- **WHEN** an annotated data class declares `val total: BigDecimal`, `val placedAt: Instant` and
  `val id: UUID`
- **THEN** compilation succeeds without any annotation on those properties
- **AND** a changed `placedAt` reports one value change at `placedAt` carrying both instants

#### Scenario: A standard-library value type is a value inside collections

- **WHEN** an annotated data class declares `val due: List<LocalDate>` and `val rates: Map<String,
  BigDecimal>`
- **THEN** compilation succeeds
- **AND** a changed element reports one value change at `due[0]`, and a changed entry one value change at
  `rates[key=eur]`

#### Scenario: A big decimal differing only in scale reports a change

- **WHEN** `total: BigDecimal` is `10` before and `10.00` after
- **THEN** the result reports one value change at `total`

#### Scenario: An inline value class compiles and is compared by equality

- **WHEN** a module contains `@JvmInline value class Email(val value: String)` and an annotated data
  class declares `val email: Email`
- **THEN** compilation succeeds without any annotation on `Email` or on the property
- **AND** two instances whose emails differ report one value change at `email` carrying both `Email`
  values

#### Scenario: A mutable JDK type is not a value

- **WHEN** an annotated data class declares `val seen: java.util.Date` with no other annotation
- **THEN** compilation fails with the error for a property kdiff cannot compare

#### Scenario: A hand-written field agrees with a generated value comparison

- **WHEN** the same `BigDecimal` property is compared once by a generated differ and once by a
  hand-written differ naming it with `field`
- **THEN** both report equal changes

### Requirement: A property kdiff cannot compare is a compile error

A property whose type is neither a value type, an annotated type, a supported collection, nor
pointed at a hand-written differ SHALL fail the compilation with an error naming the property and
its type, and naming the three ways out: annotating the type `@Diffable`, marking the property
`@DiffAsValue` to compare it by equality, or pointing the property at a hand-written differ with
`@DiffWith`. For a collection whose element or value type cannot be compared, the error SHALL name
the element type and the two declarations that can be placed on it, `@Diffable` or `@DiffAsValue`,
and the hand-written differ.

Such a property SHALL NOT be silently compared by equality: comparing a rich type as an opaque
value produces a diff that is technically correct and useless, and the author SHALL say so with
`@DiffAsValue` if that is what they want.

#### Scenario: An unsupported property type is rejected

- **WHEN** an annotated data class has a property of a third-party type with no `@DiffWith`
- **THEN** compilation fails
- **AND** the error names the property and its type, and mentions `@Diffable`, `@DiffAsValue` and
  `@DiffWith`
- **AND** the error is reported at the property

#### Scenario: An unsupported element type is rejected

- **WHEN** an annotated data class has a `List` of a third-party type with no `@DiffWith`
- **THEN** compilation fails
- **AND** the error names the property and the element type, and mentions `@Diffable`, `@DiffAsValue`
  and `@DiffWith`

#### Scenario: The same property with a hand-written differ compiles

- **WHEN** that property is annotated with `@DiffWith` naming a differ for its type
- **THEN** compilation succeeds

#### Scenario: The same property declared as a value compiles

- **WHEN** that property is annotated with `@DiffAsValue`
- **THEN** compilation succeeds
- **AND** the property is compared by equality
