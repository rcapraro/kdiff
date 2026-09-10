## MODIFIED Requirements

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

Regeneration SHALL be no wider than that. A generated differ SHALL be built from the declarations it
actually read, not from the module it sits in, so that editing an annotated class regenerates that
class's differ and leaves the differs of unrelated annotated classes as they were. A module of many
annotated classes SHALL therefore not regenerate all of them because one changed.

Both halves SHALL be observable from a consuming build rather than inferred from how generation is
wired: what a second build rewrites, and what it leaves alone, SHALL be checkable by building a
consumer twice with an edit between the runs.

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

#### Scenario: An unrelated annotated class is not regenerated

- **WHEN** a module declares two annotated classes that share no type, it has been built once, and one
  of them is then edited
- **THEN** the next build rewrites that class's generated file
- **AND** leaves the other class's generated file as the first build produced it

#### Scenario: A build reusing previous output agrees with a build from nothing

- **WHEN** any sequence of edits to the declarations a differ read is followed by a build reusing
  previous output
- **THEN** it succeeds exactly when a build from nothing succeeds, and reports the same diagnostics
