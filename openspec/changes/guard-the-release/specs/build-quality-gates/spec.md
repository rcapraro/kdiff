## ADDED Requirements

### Requirement: Every message the documentation quotes is the message the code emits

The documentation quotes the library's compile-time diagnostics, its construction-time refusals and its
failure sentences verbatim, so that a reader holding an error can search for its text and find the page
that explains it. That is only true while the two agree.

The build SHALL check every message the documentation quotes against the sources that emit it, and
SHALL fail when a quoted message is not emitted by any of them. A message reworded in the code and not
on the page SHALL therefore fail the same single command that defines done, rather than leaving the
page quietly wrong.

A quoted message SHALL be matched by its literal text, ignoring the placeholders standing in for the
names the author's own code supplies, so that a message assembled from a template is matched by the
parts of it a reader would recognise.

The check SHALL be reachable from the aggregate command, SHALL declare the documentation and the
checked sources as inputs so that editing either re-runs it, and its failure SHALL name the quoted
message and the page it appears on.

#### Scenario: A reworded message fails the build

- **WHEN** a message is reworded in the source that emits it and the page quoting it is not updated
- **THEN** the check fails
- **AND** its output names the quoted message and the page it appears on

#### Scenario: A quoted message with placeholders is matched by its literal parts

- **WHEN** a quoted message stands in for a declaration name with a placeholder
- **THEN** the check matches it on the literal text around the placeholder
- **AND** does not fail merely because the name differs

#### Scenario: Editing either side re-runs the check

- **WHEN** either the page or a source emitting a message it quotes is edited
- **THEN** the check runs again rather than being treated as up to date

#### Scenario: A message quoted for a page that no source emits fails

- **WHEN** a page quotes a message no source emits, whether because it was invented or because it
  outlived the code
- **THEN** the check fails, naming it

## MODIFIED Requirements

### Requirement: One command runs every gate

`./gradlew check` SHALL remain the single command that decides whether the repository is in a good
state. Every gate this capability defines SHALL be reachable from it, and continuous integration SHALL
run nothing beyond it.

A gate SHALL be runnable on its own for a developer fixing one thing, and each gate SHALL name, in its
failure output, the command that records or fixes what it found where such a command exists.

Continuous integration SHALL run that one command in more than one environment: on more than one
operating system, so that a gate depending on how paths are spelled is exercised where they are spelled
differently, and on a runtime later than the one the build targets, so that a toolchain moving under the
build is a failure the repository sees before a contributor does. Running the command in several
environments SHALL NOT be read as running a second command.

The command SHALL remain runnable offline against an already-populated dependency cache. A gate that
needs a published artefact SHALL obtain it from a repository the build itself produces, and SHALL NOT
write to the developer's own local repository or require credentials.

#### Scenario: The aggregate command covers every gate

- **WHEN** a developer runs the aggregate check on a repository violating any one gate
- **THEN** the command fails
- **AND** the output identifies which gate failed and in which module

#### Scenario: Continuous integration adds no second command

- **WHEN** continuous integration runs for a commit
- **THEN** each of its environments runs the aggregate check and nothing else

#### Scenario: The gate runs on more than one operating system

- **WHEN** continuous integration runs for a commit
- **THEN** the aggregate check runs on more than one operating system
- **AND** a gate that fails on only one of them fails the commit

#### Scenario: A failing gate says how to fix itself

- **WHEN** a gate that has a recording or formatting command fails
- **THEN** its output names that command

#### Scenario: No gate writes outside the build directory

- **WHEN** the aggregate check runs
- **THEN** no gate publishes to the developer's local repository
- **AND** no gate requires a credential

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

That a consumer can actually use what is published SHALL be checked by the build rather than by
inspection. A build declaring the three published coordinates and nothing else of the library's SHALL
be compiled and run as part of the aggregate command: it SHALL annotate a class, compile, and execute
the generated code. Its dependencies SHALL be resolved as published artefacts, through the descriptors
the release produces, so that a descriptor that omits what a consumer needs fails the build. The
compile classpath of such a consumer SHALL carry nothing the code-generation module depends on.

The group id SHALL be one whose namespace the maintainer can verify with the repository, and package
names SHALL NOT change on account of it.

A documentation page SHALL NOT state a version the build does not declare. This SHALL hold for the
published coordinates and for the versions of the build tools a consumer must apply to use the library,
since both are text a reader copies and neither is checked by anything else.

#### Scenario: A consumer resolves the library with no repository configuration

- **WHEN** a build declares only the central repository and the three published coordinates at a
  released version
- **THEN** the annotations, runtime and processor modules resolve
- **AND** no credential is required

#### Scenario: A consumer build compiles and runs against the published artifacts

- **WHEN** the aggregate command runs
- **THEN** a consumer build declaring only the three published coordinates, resolved as artefacts,
  compiles an annotated class and executes its generated differ
- **AND** the aggregate command fails when that build fails to resolve, to compile, or to produce the
  expected diff

#### Scenario: A descriptor missing what a consumer needs fails the build

- **WHEN** a published module's descriptor omits a dependency the consumer requires
- **THEN** the consumer build fails to resolve or to compile
- **AND** the aggregate command fails with it

#### Scenario: Generation dependencies stay off the consumer's compile classpath

- **WHEN** the consumer build's compile classpath is examined
- **THEN** it carries the annotations and runtime modules
- **AND** it carries neither the code-generation library the processor uses nor the symbol-processing
  API

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

#### Scenario: A documented build-tool version names the version the build uses

- **WHEN** a documentation page tells a consumer which version of a build plugin to apply
- **THEN** the build fails unless that version is the one the build itself uses
