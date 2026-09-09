## ADDED Requirements

### Requirement: A generated differ is regenerated when a declaration it read changes

Deciding how to compare a property SHALL sometimes read a declaration other than the annotated class's
own: the type of a property is what says whether that type is compared as a single value, and an author
declares such a type in a file of their own — as an `enum class`, as an inline `value class`, or by
declaring it a value outright.

Every declaration consulted in that decision SHALL be recorded as one the generated differ was built
from. Editing any of them SHALL cause the differ to be generated again.

The observable consequence, which is the requirement: **a build that reuses previous output SHALL
report what a build from nothing reports.** A generated differ that a build from nothing refuses SHALL
NOT survive as output that compiles, and a comparison decision SHALL NOT outlive the declaration it was
taken from.

#### Scenario: Editing a value type regenerates the differs that compared it

- **WHEN** a module declares an inline `value class` in its own file, an annotated class compares a
  property of that type, and the module has been built once
- **AND** that class is then redeclared as an ordinary type that kdiff cannot compare, with the
  annotated class's own file left untouched
- **THEN** the next build reports the same error a build from nothing reports, naming the property and
  its type
- **AND** no differ comparing that property as a single value survives from the previous build

#### Scenario: Editing an enum regenerates the differs that compared it

- **WHEN** the same is done with an `enum class` redeclared as a type kdiff cannot compare
- **THEN** the next build reports the same error a build from nothing reports

#### Scenario: Editing a type declared to compare as one value regenerates its readers

- **WHEN** the same is done by removing the declaration that made a type compare as one value, so that
  the type is no longer comparable
- **THEN** the next build reports the same error a build from nothing reports

#### Scenario: Removing an exclusion from an overridden property regenerates the subclass's differ

- **WHEN** a `@Diffable` sealed parent excludes a property from comparison, a `@Diffable` subclass
  overrides it, and the module has been built once
- **AND** the exclusion is then removed from the parent, with the subclass's own file left untouched
- **THEN** the next build reports the property's changes, as a build from nothing does

#### Scenario: Adding a declaration to an overridden property regenerates the subclass's differ

- **WHEN** neither a sealed parent's property nor the subclass override of it carries any comparison
  declaration, and the module has been built once
- **AND** a value declaration is then added to the parent's property, with the subclass's own file left
  untouched
- **THEN** the next build compares that property as one value from the subclass too, as a build from
  nothing does

#### Scenario: A build reusing previous output agrees with a build from nothing

- **WHEN** any sequence of edits to the declarations a differ read is followed by a build reusing
  previous output
- **THEN** it succeeds exactly when a build from nothing succeeds, and reports the same diagnostics

### Requirement: A comparison annotation on an overridden property is honoured on the override

A property declared by a sealed parent and overridden by a subclass SHALL be compared as the parent
declared it, whichever branch of the dispatch compares it.

Where the comparison of a property is configured by an annotation on the declaration — declaring it a
single value, excluding it from comparison, or naming a differ for it — and the property overrides
another, the annotation SHALL be read from the overridden declaration when the override carries no such
annotation of its own. An annotation on the override SHALL take precedence over one on the declaration
it overrides, so a subclass can still say something different from its parent.

This SHALL hold for the two instances being the same subclass, which delegates to that subclass's
differ, exactly as it already holds for the two being different subclasses, which compares the parent's
properties directly. The two branches SHALL NOT disagree about how one property is compared.

Only annotations configuring how a property is compared are inherited this way. A key declaration is
read from a collection's element type and a tracking scope from the annotated class itself, so neither
has an overridden declaration to consult.

#### Scenario: A value declaration on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffAsValue val meta: Meta` with `Meta` being
  `@Diffable`, and `@Diffable data class Letter(override val meta: Meta, val body: String) : Doc`
- **THEN** two `Letter` instances whose `meta` differs inside report exactly one value change at
  `meta`, carrying both `Meta` instances
- **AND** no change is reported at `meta.title`

#### Scenario: The same subclass and a subclass swap agree about the parent's property

- **WHEN** the parent declares `@DiffAsValue val meta: Meta` and one subclass overrides it
- **THEN** two instances of that subclass report the property the same way a swap between two
  subclasses reports it: one value change at `meta`, never a change beneath it

#### Scenario: An exclusion on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffIgnore val revision: String` and a
  `@Diffable` subclass overrides it
- **THEN** two instances of that subclass whose `revision` differs report no change at `revision`

#### Scenario: A hand-written differ named on a sealed parent property is honoured by a subclass

- **WHEN** `@Diffable sealed interface Doc` declares `@DiffWith(WeightDiffer::class) val weight:
  Weight` and a `@Diffable` subclass overrides it
- **THEN** two instances of that subclass are compared for that property by the named differ, with its
  paths reported beneath `weight`

#### Scenario: An annotation on the override wins

- **WHEN** a sealed parent declares `@DiffAsValue val meta: Meta` and a subclass's override carries
  `@DiffIgnore`
- **THEN** the subclass reports no change at `meta`, the override's own declaration being what applies

#### Scenario: A declaration inherited from another module is honoured, not reported

- **WHEN** a `@Diffable` class overrides a property declared in a compiled dependency that carries a
  value declaration, and that dependency generated nothing of its own
- **THEN** the declaration is honoured
- **AND** no diagnostic names the overriding property for an annotation it does not carry
- **AND** a differ is generated for the overriding class

#### Scenario: An unusable differ inherited from another module is still reported

- **WHEN** a `@Diffable` class overrides a property declared in a compiled dependency whose `@DiffWith`
  names something that is not an object implementing the comparison contract
- **THEN** compilation fails with a diagnostic naming what is wrong
- **AND** the property is not silently left uncompared

#### Scenario: An unannotated parent property is unaffected

- **WHEN** a sealed parent declares `val meta: Meta` with no comparison annotation and a `@Diffable`
  subclass overrides it
- **THEN** the property is compared as its type dictates, reporting changes beneath `meta` as before
