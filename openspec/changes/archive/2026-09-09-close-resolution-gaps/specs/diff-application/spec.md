## MODIFIED Requirements

### Requirement: A property compared as a value is applied as a value

Applying a value change at a property compared as a value — a standard-library value type, an inline
value class, a type declared `@DiffAsValue`, a property declared `@DiffAsValue`, or a property that
inherits that declaration from a property it overrides — SHALL set the property to the change's new
value, whatever that value's type. A `@DiffAsValue` property whose type is `@Diffable` or a collection
SHALL be set wholesale, since the change carries the whole new value and nothing was reported beneath
it.

For any two instances of a type holding such properties, applying the diff between them to the first
SHALL produce an instance equal to the second and SHALL report no failures. This SHALL hold for a
property whose value declaration was inherited exactly as for one carrying it directly: comparison and
application read the same declaration, so they cannot disagree about whether a property is a value.

A change reported beneath a property compared as a value SHALL be reported as a failure whose reason
states that it is not applicable to a value property, as for any other value.

#### Scenario: Round-trip over standard-library value types

- **WHEN** two instances differ in a `BigDecimal`, an `Instant` and a `UUID` property
- **THEN** applying their diff to the first produces an instance equal to the second
- **AND** no failures are reported

#### Scenario: Round-trip over an inline value class

- **WHEN** two instances differ in an `Email` property, `Email` being an inline value class
- **THEN** applying their diff reproduces the target

#### Scenario: Round-trip over a property declared as a value

- **WHEN** two instances differ inside `@DiffAsValue val billing: Address`, `Address` being `@Diffable`
- **THEN** their diff holds one value change at `billing`
- **AND** applying it produces an instance whose `billing` is the target's, with no failures

#### Scenario: Round-trip over a collection declared as a value

- **WHEN** two instances differ in `@DiffAsValue val tags: List<String>`
- **THEN** applying their diff produces an instance whose `tags` is the target's list, with no failures

#### Scenario: Round-trip over a property inheriting its value declaration

- **WHEN** a `@Diffable` sealed parent declares `@DiffAsValue val meta: Meta`, a `@Diffable` subclass
  overrides it, and two instances of that subclass differ inside `meta`
- **THEN** their diff holds one value change at `meta`
- **AND** applying it produces an instance whose `meta` is the target's, with no failures

#### Scenario: A change beneath a value-declared property is reported

- **WHEN** a value change at `billing.city` is applied to a type declaring `@DiffAsValue val billing:
  Address`
- **THEN** the result reports that change as a failure stating it is not applicable to a value property
- **AND** the result's `billing` is the source's
