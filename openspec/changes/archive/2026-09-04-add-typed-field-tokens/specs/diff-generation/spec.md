## ADDED Requirements

### Requirement: A path can name the property a change sits under

`FieldPath` SHALL expose the name of the property the path begins with, or nothing when the path
begins with no property.

A path whose first segment is a property SHALL yield that property's name. The root path SHALL yield
nothing, because a change at the root belongs to no property. A path whose first segment identifies a
collection element rather than a property SHALL also yield nothing.

#### Scenario: A property path names its first property

- **WHEN** a change is found at `billing.city`
- **THEN** its path names `billing`

#### Scenario: A keyed element path names the collection property

- **WHEN** a change is found at `addresses[id=A2].street`
- **THEN** its path names `addresses`

#### Scenario: The root path names nothing

- **WHEN** a sealed type reports a type change at the root
- **THEN** its path names no property

### Requirement: An annotated trackable class generates a token for each compared property

A class that is `@Diffable` and `@Trackable` SHALL generate a closed set of field tokens, one per
compared property, so that a caller can decide what to do about a change by matching on the token
rather than on a property name written as text.

The tokens SHALL form a sealed hierarchy, so that a `when` over them is exhaustive and adding a
compared property to the class SHALL fail to compile any exhaustive `when` that does not yet handle it.

A token SHALL cover every property the class compares, including one excluded from tracking by
`@TrackIgnore` — a diff reports such a property even though a tracker does not, and a caller
dispatching over a diff must be able to name it. A property excluded from comparison by `@DiffIgnore`
SHALL NOT have a token, because it can never appear in any change.

Each token SHALL expose the name of the property it stands for.

Generating tokens SHALL NOT change how anything is compared, applied or tracked: the changes `diff`
reports, the result `apply` produces and the scope `trackScope` declares SHALL be identical whether or
not tokens are generated.

#### Scenario: A trackable class gains one token per compared property

- **WHEN** a module contains
  `@Diffable @Trackable data class Order(val reference: String, val status: String)`
- **THEN** the module compiles successfully
- **AND** a token exists for `reference` and for `status`
- **AND** each token exposes its property's name

#### Scenario: An untracked property still gets a token

- **WHEN** a `@Trackable` class has a property annotated `@TrackIgnore`
- **THEN** that property has a token
- **AND** the property is absent from the class's declared tracking scope

#### Scenario: An ignored property gets no token

- **WHEN** a `@Trackable` class has a property annotated `@DiffIgnore`
- **THEN** that property has no token

#### Scenario: A when over the tokens is exhaustive

- **WHEN** a caller writes a `when` covering every token of a class and the absent case
- **THEN** the `when` compiles without an `else` branch

#### Scenario: Adding a compared property breaks an exhaustive when

- **WHEN** a compared property is added to a `@Trackable` class
- **AND** an exhaustive `when` over its tokens does not handle the new one
- **THEN** compilation fails

#### Scenario: A class that is not trackable generates no tokens

- **WHEN** a module contains `@Diffable data class Order(val reference: String)` with no `@Trackable`
- **THEN** no token exists for `Order`
- **AND** its generated declaration is unchanged from one produced without this capability

#### Scenario: Generating tokens leaves comparison, application and tracking unchanged

- **WHEN** the same `@Trackable` class is compiled with and without token generation available
- **THEN** `diff(a, b)` reports the same changes in the same order
- **AND** `apply` produces the same result
- **AND** the declared tracking scope is the same

### Requirement: A change resolves to the token of the property it sits under

The library SHALL resolve a change to the token of the property it sits under, given the token set of
the type being compared.

A change under a compared property SHALL resolve to that property's token. A change at the root SHALL
resolve to nothing. A change under a property of a *different* type's token set SHALL resolve to
nothing rather than to a wrong token.

Resolution SHALL NOT read the type's structure at runtime.

#### Scenario: A change resolves to its property's token

- **WHEN** a change is found at `billing.city` and is resolved against the class's token set
- **THEN** it resolves to the token for `billing`

#### Scenario: A root change resolves to nothing

- **WHEN** a sealed type's subclass swap is resolved against its token set
- **THEN** it resolves to nothing

#### Scenario: An unrecognised property resolves to nothing

- **WHEN** a change naming a property absent from the token set is resolved against it
- **THEN** it resolves to nothing

### Requirement: A collection property's token exposes the change's element at its element type

The token of a property whose type is a list, set or map SHALL expose the element or entry value a
change carries, at the element type declared by the property — so that a caller reaching an added or
removed element never writes a cast.

It SHALL yield the value for a change that carries one, and nothing for a change that does not. It
SHALL yield nothing rather than fail when handed a change that belongs to a different property.

Typed access SHALL NOT read the type's structure at runtime: the conversion happens where the element
type is already known.

#### Scenario: An added element is reached at its element type

- **WHEN** an element is added to a `List<Address>` property
- **AND** the addition is read through that property's token
- **THEN** the element is obtained as an `Address` with no cast written by the caller

#### Scenario: A removed element is reached at its element type

- **WHEN** an element is removed from that property
- **THEN** the removed element is obtained as an `Address`

#### Scenario: A change carrying no element yields nothing

- **WHEN** a moved element, which carries only its two positions, is read for an element
- **THEN** nothing is obtained

#### Scenario: A change from another property yields nothing

- **WHEN** a change under a different property is read through this property's token
- **THEN** nothing is obtained

### Requirement: A keyed collection's token exposes the element key at its key type

The token of a list whose element type declares a `@DiffKey` SHALL expose the key identifying the
element a change is about, at the type of the key property — so that a caller identifying a keyed
element never writes a cast.

It SHALL yield nothing for a change that identifies no element.

#### Scenario: A keyed element's key is reached at its key type

- **WHEN** a change is found at `addresses[id=A2].street` and read through the `addresses` token
- **THEN** the key is obtained as an `AddressId` with no cast written by the caller

#### Scenario: A change identifying no element yields no key

- **WHEN** a change at the collection property itself is read for a key
- **THEN** nothing is obtained

#### Scenario: A list with no declared key exposes no key access

- **WHEN** a list's element type declares no `@DiffKey`
- **THEN** its token offers no key access

### Requirement: A trackable class whose tokens would collide is a compile error

Two compared properties of one class SHALL NOT produce colliding token names. Where the generated
token name for one property would collide with another's, the compilation SHALL fail with an error
naming the class and both properties, reported at the class.

A collision SHALL never be resolved by renaming one token silently: a caller matching on a token they
did not expect is worse than a build that stops.

#### Scenario: Colliding token names are rejected

- **WHEN** a `@Trackable` class declares two compared properties whose token names would collide
- **THEN** compilation fails
- **AND** the error names the class and both properties

### Requirement: Tokens add no runtime dependency beyond the result types

A module whose classes generate tokens SHALL need only the annotations and the result types on its
runtime classpath. Token resolution and typed access SHALL reference nothing else, and SHALL NOT
require a reflection library.

#### Scenario: A token-generating module's runtime classpath is unchanged

- **WHEN** a module generates tokens and its build succeeds
- **THEN** its runtime classpath contains the annotations and the result types
- **AND** it contains no reflection library and nothing that performs generation
