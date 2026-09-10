## Purpose

Defines what the build refuses and what it produces, so that a change to the published API surface, a
source file that does not match the project's formatting, or a static-analysis finding fails the same
single command that already defines done — and so that the KDoc already written is readable outside an
IDE.

## Requirements

### Requirement: One command runs every gate

`./gradlew check` SHALL remain the single command that decides whether the repository is in a good
state. Every gate this capability defines SHALL be reachable from it, and continuous integration SHALL
run nothing beyond it.

A gate SHALL be runnable on its own for a developer fixing one thing, and each gate SHALL name, in its
failure output, the command that records or fixes what it found where such a command exists.

#### Scenario: The aggregate command covers every gate

- **WHEN** a developer runs the aggregate check on a repository violating any one gate
- **THEN** the command fails
- **AND** the output identifies which gate failed and in which module

#### Scenario: Continuous integration adds no second command

- **WHEN** continuous integration runs for a commit
- **THEN** it runs the aggregate check and nothing else

#### Scenario: A failing gate says how to fix itself

- **WHEN** a gate that has a recording or formatting command fails
- **THEN** its output names that command

### Requirement: The published API surface is recorded and guarded

Each published module SHALL have its public API surface recorded in a file checked into the
repository. The build SHALL fail when a module's actual public API differs from its recorded one.

The record SHALL cover exactly what consumers can compile against: the published modules' public and
protected declarations. Modules that are not published SHALL NOT be recorded, and internal
declarations SHALL NOT appear in the record.

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

- **WHEN** an internal declaration is added to a published module
- **THEN** the check passes without the record changing

#### Scenario: A non-published module has no record

- **WHEN** the public API of the sample, tutorial or benchmark module changes
- **THEN** no record is required and the check passes

### Requirement: Every maintained source file matches one formatting

The repository SHALL state its formatting in a machine-readable configuration at its root, and the
build SHALL fail when a maintained Kotlin source file does not match it.

The stated formatting SHALL be a published, conventional Kotlin formatting rather than one derived
from the sources as they stand, so that a contributor's editor and the gate agree without the
repository having to describe itself. Adopting it therefore reformats the existing sources, and that
reformatting SHALL be isolated: it SHALL land as a change of formatting alone, touching no behaviour,
so that it stays reviewable and no other diff is buried in it.

A rule the project does not intend to follow SHALL be disabled once, in the configuration, with a
stated reason — never satisfied by a suppression at each site, and never by weakening the rule for
every file to accommodate a handful.

A single command SHALL reformat the sources to match, so a contributor never has to satisfy the gate
by hand. A violation the command cannot fix SHALL be fixed in the sources, not accommodated in the
configuration.

The gate SHALL apply to the sources the repository maintains — main and test sources of every module —
and SHALL NOT apply to generated sources. Generated code is written by the processor, not by a
contributor, and holding it to a contributor's formatting would make a processor change fail a gate
nobody can act on.

#### Scenario: A misformatted source file fails the build

- **WHEN** a maintained Kotlin file is indented against the stated formatting
- **THEN** the check fails, naming the file and the line

#### Scenario: Adoption is a formatting-only change

- **WHEN** the gate is adopted and the sources are reformatted to match it
- **THEN** the reformatting is a change of layout alone
- **AND** every test passes before and after it, with no assertion edited

#### Scenario: The gate passes once adoption is complete

- **WHEN** the formatting gate is run after adoption
- **THEN** it reports no violation and rewrites no file

#### Scenario: A rule the project rejects is disabled once, with a reason

- **WHEN** a rule would demand a shape the project does not intend to follow
- **THEN** that rule is disabled in the configuration with a stated reason
- **AND** no source file carries a suppression for it

#### Scenario: Generated sources are exempt

- **WHEN** the processor emits a file that does not match the stated formatting
- **THEN** the check passes

#### Scenario: One command reformats the sources

- **WHEN** a contributor runs the formatting command on a misformatted file
- **THEN** the file matches the stated formatting
- **AND** the check passes

### Requirement: Static analysis findings fail the build

The build SHALL run static analysis over the maintained Kotlin sources of every module and SHALL fail
on any finding.

The enabled rules SHALL be recorded in a configuration file checked into the repository, so that
disabling a rule is a reviewable decision rather than a local setting. The configuration SHALL be
chosen so that the repository passes at adoption without suppressions scattered through the sources: a
rule the project does not intend to follow SHALL be disabled once, in that file, rather than suppressed
at each site.

Static analysis SHALL NOT apply to generated sources, for the reason formatting does not.

#### Scenario: A finding fails the build

- **WHEN** a maintained source file introduces a construct an enabled rule forbids
- **THEN** the check fails, naming the rule, the file and the line

#### Scenario: Adoption needs no scattered suppressions

- **WHEN** the analysis is run against the repository's sources as they stand
- **THEN** it reports no finding
- **AND** no suppression annotation was added to any source file to achieve that

#### Scenario: A disabled rule is visible in one place

- **WHEN** a reviewer reads the analysis configuration
- **THEN** every rule the project has turned off is listed there

#### Scenario: Generated sources are exempt

- **WHEN** the processor emits a file an enabled rule would flag
- **THEN** the check passes

### Requirement: Reference documentation is produced from the sources

The build SHALL be able to produce browsable reference documentation for the published modules from
the KDoc already written on their public declarations.

Producing it SHALL be a single command. The output SHALL cover the three published modules and SHALL
NOT cover the sample, tutorial or benchmark modules, which are examples rather than published API.

Producing the documentation SHALL NOT be part of the aggregate check: it is an artefact to publish, not
a gate to pass, and building it on every check would cost every contributor time for output nobody
reads locally.

#### Scenario: Documentation is produced for the published modules

- **WHEN** a developer runs the documentation command
- **THEN** browsable documentation is produced for the annotations, runtime and processor modules
- **AND** each public declaration's KDoc appears in it

#### Scenario: Example modules are absent from the documentation

- **WHEN** the documentation is produced
- **THEN** it contains no page for the sample, tutorial or benchmark modules

#### Scenario: The aggregate check does not build documentation

- **WHEN** a developer runs the aggregate check
- **THEN** no documentation is produced

### Requirement: Published artifacts are publicly resolvable and carry their sources and documentation

Each published module SHALL be released to Maven Central, so that a consumer resolves it with the
central repository every build already declares and no credential. The sample, tutorial and benchmark
modules SHALL NOT be published.

Each published module's release SHALL consist of its compiled jar, a sources jar holding the module's
Kotlin sources, a documentation jar holding the reference documentation the build already produces, and
a descriptor naming the project, its description, its home, its licence, its developers and its source
repository. Every one of them SHALL be signed.

A release SHALL be produced by the same tag-triggered path that produces the release notes, and SHALL
fail before publishing anything when the tag's version does not match the version the build declares.
A version SHALL be published once: re-running the path for an already released version SHALL NOT
replace it.

What a release produces SHALL be inspectable locally, without publishing, so that the artifacts of a
release can be checked before any tag exists.

The group id SHALL be one whose namespace the maintainer can verify with the repository, and package
names SHALL NOT change on account of it.

#### Scenario: A consumer resolves the library with no repository configuration

- **WHEN** a build declares only the central repository and the three published coordinates at a
  released version
- **THEN** the annotations, runtime and processor modules resolve
- **AND** no credential is required

#### Scenario: Sources and documentation reach the consumer

- **WHEN** a consumer's IDE resolves a published module
- **THEN** a sources jar and a documentation jar are available for it
- **AND** navigating to a public declaration shows its Kotlin source and its documentation comment

#### Scenario: The artifacts of a release can be produced locally

- **WHEN** a developer runs the local publishing command
- **THEN** for each published module the compiled jar, the sources jar, the documentation jar and the
  descriptor are produced in the local repository
- **AND** the descriptor carries the name, description, home, licence, developer and source repository
  entries
- **AND** nothing is uploaded anywhere

#### Scenario: Example modules are not published

- **WHEN** the local publishing command runs
- **THEN** no artifact is produced for the sample, tutorial or benchmark module

#### Scenario: A tag that does not match the declared version publishes nothing

- **WHEN** the release path is triggered by a tag whose version differs from the one the build declares
- **THEN** it fails before publishing any artifact
- **AND** its output names both versions

#### Scenario: A released version is not republished

- **WHEN** the release path is re-run for a tag whose version has already been released
- **THEN** the already released artifacts are unchanged

#### Scenario: Documented coordinates name the published group id and version

- **WHEN** a documentation page states a coordinate with a literal version
- **THEN** the build fails unless that coordinate's group id is the published one and its version is the
  one the build publishes
