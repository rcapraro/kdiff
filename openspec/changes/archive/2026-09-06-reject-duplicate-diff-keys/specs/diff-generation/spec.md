## MODIFIED Requirements

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
