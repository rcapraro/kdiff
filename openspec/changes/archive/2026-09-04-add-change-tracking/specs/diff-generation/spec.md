## ADDED Requirements

### Requirement: An annotated class can declare a tracking scope

`@Trackable` SHALL be honoured on a class that is also `@Diffable`. It SHALL declare every compared
property of that class as tracked, at the depth the annotation states, exactly as `@Diffable`
declares every property compared.

The generated declaration for such a class SHALL expose that scope as a `TrackScope` — the same type
a hand-written scope produces — so that a tracker can consume either without distinguishing them,
and so that no type structure is read at runtime.

Tracking SHALL be exposed on the same generated declaration that compares the type and applies
changes to it. The declaration's existing name, package, comparison signature and application
signature SHALL NOT change.

Declaring a scope SHALL NOT change how anything is compared: the changes `diff` reports for a class
SHALL be identical whether or not the class is `@Trackable`.

#### Scenario: An annotated class exposes a declared scope

- **WHEN** a module contains
  `@Diffable @Trackable(depth = 1) data class Order(val reference: String, val status: String)`
- **THEN** the module compiles successfully
- **AND** `OrderDiffer` exposes a scope naming `reference` and `status`, each at depth 1
- **AND** a tracker over `Order` created with no field tracks both properties at depth 1

#### Scenario: The scope is reached through the same declaration as comparison and application

- **WHEN** a class is both `@Diffable` and `@Trackable`
- **THEN** comparing it, applying changes to it, and reading its scope are all reached through
  `<Type>Differ`
- **AND** its comparison and application signatures are unchanged

#### Scenario: Declaring a scope leaves comparison unchanged

- **WHEN** the same data class is compiled with and without `@Trackable`
- **THEN** `diff(a, b)` reports the same changes in the same order in both cases

#### Scenario: An unannotated class exposes no scope

- **WHEN** a module contains `@Diffable data class Order(val reference: String)` with no tracking
  annotation
- **THEN** `OrderDiffer` exposes no scope
- **AND** its generated declaration is unchanged from one produced without this capability
- **AND** a tracker over `Order` created with no field tracks every property at unlimited depth

#### Scenario: An annotated sealed type declares a scope

- **WHEN** `@Diffable @Trackable sealed interface Payment` has all its subclasses `@Diffable`
- **THEN** compilation succeeds
- **AND** `PaymentDiffer` exposes a scope covering the properties the sealed parent declares

### Requirement: A property can be excluded from a declared tracking scope

`@TrackIgnore` SHALL exclude a property from its class's declared scope, as `@DiffIgnore` excludes a
property from comparison. The property SHALL still be compared: excluding it from tracking SHALL NOT
change any diff.

There SHALL be no property-level way to opt a property *into* tracking, for the same reason
comparison has none: the class opts in, and properties opt out.

#### Scenario: An excluded property is not in the declared scope

- **WHEN** a module contains
  `@Diffable @Trackable data class Order(val reference: String, @TrackIgnore val status: String)`
- **THEN** `OrderDiffer` exposes a scope naming `reference` and not `status`

#### Scenario: An excluded property is still compared

- **WHEN** that class is diffed with instances whose `status` differs
- **THEN** the diff reports a change at `status`

### Requirement: A property can declare its own tracking depth

`@TrackDepth` SHALL set the depth at which one property of a `@Trackable` class is tracked, taking
precedence over the class's depth for that property alone.

#### Scenario: A property's depth overrides the class's

- **WHEN** a module contains
  `@Diffable @Trackable(depth = 1) data class Order(val reference: String, @TrackDepth(2) val billing: Address)`
- **THEN** `OrderDiffer` exposes a scope with `reference` at depth 1 and `billing` at depth 2

#### Scenario: A property depth applies without a class depth

- **WHEN** a `@Trackable` class states no depth and one property is `@TrackDepth(1)`
- **THEN** that property is tracked at depth 1 and every other property at unlimited depth

### Requirement: An unlimited tracking depth is the default depth

`@Trackable` SHALL state its depth through a `depth` parameter whose default means unlimited: a
`@Trackable` declaration with no explicit depth SHALL track at unlimited depth, excluding nothing on
the grounds of depth.

#### Scenario: A bare annotation declares unlimited depth

- **WHEN** a module contains
  `@Diffable @Trackable data class Order(val reference: String, val billing: Address)`
- **THEN** `OrderDiffer` exposes a scope with unlimited depth for both properties
- **AND** a tracker over `Order` created with no field reports a change at `billing.city`

### Requirement: An invalid tracking depth is a compile error

A `depth` of zero, or any negative value other than the constant that denotes unlimited depth, SHALL
fail the compilation with an error that names the offending declaration and states the values `depth`
accepts.

The error SHALL be reported at the location of the offending declaration, whether that is the class
or the property.

#### Scenario: A zero depth on a class is rejected

- **WHEN** a module contains `@Diffable @Trackable(depth = 0) data class Order(val reference: String)`
- **THEN** compilation fails
- **AND** the error names `Order` and states which values `depth` accepts
- **AND** the error is reported at the declaration of `Order`

#### Scenario: A negative depth other than the unlimited constant is rejected

- **WHEN** a property is annotated `@TrackDepth(-2)`
- **THEN** compilation fails with the same error, reported at that property

### Requirement: Contradictory tracking annotations are a compile error

A property SHALL NOT be annotated both `@TrackIgnore` and `@TrackDepth`: one excludes the property
from the scope and the other configures it within the scope.

A property SHALL NOT be annotated `@TrackIgnore` or `@TrackDepth` while it is also `@DiffIgnore`,
because an ignored property produces no changes and so can never be tracked.

Each SHALL fail the compilation with an error that names the offending property and states which
annotations conflict, reported at that property.

A contradictory annotation SHALL never be resolved by preferring one side: the build SHALL NOT
succeed while silently ignoring one of the two annotations.

#### Scenario: Excluding and configuring the same property is rejected

- **WHEN** a property is annotated both `@TrackIgnore` and `@TrackDepth(2)`
- **THEN** compilation fails
- **AND** the error names the property and states that the two annotations conflict
- **AND** the error is reported at that property

#### Scenario: Configuring an ignored property is rejected

- **WHEN** a property is annotated both `@DiffIgnore` and `@TrackDepth(2)`
- **THEN** compilation fails with an error naming the property and stating that an ignored property
  cannot be tracked

#### Scenario: Excluding an ignored property from tracking is rejected

- **WHEN** a property is annotated both `@DiffIgnore` and `@TrackIgnore`
- **THEN** compilation fails with the same error, reported at that property

### Requirement: A tracking annotation with nothing to configure is a compile error

`@Trackable` SHALL be honoured only on a class that is also `@Diffable`: no declaration is generated
for an unannotated class, so nothing could expose the scope.

`@TrackIgnore` and `@TrackDepth` SHALL be honoured only on a property of a `@Trackable` class: with no
declared scope, there is nothing for either to exclude from or configure, and the annotation would
have no effect.

Each SHALL fail the compilation with an error naming the offending declaration and stating what the
annotation requires, reported at that declaration. The library SHALL NOT accept a tracking annotation
that silently does nothing, and SHALL point the author at the annotation that is missing.

#### Scenario: A trackable class that is not diffable is rejected

- **WHEN** a module contains `@Trackable data class Order(val reference: String)` with no `@Diffable`
- **THEN** compilation fails
- **AND** the error names `Order` and states that `@Trackable` requires `@Diffable`
- **AND** the error is reported at the declaration of `Order`

#### Scenario: A property annotation without a trackable class is rejected

- **WHEN** a module contains
  `@Diffable data class Order(val reference: String, @TrackIgnore val status: String)` and the class
  is not `@Trackable`
- **THEN** compilation fails
- **AND** the error names `status` and states that `@TrackIgnore` requires a `@Trackable` class

#### Scenario: A property depth without a trackable class is rejected

- **WHEN** the same class instead annotates a property `@TrackDepth(2)` and is not `@Trackable`
- **THEN** compilation fails with the corresponding error, reported at that property

#### Scenario: A rejected tracking annotation blocks the build

- **WHEN** a module contains one correctly trackable class and one class with a contradictory
  tracking annotation
- **THEN** compilation fails
- **AND** the failure is attributable to the contradictory annotation, not to the valid class

### Requirement: Declaring a tracking scope adds no runtime dependency

A declared scope SHALL be expressed in terms of the result types a consumer already needs. A module
that declares a tracking scope SHALL need only the annotations and the result types on its runtime
classpath, and its generated declaration SHALL reference nothing else.

#### Scenario: A tracked module's generated code depends only on the result types

- **WHEN** a generated declaration exposing a scope is compiled
- **THEN** it references only the annotated class, the Kotlin standard library, and the result types

#### Scenario: A tracked module's runtime classpath excludes the generator

- **WHEN** a module declares a tracking scope and its build succeeds
- **THEN** its runtime classpath contains nothing that performs generation
- **AND** it contains no reflection library
