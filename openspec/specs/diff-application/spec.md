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

A property that no change addresses SHALL be carried through rather than rebuilt, whatever its shape.
Applying SHALL NOT reconstruct a value, a nested value, a list, a set or a map that nothing in the
change list is addressed to; the source instance's own value SHALL appear in the result. So a
property untouched by a diff is the same instance in the result as in the source, exactly as it is
for a property the type does not compare at all. Equality of the result is unaffected either way —
this states that the work is not done, not that the outcome differs.

Carrying a property through SHALL skip its reconstruction and SHALL NOT skip a precondition its shape
requires. Where reconstruction reaches a property whose source value cannot be interpreted at all,
that rejection SHALL happen whether or not a change addresses the property: what an unaddressed
property saves is the rebuild, never the check. A keyed list holding a repeated key is the only such
shape the library has.

For a list matched by key, the round-trip SHALL hold on the same precondition the comparison carries:
a key identifies at most one element. When the instance being patched holds a list with two or more
elements sharing a key, applying SHALL fail with an error naming the list, the key property and the
duplicated key value. It SHALL NOT rebuild the list by retaining one of those elements in place of the
others, and SHALL NOT drop an element.

This SHALL hold wherever such a list is rebuilt, whatever is being applied to it and an empty change
list included: the list is uninterpretable on its own terms, independently of the changes addressed to
it. It is the precondition the paragraph above describes — reconstruction that reaches the list
examines it, and an examined list with a repeated key is rejected before anything is carried through.

A list that is never reached is never rejected. Applying SHALL skip reconstruction of a nested value
that no change addresses, so a list holding a repeated key beneath an untouched property is carried
through unexamined rather than rebuilt. Nothing is lost by this, because nothing is rebuilt.

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

#### Scenario: An unaddressed set property is carried through, not rebuilt

- **WHEN** a diff reports one change, at a string property, and the type also compares a `Set`
  property that no change addresses
- **THEN** applying produces an instance whose set property is the same instance the source held
- **AND** the result is equal to the target

#### Scenario: An unaddressed map and positional list are carried through, not rebuilt

- **WHEN** a diff addresses neither a `Map` property nor a positional `List` property the type
  compares
- **THEN** applying produces an instance holding the source's own map and list instances
- **AND** no failures are reported

#### Scenario: An unaddressed value property is carried through

- **WHEN** a diff addresses none of the value properties of a type
- **THEN** applying produces an instance whose value properties are the source's, and reports no
  failures

#### Scenario: Applying to a keyed list with a repeated key is rejected

- **WHEN** the instance being patched holds a keyed list with two elements sharing key `A1`
- **THEN** applying fails with an error naming the list, the key property and the value `A1`
- **AND** the result does not hold one of those elements twice

#### Scenario: An empty change list is rejected against a source with a repeated key

- **WHEN** the instance being patched holds a keyed list with two elements sharing key `A1` and the
  change list is empty
- **THEN** applying fails rather than returning an instance equal to the source

#### Scenario: A keyed list with a repeated key is rejected even when no change addresses it

- **WHEN** a diff addresses only a value property, and the same instance also holds a keyed list with
  two elements sharing key `A1` directly beneath the type being patched
- **THEN** applying fails, naming the list, the key property and the value `A1`
- **AND** carrying an unaddressed property through does not exempt a keyed list from that check

#### Scenario: A repeated key is rejected the same way for a hand-written patcher

- **WHEN** a hand-written patcher rebuilds a keyed list holding two elements sharing a key
- **THEN** applying fails exactly as it does for a list rebuilt by a generated patcher

#### Scenario: A repeated key beneath an untouched nested property is not reached

- **WHEN** a keyed list holding two elements sharing a key sits inside a nested value that no change
  addresses
- **THEN** applying succeeds, carrying that nested value through unchanged
- **AND** the list is neither rebuilt nor rejected, because reconstruction never descends into it

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

A reason SHALL be inspectable, not merely readable. The reasons SHALL form a closed, declared set of
cases a caller can handle exhaustively, and each case SHALL carry the facts it knows as properties —
the property name, the key, the index, whichever the case is about — so that a caller can branch on
why a change did not apply, and can act on which property or element it concerned, without matching
prose. A failure SHALL still render as one human-readable line naming its path and its reason, so
existing logging output is unchanged in substance.

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

#### Scenario: A reason is handled by case rather than by message

- **WHEN** a caller matches exhaustively on the reason of a reported failure
- **THEN** every reason the library can report is covered without a catch-all branch
- **AND** the matched case exposes the property, key or index its reason concerns

#### Scenario: A failure still renders as a readable line

- **WHEN** a reported failure is rendered as text
- **THEN** the line names the change's path and states the reason in prose

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
- **AND** the reason's case identifies it as a non-constructor property and carries the property name

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

### Requirement: A caller can require a patch to be all-or-nothing

Applying SHALL keep returning a partial result with its failures, because that is what lets a caller
salvage what applied. A caller that instead wants a patch to succeed completely or not at all SHALL be
able to say so at the call site, on the result, without inspecting the failures itself.

Asking for the value that way SHALL return the patched instance when every change applied, and SHALL
raise a declared exception of the library's own otherwise. That exception SHALL carry every failure, so
a caller that catches it is no less informed than one that inspected the result.

#### Scenario: A clean result yields its value

- **WHEN** a caller requires the value of a result in which every change applied
- **THEN** the patched instance is returned

#### Scenario: A partial result raises instead

- **WHEN** a caller requires the value of a result reporting at least one failure
- **THEN** the library raises its declared patch-failed exception
- **AND** the exception carries every reported failure
- **AND** the exception's message states how many changes failed

#### Scenario: Requiring the value does not change what applying does

- **WHEN** the same changes are applied twice, once read as a partial result and once required
  outright, to a source for which they all apply
- **THEN** both produce equal instances

### Requirement: Applying refuses the inputs comparison refuses

Where comparison refuses an input rather than describing it, applying SHALL refuse the same input the
same way, raising the same declared exception type — so a caller need not learn two vocabularies for
one malformed model.

This SHALL cover a keyed list holding two or more elements that share a key, and a descent that
exceeds the bound. In both cases applying SHALL raise rather than return a result: the source cannot
be rebuilt at all, so there is no partial value to hand back.

Applying descends along the paths of the changes it is given and stops wherever the source runs out,
so neither a cyclic source nor a change list deeper than the bound is unbounded on its own. Together
they are — the source never ends and the changes keep asking for more — and that is the case the
bound refuses.

#### Scenario: A duplicate key is refused when applying

- **WHEN** a diff is applied to a source whose keyed list holds two elements sharing a key
- **THEN** the declared duplicate-key exception is raised, carrying the list property, the key
  property and the duplicated key value
- **AND** no result is returned

#### Scenario: A cyclic source is refused once the changes reach into it

- **WHEN** a diff whose change paths run deeper than the bound is applied to a source whose graph
  contains a cycle
- **THEN** the declared cyclic-structure exception is raised, naming the path at which the descent
  stopped, and reporting that an instance was re-entered
- **AND** no `StackOverflowError` is raised

#### Scenario: A deep change list stops where the source ends

- **WHEN** a change whose path runs deeper than the bound is applied to a finite source
- **THEN** no exception is raised
- **AND** the change is reported as a failure for having nothing beneath a null property

#### Scenario: Applying is unchanged for a source within the bound

- **WHEN** any source that stays within the descent bound is patched before and after this change
- **THEN** the same instance is produced and the same failures are reported
