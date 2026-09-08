## ADDED Requirements

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

## MODIFIED Requirements

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
