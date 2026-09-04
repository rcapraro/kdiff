## MODIFIED Requirements

### Requirement: A differ can be written by hand for a type that cannot be annotated

The library SHALL provide a way to build a `Differ<T>` in ordinary Kotlin, for a type whose source
cannot be annotated. A hand-written differ SHALL be indistinguishable from a generated one to any
code that consumes it, so the two compose when one type nests the other.

A property SHALL be able to name a hand-written differ with `@DiffWith`, and the generated code
SHALL delegate that property's comparison to it, prefixing its paths like any nested delegation.

A hand-written differ SHALL be able to describe every comparison shape an annotated class can
declare: a property compared by value, a nested property whether nullable or not, a list compared by
the key its element type declares, a list compared by position, a set compared as unordered
membership, a map compared by entry key, and a sealed type dispatched on its runtime subclass. Each
SHALL report the same changes, at the same paths, in the same order as the annotated declaration of
the same model — so a model can move between the two routes without any consumer noticing.

A property the hand-written differ does not name SHALL NOT be compared, which is what `@DiffIgnore`
states for an annotated class.

A hand-written differ that declares subtypes SHALL compare two instances of the same declared subtype
by delegating to that subtype's differ, and two instances of different subtypes by reporting one type
change at the root followed by the comparison of the properties it names itself — the same rule an
annotated sealed type follows.

Every property, key and element type SHALL be named by property reference rather than by text, and no
reflection library SHALL be required.

#### Scenario: A hand-written differ compares a third-party type

- **WHEN** a differ for `Money` is built by hand naming its `amount` and `currency` properties
- **AND** two `Money` instances differ in `amount`
- **THEN** that differ reports one value change at `amount`

#### Scenario: A property delegates to a hand-written differ

- **WHEN** `Invoice.total: Money` is annotated with `@DiffWith` naming the hand-written `Money`
  differ, and the two totals differ in `amount`
- **THEN** the result reports one value change at path `total.amount`

#### Scenario: A hand-written differ nests inside a generated one

- **WHEN** a hand-written differ is reached through two levels of generated delegation
- **THEN** its changes appear at the full path from the root

#### Scenario: A hand-written keyed list reports a move rather than a removal and an addition

- **WHEN** a hand-written differ describes `addresses` as a list keyed by `Address.id`
- **AND** two addresses swap positions between the two instances
- **THEN** the result reports a move for each, at `addresses[id=A1]` and `addresses[id=A2]`
- **AND** an element present only on one side is reported as added or removed at its key

#### Scenario: A hand-written positional list is compared index by index

- **WHEN** a hand-written differ describes a list with no key
- **AND** the element at index 1 differs
- **THEN** the change is reported under `[1]` of that property, and no move is ever reported

#### Scenario: A hand-written set is compared as membership

- **WHEN** a hand-written differ describes a `Set<String>` property
- **AND** one element is present only in the new instance
- **THEN** the result reports one addition under that property and nothing else

#### Scenario: A hand-written map is compared by entry key

- **WHEN** a hand-written differ describes a `Map<String, String>` property
- **AND** one entry's value differs
- **THEN** the result reports one value change at that property's entry key

#### Scenario: A hand-written nullable nested property reports a null side as a value change

- **WHEN** a hand-written differ describes a nested property that is null on one side only
- **THEN** the result reports one value change at the property, not an addition or a removal

#### Scenario: A hand-written sealed differ delegates when both sides are the same subtype

- **WHEN** a hand-written differ declares a subtype differ for each subclass of `Employment`
- **AND** both sides are `Employed` with different employers
- **THEN** the result reports the subtype differ's changes, at the paths that differ reports

#### Scenario: A hand-written sealed differ reports a subclass swap as a type change

- **WHEN** the two sides are different declared subtypes
- **THEN** the result reports one type change at the root carrying both type names and both values
- **AND** the properties the hand-written differ names itself are compared alongside it

#### Scenario: Two instances of one undeclared subtype report no type change

- **WHEN** a hand-written differ declares subtypes but not the one both sides hold
- **THEN** no type change is reported, because the type did not change
- **AND** the properties the differ names itself are still compared

#### Scenario: A hand-written description of an annotated model reports identical diffs

- **WHEN** the same model is described once with annotations and once by hand, property for property
- **AND** the same pair of instances is compared with each
- **THEN** both report equal changes, at equal paths, in the same order

## ADDED Requirements

### Requirement: A diff can be routed to handlers named by property reference

The library SHALL dispatch the changes of a diff to handlers, each naming the property it handles by
property reference rather than by a property name written as text. Routing SHALL read only the diff,
so it behaves identically whether the differ was generated from annotations or written by hand, and
SHALL require no reflection library.

A handler for a property SHALL run at most once per routing, and only when at least one change sits
under that property. It SHALL receive the changes under that property, in the order the diff reports
them.

A handler for a collection property SHALL be able to react separately to an element added and an
element removed, and, where the collection's elements carry a key, to an element moved and an element
changed in place. An added or removed element SHALL be supplied at the property's element type, and
the key identifying a keyed element at the key property's own type, with no cast written by the
caller. A handler for an element changed in place SHALL run once for each element that changed,
however many of that element's properties changed, and SHALL receive that element's key.

Reacting to a moved or in-place-changed element SHALL NOT be expressible for a collection whose
elements carry no key: without a key there is nothing to identify the element by.

A change that no handler names SHALL be reported to a fallback handler when one is declared, and SHALL
be ignored without failing when none is. A change at the root of the routed type belongs to no
property and SHALL be treated as unhandled. A change under a collection property that its element
routing has no shape for SHALL be treated as unhandled too, rather than dropped — declining a kind of
change is a decision, while being unable to express one is not. Every change SHALL reach at most one
handler, and the same property SHALL NOT be named twice in one routing.

#### Scenario: A property's handler runs once with the changes under it

- **WHEN** a diff of `Person` reports changes at `name.given` and `name.family`
- **AND** it is routed with a handler naming `Person::name`
- **THEN** that handler runs once, receiving both changes

#### Scenario: A property with no change does not run its handler

- **WHEN** a diff reports no change under `Person::nickname`
- **AND** it is routed with a handler naming `Person::nickname`
- **THEN** that handler does not run

#### Scenario: An added element is supplied at its element type

- **WHEN** a diff reports an element added to a `List<Address>` property
- **AND** it is routed with a collection handler for that property
- **THEN** the added-element handler receives an `Address`, with no cast written by the caller

#### Scenario: A removed element is supplied at its element type

- **WHEN** a diff reports an element removed from that property
- **THEN** the removed-element handler receives the removed `Address`

#### Scenario: A moved element supplies its key and both positions

- **WHEN** a diff reports a keyed element moved from index 0 to index 1
- **THEN** the move handler receives the key as an `AddressId` and the two positions

#### Scenario: An element changed in place runs its handler once

- **WHEN** a diff reports changes at `addresses[id=A1].city` and `addresses[id=A1].postalCode.value`
- **THEN** the changed-element handler runs once, receiving the key `A1`

#### Scenario: A change under an unnamed property reaches the fallback

- **WHEN** a diff reports a change under a property no handler names
- **AND** a fallback handler is declared
- **THEN** the fallback receives that change

#### Scenario: A change at the root reaches the fallback

- **WHEN** a diff reports a type change at the root of the routed type
- **THEN** that change is treated as unhandled and reaches the fallback

#### Scenario: An element change an unkeyed routing cannot express reaches the fallback

- **WHEN** a change is reported inside an element of a collection whose elements carry no key
- **AND** that property is routed by element
- **THEN** the change reaches the fallback rather than being dropped

#### Scenario: An unhandled change with no fallback is ignored

- **WHEN** a diff reports a change no handler names and no fallback is declared
- **THEN** routing completes without failing and without running any handler for it

#### Scenario: Routing behaves the same for a hand-written differ

- **WHEN** the same transition is compared once by a generated differ and once by a hand-written one
- **AND** both diffs are routed with the same handlers
- **THEN** the same handlers run, with the same values, in the same order

#### Scenario: Naming one property twice is rejected

- **WHEN** a routing names the same property in two handlers
- **THEN** it is rejected rather than running one handler and dropping the other

## REMOVED Requirements

### Requirement: An annotated trackable class generates a token for each compared property

**Reason**: Field tokens are reachable only from `@Trackable`, so the annotation-free authoring route
has no way to dispatch on a change except by property name written as text — the very failure tokens
existed to prevent. Routing a diff to handlers named by property reference serves both routes with one
mechanism, and removes a generated sealed hierarchy per trackable class.

**Migration**: Replace a `when` over `<Type>Field` with a routing of the diff, naming each property by
reference. The compile-time exhaustiveness a sealed token hierarchy gave is not preserved: a property
added to a model no longer breaks a consumer's dispatch, and its changes reach the fallback handler
instead.

### Requirement: A change resolves to the token of the property it sits under

**Reason**: Removed with the tokens it resolves to.

**Migration**: A routing selects the handler for the property a change sits under, applying the same
rule — a change at the root belongs to no property.

### Requirement: A collection property's token exposes the change's element at its element type

**Reason**: Removed with the tokens that carried it.

**Migration**: A collection handler in a routing supplies the added or removed element at the
property's element type, with no cast written by the caller.

### Requirement: A keyed collection's token exposes the element key at its key type

**Reason**: Removed with the tokens that carried it.

**Migration**: A collection handler in a routing supplies the key at the key property's own type.

### Requirement: A trackable class whose tokens would collide is a compile error

**Reason**: No tokens are generated, so no token name can collide. Nothing else in the generated
output derives a name from a property name.

**Migration**: None. A class rejected only for a token collision now compiles.

### Requirement: Tokens add no runtime dependency beyond the result types

**Reason**: Removed with the tokens it constrained. Routing is part of the result types and carries
the same constraint, stated in the routing requirement.

**Migration**: None.
