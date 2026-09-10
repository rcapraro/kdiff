## ADDED Requirements

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
