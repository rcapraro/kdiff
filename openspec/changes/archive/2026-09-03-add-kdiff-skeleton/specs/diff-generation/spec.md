## Purpose

Defines what annotating a Kotlin class makes available to the annotating project: the differ that
becomes callable for that class, the result that differ returns when comparing two instances of it,
and the compile errors that reject an annotation the library cannot honour.

## ADDED Requirements

### Requirement: A differ is generated for every annotated data class

Annotating a data class with `@Diffable` SHALL make a differ for that class available to the
annotating module at compile time, without the author writing or registering anything else.

The differ SHALL be a singleton named `<Type>Differ`, declared in the same package as the annotated
class, implementing `Differ<Type>`. It SHALL expose `diff(before: Type, after: Type): Diff`.

The differ SHALL be generated only for annotated classes. An unannotated class SHALL NOT gain one.

#### Scenario: Annotated data class gains a differ

- **WHEN** a module contains `@Diffable data class Person(val id: String, val name: String)` in
  package `demo`
- **THEN** the module compiles successfully
- **AND** `demo.PersonDiffer` is available to that module's own code
- **AND** `demo.PersonDiffer.diff(a, b)` accepts two `Person` instances and returns a `Diff`

#### Scenario: Unannotated class gains nothing

- **WHEN** a module contains `data class Order(val id: String)` with no `@Diffable` annotation
- **THEN** the module compiles successfully
- **AND** no `OrderDiffer` exists
- **AND** referring to `OrderDiffer` is a compile error

#### Scenario: Two annotated classes each get their own differ

- **WHEN** a module annotates both `Person` and `Address` with `@Diffable`
- **THEN** both `PersonDiffer` and `AddressDiffer` are available
- **AND** each accepts only instances of its own type

### Requirement: A diff result reports an ordered list of changes

`diff` SHALL return a `Diff` describing how `after` differs from `before`. The `Diff` SHALL expose
the changes it found as an ordered list, and SHALL report directly whether it found none.

A `Diff` SHALL be a value: comparing the same pair of instances twice SHALL produce equal results,
and calling `diff` SHALL NOT modify either instance.

#### Scenario: Result exposes its changes and its emptiness

- **WHEN** a caller obtains a `Diff` from any differ
- **THEN** the `Diff` exposes an ordered list of changes
- **AND** the `Diff` reports whether that list is empty
- **AND** an empty list and "reports empty" always agree

#### Scenario: Diffing is repeatable and does not mutate its inputs

- **WHEN** a caller calls `diff(a, b)` twice with the same two instances
- **THEN** both calls return equal results
- **AND** `a` and `b` are unchanged

### Requirement: Field comparison is out of scope for this change

This change establishes the generation pipeline, not the comparison. A generated differ SHALL
compare no fields and SHALL report no changes for any pair of instances of its type, including
instances that differ.

This is a deliberate, temporary baseline. It exists so the pipeline can be proved end to end
before comparison semantics are specified, and it is superseded by the change that introduces
field-by-field comparison.

#### Scenario: Equal instances produce an empty diff

- **WHEN** `PersonDiffer.diff(p, p)` is called
- **THEN** the result reports no changes

#### Scenario: Differing instances also produce an empty diff, for now

- **WHEN** `PersonDiffer.diff(Person("1", "Ada"), Person("1", "Grace"))` is called
- **THEN** the result reports no changes
- **AND** this holds only until field comparison is introduced

### Requirement: Annotating an unsupported declaration is a compile error

`@Diffable` SHALL be honoured only on data classes. Applied to any other declaration, it SHALL
fail the compilation with an error that names the offending declaration and states that
`@Diffable` requires a data class.

The error SHALL be reported at the location of the offending declaration, so an IDE and a build
log both point at the annotation's target rather than at generated code.

An unsupported declaration SHALL never be silently skipped: the build SHALL NOT succeed while
producing no differ for it.

#### Scenario: Annotated regular class is rejected

- **WHEN** a module contains `@Diffable class Person(val name: String)` — a class, not a data class
- **THEN** compilation fails
- **AND** the error message names `Person` and states that `@Diffable` requires a data class
- **AND** the error is reported at the declaration of `Person`

#### Scenario: Annotated interface is rejected

- **WHEN** a module contains `@Diffable interface Shape`
- **THEN** compilation fails with the same error, reported at `Shape`

#### Scenario: Annotated object is rejected

- **WHEN** a module contains `@Diffable object Registry`
- **THEN** compilation fails with the same error, reported at `Registry`

#### Scenario: Annotated enum class is rejected

- **WHEN** a module contains `@Diffable enum class Status { OPEN, CLOSED }`
- **THEN** compilation fails with the same error, reported at `Status`

#### Scenario: A rejected declaration blocks the build

- **WHEN** a module contains one valid `@Diffable` data class and one `@Diffable` interface
- **THEN** compilation fails
- **AND** the failure is attributable to the interface, not to the valid data class

### Requirement: Generating differs adds no runtime dependency beyond the result types

A module that annotates classes SHALL need only the annotations and the result types on its
runtime classpath. Whatever performs the generation SHALL NOT appear on a consuming module's
runtime or published dependencies.

#### Scenario: Consumer runtime classpath excludes the generator

- **WHEN** a module annotates a class with `@Diffable` and its build succeeds
- **THEN** its runtime classpath contains the annotations and the result types
- **AND** its runtime classpath contains nothing that performs generation

#### Scenario: Generated code depends only on the result types

- **WHEN** a generated differ is compiled
- **THEN** it references only the annotated class, the Kotlin standard library, and the result types
