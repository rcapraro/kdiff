## Purpose

Defines what applying a diff does: reconstructing a target instance from a source instance and the
changes between them, what each kind of change does when applied, and what happens to a change the
library cannot apply.

## Requirements

### Requirement: An annotated type can have a diff applied to it

Annotating a data class or sealed type with `@Diffable` SHALL make it possible to apply changes to
an instance of that type, producing a new instance, without the author writing anything else.

Applying SHALL be exposed on the same generated declaration that compares the type, so a single
import provides both directions. The declaration's existing name, package and comparison signature
SHALL NOT change.

Applying SHALL NOT modify the instance it is given. The source instance SHALL be unchanged
afterwards, and applying the same changes twice to the same source SHALL produce equal results.

#### Scenario: An annotated data class can be patched

- **WHEN** a module contains `@Diffable data class Person(val id: String, val name: String)`
- **THEN** applying changes to a `Person` produces a `Person`
- **AND** it is reached through the same generated declaration that compares `Person`

#### Scenario: Applying does not modify the source

- **WHEN** changes are applied to an instance
- **THEN** that instance is unchanged afterwards

#### Scenario: Applying is repeatable

- **WHEN** the same changes are applied twice to the same source instance
- **THEN** both applications produce equal results

#### Scenario: Applying no changes returns an equal instance

- **WHEN** an empty list of changes is applied to an instance
- **THEN** the result equals that instance
- **AND** no failures are reported

### Requirement: Applying a diff to its source reproduces the target

For any two instances of a type the library supports, applying the diff between them to the first
SHALL produce an instance equal to the second, and SHALL report no failures.

This SHALL hold for every property shape the library compares: values, enums, nullable properties,
nested annotated types, lists matched by key, lists compared by position, sets, maps, and sealed
types including a subclass change.

A property excluded from comparison SHALL keep the source instance's value, since no change was
ever reported for it. The round-trip is therefore over the compared properties, which is what a
diff describes.

#### Scenario: Round-trip over value and enum properties

- **WHEN** two instances differ in a string property and an enum property
- **THEN** applying their diff to the first produces an instance equal to the second

#### Scenario: Round-trip over a nullable property in both directions

- **WHEN** a nullable property goes from `null` to a value in one pair, and from a value to `null`
  in another
- **THEN** applying each diff reproduces its target

#### Scenario: Round-trip over a nested annotated type

- **WHEN** two instances differ only inside a nested annotated property
- **THEN** applying their diff reproduces the target, with the nested value rebuilt

#### Scenario: Round-trip over a keyed list with a modification, an addition, a removal and a move

- **WHEN** two instances differ by an element modified, one added, one removed and one reordered
- **THEN** applying their diff reproduces the target list exactly, including element order

#### Scenario: Round-trip over an unkeyed list that changed length

- **WHEN** two instances differ by a changed element and a different list length
- **THEN** applying their diff reproduces the target list

#### Scenario: Round-trip over a set

- **WHEN** two instances differ by set members added and removed
- **THEN** applying their diff reproduces the target set

#### Scenario: Round-trip over a map with a changed, an added and a removed entry

- **WHEN** two instances differ by an entry whose value changed, an entry added and an entry removed
- **THEN** applying their diff reproduces the target map, including entries whose keys are not
  strings

#### Scenario: Round-trip over a sealed subclass change

- **WHEN** two instances hold different subclasses of the same sealed type
- **THEN** applying their diff reproduces the target, holding the target's subclass

#### Scenario: Round-trip leaves an ignored property at the source value

- **WHEN** two instances differ only in a property excluded from comparison
- **THEN** applying their diff produces an instance equal to the source
- **AND** no failures are reported

### Requirement: Each kind of change has a defined effect when applied

Applying a value change SHALL set the property at its path to the change's new value. Applying an
addition SHALL insert the element or entry its path identifies. Applying a removal SHALL delete the
element or entry its path identifies. Applying a move SHALL place the identified element at its new
index. Applying a type change SHALL replace the value at its path with the change's new value.

A change SHALL be applied at the path it carries, however deeply nested, so a change reported
beneath several levels of nesting is applied at that same location.

#### Scenario: A value change sets the property

- **WHEN** a value change at `name` from `"Ada"` to `"Grace"` is applied
- **THEN** the result's `name` is `"Grace"`

#### Scenario: A nested value change is applied at its path

- **WHEN** a value change at `company.address.city` is applied
- **THEN** only that nested property differs in the result

#### Scenario: An addition inserts an element

- **WHEN** an addition at a keyed list path is applied
- **THEN** the result's list contains that element

#### Scenario: A removal deletes an element

- **WHEN** a removal at a keyed list path is applied
- **THEN** the result's list does not contain that element

#### Scenario: A type change replaces the value

- **WHEN** a type change from `Card` to `Transfer` is applied
- **THEN** the value at that path is the change's `Transfer`

### Requirement: A change that cannot be applied is reported, never silently dropped

Applying SHALL return the patched instance together with every change it could not apply, each
carrying the change itself and the reason it failed.

The result SHALL make a clean application distinguishable from a partial one, so a caller that
requires strictness can detect any failure. Changes that can be applied SHALL still be applied when
others fail; a single unapplicable change SHALL NOT discard the rest.

A change SHALL be reported as a failure when its path names no property the type compares, when it
falls beneath a property the library can compare but not reconstruct, or when it targets a property
that is not a constructor parameter.

#### Scenario: An unknown path is reported

- **WHEN** a change whose path names no compared property is applied
- **THEN** the result reports that change as a failure with a reason
- **AND** the returned instance is otherwise patched

#### Scenario: Other changes still apply when one fails

- **WHEN** a list of changes contains one unapplicable change and several applicable ones
- **THEN** the applicable ones are applied
- **AND** only the unapplicable one is reported

#### Scenario: A clean application is distinguishable from a partial one

- **WHEN** every change applies successfully
- **THEN** the result reports no failures

#### Scenario: A property that is not a constructor parameter is reported

- **WHEN** a change targets a property declared in the class body rather than the constructor
- **THEN** it is reported as a failure naming that property
- **AND** the reason states that only constructor properties can be reconstructed

### Requirement: A property compared by a hand-written differ is applied only when that differ can also patch

A property annotated `@DiffWith` SHALL have its changes applied by the object it names when that
object can patch the property's type. When the named object can only compare, changes beneath that
property SHALL be reported as failures.

The library SHALL NOT attempt to reconstruct a type it was only taught to compare: doing so would
require constructing a value whose shape it does not know.

#### Scenario: A hand-written patcher is used

- **WHEN** a `@DiffWith` property names an object that can both compare and patch its type
- **AND** the two instances differ inside that property
- **THEN** applying their diff reproduces the target
- **AND** no failures are reported

#### Scenario: A compare-only differ makes the property unpatchable

- **WHEN** a `@DiffWith` property names an object that can only compare its type
- **AND** a change is reported beneath that property
- **THEN** applying reports that change as a failure
- **AND** the reason identifies the property and that its differ cannot patch

#### Scenario: The rest of the instance still applies

- **WHEN** an instance has both a compare-only `@DiffWith` property and ordinary properties, and
  both changed
- **THEN** the ordinary properties are patched
- **AND** only the change beneath the `@DiffWith` property is reported
