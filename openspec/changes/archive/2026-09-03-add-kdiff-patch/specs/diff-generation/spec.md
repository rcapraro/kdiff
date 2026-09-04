## MODIFIED Requirements

### Requirement: An annotated sealed type dispatches on the runtime subclass

`@Diffable` SHALL be honoured on a sealed class or sealed interface whose subclasses are all
themselves `@Diffable`. The generated differ SHALL dispatch on the runtime type of the two
instances.

When both instances are the same subclass, the differ SHALL delegate to that subclass's differ and
report its changes unchanged.

When the two instances are different subclasses, the differ SHALL report a type change carrying the
two type names **and the two values**, and SHALL additionally compare the properties declared by
the sealed parent itself, reporting those as ordinary changes. It SHALL NOT descend into properties
that belong to only one of the two subclasses.

A type change SHALL carry the values because a type name alone cannot be turned back into an
instance: without them a subclass change is a difference that can be reported but never replayed.

When the sealed parent declares no properties of its own, a subclass change SHALL produce the type
change alone.

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

### Requirement: A change identifies where it was found

Every change SHALL carry a path locating it relative to the root being diffed. A path SHALL be
composed of segments naming a property, an index into a positional collection, or a key into a
keyed collection.

A key segment SHALL retain the key itself, not a rendering of it, so that an element or entry it
identifies can be located and reconstructed. A key that is not a string SHALL survive in the path
as the value it is.

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
