## MODIFIED Requirements

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
