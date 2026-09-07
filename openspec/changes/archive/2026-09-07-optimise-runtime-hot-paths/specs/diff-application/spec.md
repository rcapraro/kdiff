## MODIFIED Requirements

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
