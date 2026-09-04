## ADDED Requirements

### Requirement: A hand-written scope can name the properties it excludes

A tracking scope written by hand SHALL be able to name the properties it excludes instead of the
properties it tracks. A scope naming only exclusions SHALL track every compared property except those
named, at the scope's depth — the hand-written counterpart of excluding a property from an annotated
class's declared scope.

An excluded property SHALL never be reported, at any depth: neither the property itself nor anything
nested beneath it.

Naming exclusions and tracked properties in one scope SHALL be rejected, because the two say opposite
things about every property named in neither. Excluding a property the differ does not compare SHALL
be accepted and SHALL exclude nothing, since such a property can never appear in a change.

#### Scenario: Excluding one property tracks every other

- **WHEN** a scope for `Person` excludes `lastSeenAt` and names no tracked property
- **AND** a transition changes `name.family` and `lastSeenAt`
- **THEN** the change at `name.family` is reported and the change at `lastSeenAt` is not

#### Scenario: An excluded property's nested change is not reported either

- **WHEN** a scope excludes a property whose type nests further
- **AND** a transition changes something two steps beneath it
- **THEN** nothing is reported for it

#### Scenario: Mixing an exclusion with a tracked property is rejected

- **WHEN** a scope both names a tracked property and excludes another
- **THEN** it is rejected rather than resolved by a precedence rule

#### Scenario: Excluding a property that is never compared is harmless

- **WHEN** a scope excludes a property the differ does not compare
- **THEN** the scope is accepted and every compared property is still tracked

### Requirement: A scope can filter a single comparison without a tracker

The library SHALL let a caller apply a tracking scope to one comparison of two instances, reporting
exactly the changes a tracker carrying that scope would report for the same transition. It SHALL hold
no baseline and remember nothing, for the caller that already holds both instances and wants the
tracked view of the difference between them.

When no scope is given, the scope the differ's type declares SHALL apply; when the differ's type
declares none, every compared property SHALL be reported.

#### Scenario: A filtered comparison equals a tracker's report

- **WHEN** two instances are compared with a scope applied directly
- **AND** a tracker carrying the same scope is shown the same two instances
- **THEN** both report equal changes, in the same order

#### Scenario: A declared scope applies when none is given

- **WHEN** two instances of a type with a declared tracking scope are compared with no scope given
- **THEN** the declared scope decides what is reported

#### Scenario: Everything compared is reported when nothing is declared

- **WHEN** the differ's type declares no scope and none is given
- **THEN** every change the comparison found is reported

#### Scenario: A filtered comparison remembers nothing

- **WHEN** the same two instances are compared this way twice
- **THEN** both comparisons report the same changes
