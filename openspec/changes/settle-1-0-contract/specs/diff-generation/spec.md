## ADDED Requirements

### Requirement: A capability interface may gain a member without breaking an implementation of it

The interfaces a generated object declares — comparison, application and tracking — are implemented by
hand as well as generated, so growing one is the library's only way to offer a capability more without
forcing every hand-written implementation to be revisited.

Such an interface MAY gain a member in a release that is not a major one, provided that member carries
an implementation of its own. An implementation that predates the member SHALL keep compiling, and
SHALL behave as the new member's own implementation describes.

A comparison or application interface SHALL remain expressible as a single function expression after
gaining such a member, so a one-off differ or patcher written as a lambda is not made illegal by a
capability being added.

A generated object MAY come to declare a further capability interface. Doing so SHALL NOT change what
the object already declares: its name, its package, and the members of the interfaces it already
implements SHALL be as they were, and code written against them SHALL neither be recompiled nor
adapted.

#### Scenario: A hand-written implementation predating a member still compiles

- **WHEN** an interface a hand-written object implements gains a member carrying its own implementation
- **THEN** that object compiles unchanged
- **AND** calling the new member on it runs the implementation the interface carries

#### Scenario: A lambda implementation survives the addition

- **WHEN** a comparison or application interface that gained such a member is implemented as a single
  function expression
- **THEN** it compiles
- **AND** it is accepted everywhere the interface is expected

#### Scenario: A further capability leaves the existing declaration alone

- **WHEN** a generated object comes to declare a capability interface it did not declare before
- **THEN** its name and package are unchanged
- **AND** every member of the interfaces it already implemented has the signature it had
- **AND** a consumer calling only those members needs no change

#### Scenario: A member without an implementation is not added this way

- **WHEN** a capability interface gains a member with no implementation of its own
- **THEN** that is a breaking change, and is released as one
