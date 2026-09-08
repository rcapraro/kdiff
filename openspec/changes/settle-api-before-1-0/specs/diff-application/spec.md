## ADDED Requirements

### Requirement: The helpers a hand-written patcher composes return the patcher's own result type

Every helper the library offers for rebuilding one property — a value, a nested value whether nullable
or not, a keyed list, a positional list, a set, a map, and the two that report a property as
unpatchable or as not reconstructible — SHALL return the same result type that applying to a whole
instance returns: the rebuilt value together with the changes that could not be applied to it.

There SHALL be one such type. A hand-written patcher composing the helpers SHALL read their results
with the same two members it returns from its own `apply`, and SHALL need no conversion between a
helper's result and its own.

#### Scenario: A helper's result is the patcher's result type

- **WHEN** a hand-written patcher rebuilds a `Money` from the value helper for `amount` and for
  `currency`
- **THEN** each helper's result is of the type the patcher's own `apply` returns
- **AND** the patcher constructs its result from their values and failures without converting either

#### Scenario: A helper's result can be required outright

- **WHEN** a caller applies the value helper to a property and requires its value, as it would a whole
  application's
- **THEN** the value is returned when every change applied, and the declared patch-failed exception is
  raised otherwise

#### Scenario: Generated code is unchanged by the single type

- **WHEN** a `@Diffable` class is regenerated
- **THEN** the generated file is textually identical to the one produced before the helpers changed
  their return type
