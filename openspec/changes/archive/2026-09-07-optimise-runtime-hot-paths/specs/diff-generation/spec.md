## ADDED Requirements

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
