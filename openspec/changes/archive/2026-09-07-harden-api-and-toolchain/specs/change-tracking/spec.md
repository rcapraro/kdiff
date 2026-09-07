## MODIFIED Requirements

### Requirement: A tracking scope can be written by hand for a type that cannot be annotated

The library SHALL provide a way to build a `TrackScope<T>` in ordinary Kotlin, naming the tracked
fields of `T` and their depths, for a type whose source cannot be annotated.

A hand-written scope SHALL be indistinguishable from a generated one to any code that consumes it: a
tracker SHALL behave identically whether its scope was declared by annotation or built by hand, and
SHALL NOT expose which route produced it.

Fields SHALL be named by property reference, so that a field that does not belong to the tracked
type SHALL NOT compile, and a renamed property SHALL be renamed at the call site by any refactoring
that renames it in the declaration.

A hand-written scope SHALL compose with a hand-written differ, so that a type carrying no kdiff
annotation at all can be both compared and tracked.

Building a scope standalone and declaring one inline while creating a tracker SHALL offer the same
members, with the same names, the same parameters and the same validation. Neither route SHALL accept
a scope the other rejects, or reject a scope the other accepts: a caller who learns one has learned
both.

#### Scenario: A scope built by hand tracks the fields it names

- **WHEN** a scope for `Money` is built by hand naming its `amount` property
- **AND** a tracker using it is updated with a `Money` whose `amount` and `currency` both differ
- **THEN** the update reports one change, at `amount`

#### Scenario: A hand-written scope is indistinguishable from a generated one

- **WHEN** one tracker is created with a hand-written scope and another with the scope declared by
  annotation on the same type, both naming the same fields at the same depths
- **THEN** both trackers report the same changes for the same update

#### Scenario: A type with no annotations at all can be tracked

- **WHEN** a differ for a third-party type is built by hand and a scope for it is built by hand
- **THEN** a tracker over that type reports the changes its scope selects
- **AND** the type carries no kdiff annotation

#### Scenario: A field reference from another type does not compile

- **WHEN** a scope for `Order` names a property reference belonging to `Address`
- **THEN** the code does not compile

#### Scenario: Declaring a scope inline offers exactly the standalone members

- **WHEN** a scope is declared inline while creating a tracker, using each member the standalone
  builder offers
- **THEN** the code compiles
- **AND** the tracker reports the changes the equivalent standalone scope selects

#### Scenario: The two routes reject the same scope

- **WHEN** a scope that one route rejects is declared through the other
- **THEN** it is rejected there too, with the same error

## ADDED Requirements

### Requirement: Every stated depth is validated wherever it is stated

A depth SHALL be at least 1, or the value denoting unlimited depth. Any other depth SHALL be rejected
where it is stated, whether it is stated as a scope's own depth or as the depth of one named field, and
whether the scope is built standalone or declared inline while creating a tracker.

The rejection SHALL state what is allowed and the value that was given, and SHALL name the property
when the depth was stated for one. An annotated class states its depths in source and has them
rejected as compile errors; a hand-written scope states them at runtime and SHALL have them rejected
as it is built, before any tracker can hold it.

This is behaviour the library already has; it is stated here because the two builders now reach it
through one shared declaration rather than two hand-mirrored ones, and the point of that change is
that this requirement cannot come to hold for one route and not the other.

#### Scenario: A field's depth of zero is rejected

- **WHEN** a scope names a field at depth 0
- **THEN** building it fails with an error naming the value 0 and stating that a depth must be at
  least 1 or unlimited

#### Scenario: A negative depth other than unlimited is rejected

- **WHEN** a scope names a field at depth -2
- **THEN** building it fails with an error naming the value -2

#### Scenario: The unlimited depth is accepted for a field

- **WHEN** a scope names a field at the unlimited depth
- **THEN** building it succeeds
- **AND** the field is tracked to any depth, as if named for its whole subtree

#### Scenario: A scope's own invalid depth is rejected

- **WHEN** a scope states its own depth as 0
- **THEN** building it fails with an error naming the value 0

#### Scenario: An inline scope is validated the same way

- **WHEN** a tracker is created with an inline scope naming a field at depth 0
- **THEN** creating it fails with the same error the standalone builder reports

### Requirement: A scope block cannot reach the members of an enclosing builder

A block that declares a tracking scope SHALL NOT expose the members of any builder block enclosing it,
and SHALL NOT expose its own members to a block nested inside it. Calling an enclosing builder's
member from such a block SHALL be a compile error.

Reaching the enclosing builder deliberately SHALL remain possible by naming its receiver explicitly.

Nothing about which changes a correctly-written scope selects SHALL change.

#### Scenario: A differ's member is not reachable from a scope block

- **WHEN** a scope block nested inside a differ block calls a member belonging to the differ builder
- **THEN** the code does not compile

#### Scenario: A callback declaration is not reachable from an inline scope

- **WHEN** an inline scope block calls a member that belongs only to the enclosing tracker builder
- **THEN** the code does not compile

### Requirement: A tracker block runs exactly once, so a caller can initialise a value in it

Every function of the library that takes a tracking or scope block SHALL state that it invokes that
block exactly once, so that a `val` declared outside the block can be assigned inside it and read
after it without the compiler reporting it as possibly uninitialised.

#### Scenario: A value is initialised inside a tracker block

- **WHEN** a caller declares an uninitialised `val`, assigns it inside a tracker or scope block, and
  reads it after the block
- **THEN** the code compiles
