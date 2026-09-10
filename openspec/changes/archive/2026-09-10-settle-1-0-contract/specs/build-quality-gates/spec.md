## MODIFIED Requirements

### Requirement: The published API surface is recorded and guarded

Each published module SHALL have its public API surface recorded in a file checked into the
repository. The build SHALL fail when a module's actual public API differs from its recorded one.

The record SHALL cover what consumers can compile against: the published modules' public and protected
declarations. Modules that are not published SHALL NOT be recorded, and a declaration that is internal
and reachable only from within its own module SHALL NOT appear in the record.

The record SHALL also contain entries that are not part of what the library promises, because the
platform requires them to exist: a declaration published solely so that a function inlined into a
consumer can reach it, and the accessors an inline value class lowers to. These SHALL be recorded like
anything else, so that their disappearance still fails the build, and the library's documented
statement of what it promises SHALL name these categories, so that a reader of the record can tell a
promise from an artefact of compilation.

A published module whose artefact exists to provide a code-generation entry point SHALL record that
entry point and nothing else. The processor's classes are reached by the compiler through the module's
service registration, never named by a consumer, so anything else in its record would be a promise the
module has no reason to make.

Updating the record SHALL be a deliberate, single command, so that adding to the API is a one-line
diff a reviewer can read and removing from it cannot happen unnoticed.

#### Scenario: Removing a public declaration fails the build

- **WHEN** a public declaration is removed from a published module and the record is not updated
- **THEN** the check fails, naming the declaration that disappeared

#### Scenario: Adding a public declaration fails until recorded

- **WHEN** a public declaration is added to a published module and the record is not updated
- **THEN** the check fails, naming the declaration that appeared
- **AND** running the recording command makes the check pass with the new declaration in the record

#### Scenario: An internal declaration is not recorded

- **WHEN** an internal declaration reachable only from within its module is added to a published module
- **THEN** the check passes without the record changing

#### Scenario: A declaration published for inlining is recorded but not promised

- **WHEN** a declaration is internal but published so that an inline function can reach it
- **THEN** it appears in the record, and removing it fails the build
- **AND** the documented statement of what the library promises identifies it as recorded rather than
  promised

#### Scenario: The code-generation module records only its entry point

- **WHEN** the processor module's record is read
- **THEN** it names the declaration the compiler loads through the service registration
- **AND** it names no other declaration of that module

#### Scenario: A non-published module has no record

- **WHEN** the public API of the sample, tutorial or benchmark module changes
- **THEN** no record is required and the check passes
