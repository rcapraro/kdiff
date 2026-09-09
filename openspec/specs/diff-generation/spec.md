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

A `Diff` SHALL additionally be usable as an ordered collection of its changes in its own right: it
SHALL be iterable, SHALL report how many changes it holds, and SHALL report emptiness and
non-emptiness in the shape the Kotlin standard library uses for a collection, so that the stdlib
collection operators apply to it without the caller reaching for the underlying list first. The list
SHALL remain reachable for a caller that wants it.

A `Diff` SHALL be a value: comparing the same pair of instances twice SHALL produce equal results,
and calling `diff` SHALL NOT modify either instance. Two diffs holding equal changes in the same order
SHALL be equal.

A `Diff` SHALL be constructed from its changes and from nothing else. It SHALL NOT offer a way to derive
a modified copy of itself or to destructure it: a diff with different changes is a different diff, and
the constructor is how one is made. Its string form SHALL be its rendering, so a diff printed in a log
or an assertion failure reads as a diff.

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

#### Scenario: Two diffs over equal changes are equal

- **WHEN** two diffs are constructed from equal lists of changes
- **THEN** they are equal and have equal hash codes

#### Scenario: A diff offers no copy and no destructuring

- **WHEN** a caller attempts to derive a copy of a diff with different changes, or to destructure a diff
  into its components
- **THEN** the code does not compile
- **AND** constructing a new diff from the changes is the way to obtain one

#### Scenario: A diff's string form is its rendering

- **WHEN** a diff holding a value change at `city` from `"Paris"` to `"Nice"` is converted to a string
- **THEN** the result is the same text its rendering produces, one line naming `city` and both values

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

### Requirement: A generated differ is regenerated when a declaration it read changes

Deciding how to compare a property SHALL sometimes read a declaration other than the annotated class's
own: the type of a property is what says whether that type is compared as a single value, and an author
declares such a type in a file of their own — as an `enum class`, as an inline `value class`, or by
declaring it a value outright.

Every declaration consulted in that decision SHALL be recorded as one the generated differ was built
from. Editing any of them SHALL cause the differ to be generated again.

The observable consequence, which is the requirement: **a build that reuses previous output SHALL
report what a build from nothing reports.** A generated differ that a build from nothing refuses SHALL
NOT survive as output that compiles, and a comparison decision SHALL NOT outlive the declaration it was
taken from.

#### Scenario: Editing a value type regenerates the differs that compared it

- **WHEN** a module declares an inline `value class` in its own file, an annotated class compares a
  property of that type, and the module has been built once
- **AND** that class is then redeclared as an ordinary type that kdiff cannot compare, with the
  annotated class's own file left untouched
- **THEN** the next build reports the same error a build from nothing reports, naming the property and
  its type
- **AND** no differ comparing that property as a single value survives from the previous build

#### Scenario: Editing an enum regenerates the differs that compared it

- **WHEN** the same is done with an `enum class` redeclared as a type kdiff cannot compare
- **THEN** the next build reports the same error a build from nothing reports

#### Scenario: Editing a type declared to compare as one value regenerates its readers

- **WHEN** the same is done by removing the declaration that made a type compare as one value, so that
  the type is no longer comparable
- **THEN** the next build reports the same error a build from nothing reports

#### Scenario: Removing an exclusion from an overridden property regenerates the subclass's differ

- **WHEN** a `@Diffable` sealed parent excludes a property from comparison, a `@Diffable` subclass
  overrides it, and the module has been built once
- **AND** the exclusion is then removed from the parent, with the subclass's own file left untouched
- **THEN** the next build reports the property's changes, as a build from nothing does

#### Scenario: Adding a declaration to an overridden property regenerates the subclass's differ

- **WHEN** neither a sealed parent's property nor the subclass override of it carries any comparison
  declaration, and the module has been built once
- **AND** a value declaration is then added to the parent's property, with the subclass's own file left
  untouched
- **THEN** the next build compares that property as one value from the subclass too, as a build from
  nothing does

#### Scenario: A build reusing previous output agrees with a build from nothing

- **WHEN** any sequence of edits to the declarations a differ read is followed by a build reusing
  previous output
- **THEN** it succeeds exactly when a build from nothing succeeds, and reports the same diagnostics

### Requirement: A comparison reads each compared property once per instance

Comparing two instances SHALL read each compared property of each instance exactly once. A differ
SHALL NOT re-read a property to decide what to report about it, nor read a property it does not
compare.

Where a list is matched by key, the key SHALL be derived from each element exactly once per side.
Where a comparison descends into a nested value, that nested value SHALL be read once from each
instance and compared once, however many changes the descent reports.

This SHALL hold identically for a generated differ and for a hand-written one, since a property
reference is read the same way in both.

The requirement is about the reads a comparison performs, not about the changes it reports: it
constrains the work, and leaves the result exactly as the other requirements of this capability
define it. It exists because a property getter can be arbitrarily expensive — a computed value, a
lazily materialised collection — and a caller cannot see a redundant read in the diff.

#### Scenario: Each compared property is read once from each side

- **WHEN** a type whose property getters count their invocations is compared against another instance
  of itself
- **THEN** every compared property has been read exactly once on the `before` instance and exactly
  once on the `after` instance
- **AND** this holds whether the property differed or not

#### Scenario: An ignored property is never read

- **WHEN** a type carrying a property excluded from comparison is compared
- **THEN** that property's getter is never invoked

#### Scenario: A keyed list derives each element's key once per side

- **WHEN** a list matched by key is compared, and the key property counts its invocations
- **THEN** the key has been read once for each element of the `before` list and once for each element
  of the `after` list

#### Scenario: A nested property is read once however many changes it reports

- **WHEN** a nested annotated property is compared and the comparison reports several changes beneath
  it
- **THEN** the nested property itself was read once from each instance

#### Scenario: A hand-written differ reads no more than a generated one

- **WHEN** the same transition is compared once by a generated differ and once by a hand-written
  differ describing the same properties
- **THEN** both perform the same number of reads of each compared property

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

A key SHALL identify at most one element in each list. This is a precondition on the data, not on the
declaration: nothing in the source states that the key values will differ, so it cannot be checked
when the type is compiled.

When a list on either side holds two or more elements sharing a key, the comparison SHALL fail with
an error naming the list, the key property and the duplicated key value, and SHALL NOT report changes
for that comparison. It SHALL NOT match one of the elements and discard the others, and SHALL NOT
fall back to comparing the list by position.

Failing is the only correct outcome, because such a comparison has no representable result: a path
identifies a keyed element by its key value, so two elements sharing a key produce the same path and
no change reported there could say which element it describes.

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

#### Scenario: A repeated key in the old list is rejected

- **WHEN** the old list holds two elements both carrying key `A1`
- **THEN** the comparison fails with an error naming the list, the key property and the value `A1`
- **AND** no changes are reported for that comparison

#### Scenario: A repeated key in the new list is rejected

- **WHEN** the old list holds unique keys and the new list holds two elements both carrying key `A1`
- **THEN** the comparison fails with an error naming the list, the key property and the value `A1`

#### Scenario: A repeated key is rejected even when the two lists are otherwise equal

- **WHEN** both lists hold the same two elements sharing key `A1`, in the same order
- **THEN** the comparison fails rather than reporting no changes
- **AND** the failure does not depend on whether the duplicated elements differ from each other

#### Scenario: A repeated key is rejected the same way for a hand-written differ

- **WHEN** a list described by hand as a keyed list holds two elements sharing a key
- **THEN** the comparison fails exactly as it does for a list compared by a generated differ

#### Scenario: The same key in both lists is not a duplicate

- **WHEN** one element carries key `A1` in the old list and one element carries key `A1` in the new
- **THEN** the comparison succeeds, matching them as the same element

#### Scenario: A repeated key is not rejected when the list is compared by position

- **WHEN** a list whose element type declares no key holds two equal elements
- **THEN** the comparison succeeds, comparing the elements index by index

### Requirement: A list whose element type declares no key is compared by position

When a list's element type has no `@DiffKey` property, elements SHALL be matched by position. Before
matching, the comparison SHALL exclude the two lists' common trailing run, and SHALL compare only the
window that remains.

The common trailing run is the longest run of trailing positions, taken one from the end of each list,
at which **comparing the two elements would report nothing**. Where elements are compared as opaque
values that is equality; where they are compared by a differ it is that differ reporting no change.
Excluding a position is therefore equivalent to comparing it, by construction: the rule is stated in
terms of what a comparison would report, never in terms of element equality standing in for it.

This distinction is load-bearing rather than pedantic. A type's `equals` may be looser than the
properties its differ reads — an entity whose equality is by identity, or a type with a compared
property its `equals` does not cover — and excluding a position on the strength of equality alone would
drop a change that the comparison would otherwise have reported, silently and with nothing in the
result to say so.

The run SHALL NOT extend past the shorter list. Both windows therefore begin at index 0 in their own
list, and no leading run is excluded: the window's own comparison already reports nothing across
leading positions at which the two lists agree.

Within the window, a position present in both lists SHALL be compared by the rules for its element
type, at a path identifying the element by its index. A position present only in the new list SHALL be
reported as added, at its index in the **new** list; present only in the old list, as removed, at its
index in the **old** list. A move SHALL never be reported for such a list.

The choice of index is what makes the result applicable: applying inserts against the target's
positions and removes against the source's, so a change reported at the other side's index could not
be replayed.

Two properties follow, and a reader SHALL be able to rely on both. **Every element change is reported
at an index that identifies the same position in both lists** — the window begins at index 0 in each,
so an element change never names a position the element does not hold on both sides. **Additions and
removals are reported at indices at or after every element change's index, and a single comparison
reports additions or removals, never both** — the shorter list's leftover range is empty by
construction, and two lists of equal length leave both empty.

An insertion earlier in the list than a changed element is therefore not aligned: a changed element
leaves no agreeing tail beyond itself, so the window spans from the start of the list through that
element and the two edits report position by position. That is the stated ceiling of comparing without
a key, not a defect of the index rule.

**When the two lists have the same length, the comparison SHALL be index by index over the whole
list.** Excluding a common trailing run from two lists of equal length cannot change which positions
are paired or what is reported at them, so this is a guarantee rather than a coincidence, and the
library SHALL keep it one. It is the contract a fixed-arity list depends on — a weekly schedule of
seven slots, a coordinate triple, a three-place ranking — where the index *is* the element's identity
and matching elements by their value instead would report an addition and a removal for what is a
change of position, and say nothing at all about the element that moved.

#### Scenario: An element inserted at the head is reported as one addition

- **WHEN** the old list is `["a", "b", "c"]` and the new list is `["x", "a", "b", "c"]`
- **THEN** the result reports exactly one change: an addition of `"x"` at index 0
- **AND** no change is reported for `"a"`, `"b"` or `"c"`

#### Scenario: An element removed from the head is reported as one removal

- **WHEN** the old list is `["x", "a", "b", "c"]` and the new list is `["a", "b", "c"]`
- **THEN** the result reports exactly one change: a removal of `"x"` at index 0

#### Scenario: A run inserted in the middle is reported as additions at its new positions

- **WHEN** the old list is `["a", "b", "c"]` and the new list is `["a", "x", "y", "b", "c"]`
- **THEN** the result reports exactly two additions, of `"x"` at index 1 and `"y"` at index 2

#### Scenario: A run removed from the middle is reported as removals at its old positions

- **WHEN** the old list is `["a", "x", "y", "b", "c"]` and the new list is `["a", "b", "c"]`
- **THEN** the result reports exactly two removals, of `"x"` at index 1 and `"y"` at index 2

#### Scenario: A changed element is reported at its index

- **WHEN** the element at index 1 differs and the element type has no key
- **THEN** the result reports the change at a path identifying index 1

#### Scenario: Two lists of equal length are compared index by index

- **WHEN** the old list is `["alice", "bob", "carol"]` and the new list is `["bob", "alice", "carol"]`
- **THEN** the result reports a value change at index 0 from `"alice"` to `"bob"` and a value change at
  index 1 from `"bob"` to `"alice"`
- **AND** no addition and no removal is reported

#### Scenario: Equal-length comparison is unaffected by how many positions agree at the ends

- **WHEN** two lists of the same length hold equal elements at some leading and trailing positions and
  differ elsewhere
- **THEN** the changes reported are exactly those a position-by-position comparison of the whole list
  would report

#### Scenario: A longer new list reports additions at the trailing indices

- **WHEN** the old list has two elements and the new list has three, the first two being equal
- **THEN** the result reports one addition at index 2

#### Scenario: A shorter new list reports removals at the trailing indices

- **WHEN** the old list has three elements and the new list has two, the first two being equal
- **THEN** the result reports one removal at index 2

#### Scenario: An element added to a run of identical elements is reported at the run's first index

- **WHEN** the old list is `["a"]` and the new list is `["a", "a"]`
- **THEN** the result reports exactly one addition of `"a"`, at index 0
- **AND** the same holds in reverse, as exactly one removal at index 0

#### Scenario: Two lists agreeing at neither end compare as they did before

- **WHEN** the two lists agree at neither their first nor their last position
- **THEN** the changes reported are exactly those a position-by-position comparison would report

#### Scenario: A comparison reports additions or removals, never both

- **WHEN** any two unkeyed lists are compared
- **THEN** the result holds no addition when the new list is the shorter, and no removal when the old
  list is the shorter
- **AND** two lists of equal length produce neither

#### Scenario: An element change names a position both lists hold

- **WHEN** an unkeyed list comparison reports a change to an element
- **THEN** the index it is reported at identifies that position in the old list and in the new list
  alike
- **AND** every addition and every removal in the same result is reported at an index at or after it

#### Scenario: An insertion earlier than a changed element is not aligned

- **WHEN** the old list is `["a", "b", "c"]` and the new list is `["x", "a", "b", "c2"]`, where `"c2"`
  differs from `"c"`
- **THEN** the two lists agree at neither end, so the result is what a position-by-position comparison
  reports: a change at each of indices 0, 1 and 2, and an addition at index 3
- **AND** this is the stated ceiling of comparing without a key, not a defect of the index rule

#### Scenario: Nested elements after an insertion are not compared against the wrong element

- **WHEN** a list of a nested annotated element type gains one element at the head and is otherwise
  unchanged
- **THEN** the result reports one addition and no change inside any other element

#### Scenario: An unchanged list produces no changes

- **WHEN** both lists hold equal elements in the same order
- **THEN** the result reports no changes

#### Scenario: An element type whose equality is looser than its differ is still fully compared

- **WHEN** an unkeyed list is compared by a differ, and two elements at the same trailing position are
  equal to each other while that differ reports a change between them
- **THEN** that change is reported
- **AND** no position is excluded on the strength of element equality alone

#### Scenario: A property a type's equality does not cover is still compared inside a list

- **WHEN** an unkeyed list holds elements of an annotated type with a compared property that the
  type's own `equals` does not cover, and only that property differs at a trailing position
- **THEN** the change to that property is reported

#### Scenario: A hand-written positional list behaves exactly as a generated one

- **WHEN** a list described by hand with the positional list builder gains an element at its head
- **THEN** the changes reported are exactly those a generated differ reports for the same lists

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

### Requirement: A differ can be written by hand for a type that cannot be annotated

The library SHALL provide a way to build a `Differ<T>` in ordinary Kotlin, for a type whose source
cannot be annotated. A hand-written differ SHALL be indistinguishable from a generated one to any
code that consumes it, so the two compose when one type nests the other.

A property SHALL be able to name a hand-written differ with `@DiffWith`, and the generated code
SHALL delegate that property's comparison to it, prefixing its paths like any nested delegation.

A hand-written differ SHALL be able to describe every comparison shape an annotated class can
declare: a property compared by value, a nested property whether nullable or not, a list compared by
the key its element type declares, a list compared by position, a set compared as unordered
membership, a map compared by entry key, and a sealed type dispatched on its runtime subclass. Each
SHALL report the same changes, at the same paths, in the same order as the annotated declaration of
the same model — so a model can move between the two routes without any consumer noticing.

A property the hand-written differ does not name SHALL NOT be compared, which is what `@DiffIgnore`
states for an annotated class.

A hand-written differ that declares subtypes SHALL compare two instances of the same declared subtype
by delegating to that subtype's differ, and two instances of different subtypes by reporting one type
change at the root followed by the comparison of the properties it names itself — the same rule an
annotated sealed type follows.

Every property, key and element type SHALL be named by property reference rather than by text, and no
reflection library SHALL be required.

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

#### Scenario: A hand-written keyed list reports a move rather than a removal and an addition

- **WHEN** a hand-written differ describes `addresses` as a list keyed by `Address.id`
- **AND** two addresses swap positions between the two instances
- **THEN** the result reports a move for each, at `addresses[id=A1]` and `addresses[id=A2]`
- **AND** an element present only on one side is reported as added or removed at its key

#### Scenario: A hand-written positional list is compared index by index

- **WHEN** a hand-written differ describes a list with no key
- **AND** the element at index 1 differs
- **THEN** the change is reported under `[1]` of that property, and no move is ever reported

#### Scenario: A hand-written set is compared as membership

- **WHEN** a hand-written differ describes a `Set<String>` property
- **AND** one element is present only in the new instance
- **THEN** the result reports one addition under that property and nothing else

#### Scenario: A hand-written map is compared by entry key

- **WHEN** a hand-written differ describes a `Map<String, String>` property
- **AND** one entry's value differs
- **THEN** the result reports one value change at that property's entry key

#### Scenario: A hand-written nullable nested property reports a null side as a value change

- **WHEN** a hand-written differ describes a nested property that is null on one side only
- **THEN** the result reports one value change at the property, not an addition or a removal

#### Scenario: A hand-written sealed differ delegates when both sides are the same subtype

- **WHEN** a hand-written differ declares a subtype differ for each subclass of `Employment`
- **AND** both sides are `Employed` with different employers
- **THEN** the result reports the subtype differ's changes, at the paths that differ reports

#### Scenario: A hand-written sealed differ reports a subclass swap as a type change

- **WHEN** the two sides are different declared subtypes
- **THEN** the result reports one type change at the root carrying both type names and both values
- **AND** the properties the hand-written differ names itself are compared alongside it

#### Scenario: Two instances of one undeclared subtype report no type change

- **WHEN** a hand-written differ declares subtypes but not the one both sides hold
- **THEN** no type change is reported, because the type did not change
- **AND** the properties the differ names itself are still compared

#### Scenario: A hand-written description of an annotated model reports identical diffs

- **WHEN** the same model is described once with annotations and once by hand, property for property
- **AND** the same pair of instances is compared with each
- **THEN** both report equal changes, at equal paths, in the same order

### Requirement: A comparison annotation on an overridden property is honoured on the override

A property declared by a sealed parent and overridden by a subclass SHALL be compared as the parent
declared it, whichever branch of the dispatch compares it.

Where the comparison of a property is configured by an annotation on the declaration — declaring it a
single value, excluding it from comparison, or naming a differ for it — and the property overrides
another, the annotation SHALL be read from the overridden declaration when the override carries no such
annotation of its own. An annotation on the override SHALL take precedence over one on the declaration
it overrides, so a subclass can still say something different from its parent.

This SHALL hold for the two instances being the same subclass, which delegates to that subclass's
differ, exactly as it already holds for the two being different subclasses, which compares the parent's
properties directly. The two branches SHALL NOT disagree about how one property is compared.

Only annotations configuring how a property is compared are inherited this way. A key declaration is
read from a collection's element type and a tracking scope from the annotated class itself, so neither
has an overridden declaration to consult.

#### Scenario: A value declaration on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffAsValue val meta: Meta` with `Meta` being
  `@Diffable`, and `@Diffable data class Letter(override val meta: Meta, val body: String) : Doc`
- **THEN** two `Letter` instances whose `meta` differs inside report exactly one value change at
  `meta`, carrying both `Meta` instances
- **AND** no change is reported at `meta.title`

#### Scenario: The same subclass and a subclass swap agree about the parent's property

- **WHEN** the parent declares `@DiffAsValue val meta: Meta` and one subclass overrides it
- **THEN** two instances of that subclass report the property the same way a swap between two
  subclasses reports it: one value change at `meta`, never a change beneath it

#### Scenario: An exclusion on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffIgnore val revision: String` and a
  `@Diffable` subclass overrides it
- **THEN** two instances of that subclass whose `revision` differs report no change at `revision`

#### Scenario: A hand-written differ named on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffWith(WeightDiffer::class) val weight:
  Weight` and a `@Diffable` subclass overrides it
- **THEN** two instances of that subclass are compared for that property by the named differ, with its
  paths reported beneath `weight`

#### Scenario: An annotation on the override wins

- **WHEN** a sealed parent declares `@DiffAsValue val meta: Meta` and a subclass's override carries
  `@DiffIgnore`
- **THEN** the subclass reports no change at `meta`, the override's own declaration being what applies

#### Scenario: A declaration inherited from another module is honoured, not reported

- **WHEN** a `@Diffable` class overrides a property declared in a compiled dependency that carries a
  value declaration, and that dependency generated nothing of its own
- **THEN** the declaration is honoured
- **AND** no diagnostic names the overriding property for an annotation it does not carry
- **AND** a differ is generated for the overriding class

#### Scenario: An unusable differ inherited from another module is still reported

- **WHEN** a `@Diffable` class overrides a property declared in a compiled dependency whose `@DiffWith`
  names something that is not an object implementing the comparison contract
- **THEN** compilation fails with a diagnostic naming what is wrong
- **AND** the property is not silently left uncompared

#### Scenario: An unannotated parent property is unaffected

- **WHEN** a sealed parent declares `val meta: Meta` with no comparison annotation and a `@Diffable`
  subclass overrides it
- **THEN** the property is compared as its type dictates, reporting changes beneath `meta` as before

### Requirement: A differ or a patcher can be written as a single expression

The comparison and application contracts SHALL each be a functional interface, so that an
implementation with one comparison to make can be written as a lambda where it is used, without
declaring an object. Such an implementation SHALL be indistinguishable from any other to code that
consumes it.

The documentation SHALL state, wherever it states it for an object implementation, that a differ
written this way and calling another differ directly bypasses the descent bound, and SHALL point at the
nested-comparison helper that keeps it.

#### Scenario: A differ is written as a lambda

- **WHEN** a caller declares a differ for `Money` as a lambda over two instances that returns a `Diff`
- **THEN** it compiles
- **AND** it can be passed wherever a differ for `Money` is accepted, including as the differ of a
  nested property in a hand-written differ

#### Scenario: A patcher is written as a lambda

- **WHEN** a caller declares a patcher for `Money` as a lambda over an instance and a list of changes
  that returns a result
- **THEN** it compiles and is accepted wherever a patcher for `Money` is

### Requirement: A change identifies where it was found

Every change SHALL carry a path locating it relative to the root being diffed. A path SHALL be
composed of segments naming a property, an index into a positional collection, or a key into a
keyed collection.

A key segment SHALL retain the key itself, not a rendering of it, so that an element or entry it
identifies can be located and reconstructed. A key that is not a string SHALL survive in the path
as the value it is.

A key segment SHALL name what the value identifies its element by: the key property for an element of
a keyed list, and, for a map entry, a declared constant standing for "the entry's key". That constant
SHALL be reachable by name from the key segment type, so that a hand-written differ or patcher building
or reading a map path names it rather than repeating a literal, and so that the rendered form of a map
path can be traced to its origin.

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

#### Scenario: A map entry's key segment carries the declared constant

- **WHEN** a change is found at the entry keyed `"eur"` of an `amounts` map
- **THEN** the path's key segment names its property with the declared map-entry constant
- **AND** that constant is reachable from the key segment type by name
- **AND** the path renders as `amounts[key=eur]`, exactly as before the constant was declared

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

The same text SHALL be what the diff's own string conversion produces, so that a diff reaching a log, a
debugger or an assertion message without the caller asking for its rendering still reads as a diff.
Rendering SHALL remain reachable by name, for a caller who wants the text on purpose.

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

#### Scenario: The string conversion and the rendering agree

- **WHEN** any diff is both rendered and converted to a string
- **THEN** the two texts are identical

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

### Requirement: More than one key on a type is a compile error

A type SHALL declare at most one `@DiffKey` property. Two or more SHALL fail the compilation with
an error naming the type and the competing properties.

#### Scenario: Two keys on one type are rejected

- **WHEN** a data class annotates both `id` and `code` with `@DiffKey`
- **THEN** compilation fails
- **AND** the error names the type and both properties

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

### Requirement: A path can name the property a change sits under

`FieldPath` SHALL expose the name of the property the path begins with, or nothing when the path
begins with no property.

A path whose first segment is a property SHALL yield that property's name. The root path SHALL yield
nothing, because a change at the root belongs to no property. A path whose first segment identifies a
collection element rather than a property SHALL also yield nothing.

#### Scenario: A property path names its first property

- **WHEN** a change is found at `billing.city`
- **THEN** its path names `billing`

#### Scenario: A keyed element path names the collection property

- **WHEN** a change is found at `addresses[id=A2].street`
- **THEN** its path names `addresses`

#### Scenario: The root path names nothing

- **WHEN** a sealed type reports a type change at the root
- **THEN** its path names no property

### Requirement: A diff can be routed to handlers named by property reference

The library SHALL dispatch the changes of a diff to handlers, each naming the property it handles by
property reference rather than by a property name written as text. Routing SHALL read only the diff,
so it behaves identically whether the differ was generated from annotations or written by hand, and
SHALL require no reflection library.

A handler for a property SHALL run at most once per routing, and only when at least one change sits
under that property. It SHALL receive the changes under that property, in the order the diff reports
them.

A handler for a collection property SHALL be able to react separately to an element added and an
element removed, and, where the collection's elements carry a key, to an element moved and an element
changed in place. An added or removed element SHALL be supplied at the property's element type, and
the key identifying a keyed element at the key property's own type, with no cast written by the
caller. A handler for an element changed in place SHALL run once for each element that changed,
however many of that element's properties changed, and SHALL receive that element's key.

Reacting to a moved or in-place-changed element SHALL NOT be expressible for a collection whose
elements carry no key: without a key there is nothing to identify the element by.

A change that no handler names SHALL be reported to a fallback handler when one is declared, and SHALL
be ignored without failing when none is. A change at the root of the routed type belongs to no
property and SHALL be treated as unhandled. A change under a collection property that its element
routing has no shape for SHALL be treated as unhandled too, rather than dropped — declining a kind of
change is a decision, while being unable to express one is not. Every change SHALL reach at most one
handler, and the same property SHALL NOT be named twice in one routing.

#### Scenario: A property's handler runs once with the changes under it

- **WHEN** a diff of `Person` reports changes at `name.given` and `name.family`
- **AND** it is routed with a handler naming `Person::name`
- **THEN** that handler runs once, receiving both changes

#### Scenario: A property with no change does not run its handler

- **WHEN** a diff reports no change under `Person::nickname`
- **AND** it is routed with a handler naming `Person::nickname`
- **THEN** that handler does not run

#### Scenario: An added element is supplied at its element type

- **WHEN** a diff reports an element added to a `List<Address>` property
- **AND** it is routed with a collection handler for that property
- **THEN** the added-element handler receives an `Address`, with no cast written by the caller

#### Scenario: A removed element is supplied at its element type

- **WHEN** a diff reports an element removed from that property
- **THEN** the removed-element handler receives the removed `Address`

#### Scenario: A moved element supplies its key and both positions

- **WHEN** a diff reports a keyed element moved from index 0 to index 1
- **THEN** the move handler receives the key as an `AddressId` and the two positions

#### Scenario: An element changed in place runs its handler once

- **WHEN** a diff reports changes at `addresses[id=A1].city` and `addresses[id=A1].postalCode.value`
- **THEN** the changed-element handler runs once, receiving the key `A1`

#### Scenario: A change under an unnamed property reaches the fallback

- **WHEN** a diff reports a change under a property no handler names
- **AND** a fallback handler is declared
- **THEN** the fallback receives that change

#### Scenario: A change at the root reaches the fallback

- **WHEN** a diff reports a type change at the root of the routed type
- **THEN** that change is treated as unhandled and reaches the fallback

#### Scenario: An element change an unkeyed routing cannot express reaches the fallback

- **WHEN** a change is reported inside an element of a collection whose elements carry no key
- **AND** that property is routed by element
- **THEN** the change reaches the fallback rather than being dropped

#### Scenario: An unhandled change with no fallback is ignored

- **WHEN** a diff reports a change no handler names and no fallback is declared
- **THEN** routing completes without failing and without running any handler for it

#### Scenario: Routing behaves the same for a hand-written differ

- **WHEN** the same transition is compared once by a generated differ and once by a hand-written one
- **AND** both diffs are routed with the same handlers
- **THEN** the same handlers run, with the same values, in the same order

#### Scenario: Naming one property twice is rejected

- **WHEN** a routing names the same property in two handlers
- **THEN** it is rejected rather than running one handler and dropping the other

### Requirement: A routing can be nested under a property and dispatch at that property's type

The library SHALL let a routing declare a frame beneath one property, naming that property by
property reference, and dispatch the changes found beneath it against the property's own type. A frame
SHALL offer every route a routing offers — a handler for a property, a handler for the elements of a
collection property in both its keyed and unkeyed forms, and a fallback — and frames SHALL nest to
any depth the model has.

A frame SHALL dispatch exactly as routing the framed value's own diff would: the same handlers run,
receiving the same changes at paths rooted at the framed property's type, in the same order. Routing
a nested value through a frame and comparing that value directly and routing the result SHALL be
indistinguishable to the handlers.

A frame's property SHALL count as named in the enclosing routing. Changes beneath it SHALL NOT also
reach the enclosing fallback, and naming that property in both a frame and another handler of the
same routing SHALL be rejected, as naming any property twice already is.

A change that no handler inside a frame names SHALL be reported to the frame's own fallback when it
declares one. When it declares none, that change SHALL be returned to the enclosing routing, which
applies its own rules to it — its fallback when it declares one, and no failure when it does not —
so that one fallback at the outermost routing sees every change no handler at any depth named. A
change returned to an enclosing routing SHALL be reported at the path the enclosing routing received
it at, not at the path its frame dispatched it at.

A frame dispatches against the property's declared type, so the properties it can name are the ones
that type declares. Where the declared type is sealed, those are the properties the sealed type
declares itself; a subclass's own properties SHALL NOT be nameable in the frame. Where the declared
type is a collection, a path beneath the property begins with an element rather than a property, so
no handler in the frame can name it — naming the elements of a collection is what a collection handler
is for.

A change reported exactly at the framed property, rather than beneath it, has nothing left to dispatch
against the property's type — the value change a nullable nested value reports when it appears or
disappears is such a change. A frame SHALL treat it as unhandled rather than deliver it to a handler,
the way a change at the root of a routed type is already treated as unhandled.

#### Scenario: A frame dispatches on the framed property's own properties

- **WHEN** a diff of `Person` reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` containing a handler naming
  `CivilIdentity::civilStatus`
- **THEN** that handler runs once, receiving the change

#### Scenario: A frame is equivalent to routing the nested value's own diff

- **WHEN** the same transition is routed once through a frame naming `Person::identity` and once by
  comparing the two `CivilIdentity` values directly and routing that diff
- **THEN** the same handlers run, with the same changes at the same paths, in the same order

#### Scenario: Frames nest to a further level

- **WHEN** a diff of `Person` reports changes at `identity.name.given` and `identity.name.family`
- **AND** it is routed with a frame naming `Person::identity` containing a frame naming
  `CivilIdentity::name` containing a handler naming `FullName::given`
- **THEN** the handler naming `FullName::given` runs once, receiving the change at `given`

#### Scenario: A handler for a property with no change inside a frame does not run

- **WHEN** a diff reports a change at `identity.civilStatus` and none under `identity.name`
- **AND** the frame naming `Person::identity` declares handlers for both
- **THEN** only the handler naming `CivilIdentity::civilStatus` runs

#### Scenario: A keyed collection inside a frame supplies its element key

- **WHEN** a diff of `Person` reports changes at `fiscal.crs[country=FR].tin` and
  `fiscal.crs[country=FR].certifiedOn`
- **AND** it is routed with a frame naming `Person::fiscal` containing a keyed element handler for
  `FiscalProfile::crs` keyed by `CrsRecord::country`
- **THEN** the changed-element handler runs once, receiving the key `FR` at the key property's own
  type

#### Scenario: A frame's changes do not reach the enclosing fallback

- **WHEN** a diff reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` that handles it, and an enclosing
  fallback
- **THEN** the enclosing fallback does not receive that change

#### Scenario: A change no handler inside a frame names reaches the enclosing fallback

- **WHEN** a diff reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` that declares no handler for
  `civilStatus` and no fallback of its own, and an enclosing fallback
- **THEN** the enclosing fallback receives that change, reported at `identity.civilStatus`

#### Scenario: A frame's own fallback consumes what its handlers did not name

- **WHEN** that same diff is routed with a frame that declares its own fallback
- **THEN** the frame's fallback receives the change and the enclosing fallback does not

#### Scenario: A change at the framed property itself is unhandled

- **WHEN** a diff reports a value change at `fiscal` because a nullable nested value became null
- **AND** it is routed with a frame naming `Person::fiscal` and an enclosing fallback
- **THEN** no handler inside the frame runs for it
- **AND** the enclosing fallback receives that change, reported at `fiscal`

#### Scenario: An unhandled change inside a frame with no fallback anywhere is ignored

- **WHEN** a diff reports a change inside a frame that no handler names, and neither the frame nor
  the enclosing routing declares a fallback
- **THEN** routing completes without failing and without running any handler for it

#### Scenario: A frame over a sealed property names the properties the sealed type declares

- **WHEN** a diff reports a change beneath `Person::employment`, whose declared type is sealed and
  declares `since` itself
- **AND** it is routed with a frame naming `Person::employment` containing a handler naming
  `Employment::since`
- **THEN** that handler runs once, receiving the change at `since`

#### Scenario: A subclass swap under a frame is unhandled

- **WHEN** a diff reports a type change at `employment` because the value became a different subclass
- **AND** it is routed with a frame naming `Person::employment` and an enclosing fallback
- **THEN** no handler inside the frame runs for it
- **AND** the enclosing fallback receives that change, reported at `employment`

#### Scenario: A frame over a collection property leaves every change unhandled

- **WHEN** a diff reports elements removed from `Person::tags` at `tags[0]` and `tags[1]`
- **AND** it is routed with a frame naming `Person::tags` and an enclosing fallback
- **THEN** no handler inside the frame runs
- **AND** the enclosing fallback receives both changes, reported at `tags[0]` and `tags[1]`

#### Scenario: Naming a property in both a frame and another handler is rejected

- **WHEN** a routing declares a frame naming `Person::identity` and also a handler naming
  `Person::identity`
- **THEN** it is rejected rather than running one and dropping the other

#### Scenario: A nested routing behaves the same for a hand-written differ

- **WHEN** the same transition is compared once by a generated differ and once by a hand-written one
- **AND** both diffs are routed with the same frames and handlers
- **THEN** the same handlers run, with the same values, in the same order

### Requirement: A routing's fallback receives the changes the diff reported

A routing SHALL hand its fallback the change instances the diff holds, not copies of them, and SHALL
hand them over in the order the diff reports them. A caller that routes a diff and audits what no
handler named SHALL therefore be able to match each such change back to the diff it came from, and to
the position it held there.

This SHALL hold however a change came to be unhandled: because no handler names its property, because
it sits at the root of the routed type, or because a nested frame declined it and handed it back to the
routing that framed it.

The existing requirement *A routing can be nested under a property and dispatch at that property's
type* already states that a change handed back by a frame is reported at the path the enclosing routing
received it at. This adds that it is the same change, not an equal reconstruction of one — which is
what lets a frame hand back what it was given rather than something merely equal to it.

#### Scenario: A change under an unnamed property reaches the fallback as the reported instance

- **WHEN** a diff reports a change under a property no handler names
- **AND** a fallback handler is declared
- **THEN** the fallback receives the very change instance the diff holds, not an equal copy of it

#### Scenario: A change a nested frame declined reaches the outer fallback as the reported instance

- **WHEN** a routing frame beneath a property declines a change its own handlers did not name, and the
  frame declares no fallback of its own
- **THEN** the enclosing routing's fallback receives the change instance the diff holds
- **AND** it is reported at the path the enclosing routing was given it at

#### Scenario: Several unhandled changes reach the fallback in the diff's order

- **WHEN** a diff reports several changes that no handler names
- **THEN** the fallback receives them in the order the diff reports them, each the instance the diff
  holds

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
