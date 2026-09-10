# Contributing

## The build

```bash
./gradlew check                                   # the definition of done for any change
./gradlew :kdiff-runtime:test                     # one module
./gradlew :kdiff-runtime:test --tests '*SelectSpec*'   # one spec class
./gradlew :kdiff-sample:kspKotlin --rerun-tasks   # regenerate the sample's differs

./gradlew ktlintFormat                            # fix formatting
./gradlew updateKotlinAbi                         # record a deliberate public API change
./gradlew dokkaGenerate                           # build the reference documentation
./gradlew :kdiff-benchmarks:jmh                   # measure, when a change touches a hot path
```

`check` is still the one command CI runs. Seven gates hang off it, and each names its own fix:

- **ktlint**, with the formatting stated in `.editorconfig`. `ktlintFormat` fixes what it reports.
- **detekt**, configured in `config/detekt/detekt.yml`. That file holds *only* this project's
  deviations from the defaults, one entry per rule with the reason it is off — so if a rule is in your
  way, argue with it there rather than adding a `@Suppress` at the site.
- **`allWarningsAsErrors`** on every Kotlin compilation.
- **ABI validation** for the three published modules, with the dump checked in under `<module>/api/`.
  Adding or removing a public declaration fails `check` until you run `updateKotlinAbi`, which is the
  point: a removal should be a line in a diff a reviewer reads, not a surprise for a consumer.

  The dump is a detector, not a description of the promise: it also records `@PublishedApi internal`
  members and the accessors a `value class` lowers to, because the platform requires them to exist.
  [`docs/api-stability.md`](docs/api-stability.md#1-what-is-recorded) says which entries those are, and
  is where a promise about the public surface is written down — so a change to what kdiff guarantees
  belongs on that page, not only in the dump.

- **A consumer build against the published artefacts**, in `kdiff-integration`. The three modules are
  published to a repository under `build/` — never `~/.m2`, and never the network — and a Gradle
  TestKit build declaring only the three coordinates compiles an annotated class and runs its differ.
  This is the only thing in the repository that reads a generated descriptor: `kdiff-sample` consumes
  the *project*, so a broken POM is invisible to it. Run it alone with
  `./gradlew :kdiff-integration:test`.
- **Regeneration, in both directions**, in the same module: a build, an edit, a second build. That a
  changed declaration regenerates its readers, and that an unrelated annotated class is left alone.
- **The documentation's quoted messages**, described under `docs/errors.md` below.

The first two are what the second Gradle invocation costs — the messages gate runs inside
`:kdiff-sample:test`, in the same invocation as everything else. `./gradlew check --rerun-tasks` goes
from about 50s to about 1m02s on a warm cache, so budget **~12s**, of which about 4s is the Dokka
javadoc jar that publishing to `build/repo` now pulls in. If that ever stops being worth it, the
answer is to argue with the requirement in `openspec/specs/build-quality-gates/`, not to move the gate
into a CI step — a gate CI runs and `check` does not is what that requirement exists to prevent.

detekt is pinned to a `2.0.0-alpha`. That is deliberate — it is the release built against this
project's Kotlin, where stable 1.23.8 still embeds Kotlin 2.0.21 — and the version is pinned exactly
so no upgrade arrives on its own. If an alpha ever breaks the build, the escape is `ignoreFailures`
for one release with a note here saying so, not a downgrade.

Generated code lands in `kdiff-sample/build/generated/ksp/main/kotlin/demo/`. Read it after any
processor change — it is how you check what the processor actually did.

Requires a JDK; the build uses a JVM 21 toolchain and Gradle will provision one if needed. kdiff
targets JVM 21 deliberately — a consumer compiles at that target as well as running on it, because
several entry points are `inline`. Do not lower it to widen adoption without a change that says so.

Tests are Kotest on the JUnit platform, and test names are sentences — so `--tests` filters on the
spec *class*, not the test name.

## This repository is spec-driven

Behaviour lives in `openspec/specs/` before it lives in code. Work through the workflow rather than
editing specs directly:

1. **Propose** — `/opsx:propose` creates a change with a proposal, delta specs, a design and tasks.
2. **Apply** — `/opsx:apply` works through the tasks.
3. **Archive** — `/opsx:archive` merges the delta specs into the main specs and files the change under
   `openspec/changes/archive/`.

Editing a main spec outside a change bypasses the workflow and loses the record of why the behaviour
changed. Completed changes in the archive are the best available explanation of any given design
decision.

`openspec/config.yaml` is authoritative for the tech stack, the per-artifact rules and the conventions
this project holds itself to. Read it before planning a change; it is not duplicated here.

## Things that will fail review

**Breaking a module's dependency rules.** `kdiff-annotations` has no dependencies. `kdiff-runtime`
depends only on the Kotlin standard library. `kdiff-processor` is compile-time only for consumers.
These are what let someone take the runtime without a code generator — see
[docs/architecture.md](docs/architecture.md).

**Adding a `Change` variant.** The vocabulary is sealed and closed so a `when` over it can be
exhaustive. A sixth variant grows a branch in every consumer's exhaustive `when`, which makes it a
breaking change, not an addition.

**Generating an algorithm instead of calling one.** New logic goes in `kdiff-runtime`; the processor
emits a call to it. A fix then ships as a dependency bump rather than a recompile of every consumer.

**A silent fallback.** An unsupported shape is a `KSPLogger.error` reported at the offending
declaration. Never an exception, never a quiet default — an annotation that does nothing is worse than
one that fails the build.

**Bumping a version inside a feature change.** Kotlin, KSP, KotlinPoet, Gradle and Kotest versions
live in `gradle/libs.versions.toml`, and moving one is its own change.

## Tests

Every runtime change ships with a Kotest spec. Every processor change ships with a compile-testing
spec that compiles a Kotlin snippet in-test and asserts on the exit code, the diagnostics (message
*and* location) and the generated code — preferring to compile and *invoke* the generated code over
asserting on its text.

When generated output changes, update `kdiff-sample` and re-run its tests in the same change.

## Documentation

Samples in `README.md` and `docs/` are checked against their sources. A fenced Kotlin block carries
either a marker naming the file it mirrors:

```
<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```

or `<!-- illustrative -->` if it is consumer-side code this repository has no file for. Blocks with a
`from:` marker are verified by `DocumentationSamplesSpec` in `kdiff-sample`, which fails the build if
a sample drifts from the code it claims to show. An unmarked block also fails the build, so the escape
hatch stays visible.

Two page-level conventions follow from that, because a page a reader copies from is the last place an
unchecked block belongs:

- **A recipe in `docs/how-to.md` is a test, not prose.** Add it to `RecipesSpec` in `kdiff-sample`
  first, then cite it with a `from:` marker. Reaching for `illustrative` there would put the code a
  reader copies first back outside the harness, which is the drift this spec exists to catch.
- **`docs/errors.md` quotes message text verbatim**, copied from the source rather than written from
  memory. The messages live in `DiffProcessor.kt` (compile-time diagnostics), `Dsl.kt`,
  `TrackScope.kt` and `Route.kt` (construction-time `require`s), and `Patcher.kt` and `Errors.kt`
  (failure sentences and the declared exceptions).

  `DocumentationMessagesSpec` in `kdiff-sample` checks the page against them, and those files are
  declared inputs of that task, so rewording a message re-runs it. The check cuts each source message
  into the runs of text the code states literally — splitting at `${…}` and `$name` — and asks whether
  the page's quotation contains them in order. It reads the source as ground truth, since a page
  expands more than the `<…>` placeholders it marks: `$part` becomes *elements* or *values*, and
  `$MAX_DESCENT` becomes *512*.

  **So a diagnostic is one string expression of adjacent literals and interpolations.** Concatenation
  across lines is fine and expected — the check rejoins it. One assembled with `buildString`, with
  `trimIndent`, or from a `when` returning halves arrives in pieces and is checked as pieces, which
  weakens the gate without telling you.

The same spec pins the published coordinates. Every `io.github.rcapraro:kdiff-<module>:<version>` on a
documentation page — this one included, in a fenced block, a shell snippet or plain prose — must name
the group id and the version the build publishes, which `kdiff-sample` passes its tests as
`kdiff.group` and `kdiff.version`. So an install snippet cannot survive a release that leaves it
behind, and the version belongs in as few places as possible: the release notes are generated from
`CHANGELOG.md`, and the README links to them rather than restating which release is current. A
coordinate whose version is a variable — `kdiff-runtime:$kdiffVersion` — is skipped, since what it
resolves to is not written on the page and cannot go stale.

## Commits and pull requests

Run `./gradlew check` before opening a pull request. Say which modules changed and whether the
generated API surface changed — a change consumers must recompile or adapt to is breaking, and should
say so.

## Releasing

A release is a tag. Pushing `vX.Y.Z` runs `.github/workflows/publish.yml`, which checks the tag against
`version` in the root `build.gradle.kts`, runs `./gradlew check`, publishes the three published modules
to Maven Central as one deployment, and creates or updates the GitHub release from that version's
`CHANGELOG.md` section. So the changelog entry is written before the tag, and it is the only place a
version is described.

Before tagging, inspect what a release will produce, without publishing anything:

```bash
./gradlew publishToMavenLocal
```

Each published module lands under `~/.m2/repository/io/github/rcapraro/<module>/<version>/` as the jar,
a `-sources.jar` holding the module's Kotlin sources, a `-javadoc.jar` holding Dokka's HTML, and a
`.pom` carrying the name, description, url, licence, developer and scm entries Central validates.
Nothing is signed locally — the signing key lives only in the workflow.

Central takes a few minutes to sync after the workflow finishes. Confirm the release resolved:

```bash
curl -sI https://repo1.maven.org/maven2/io/github/rcapraro/kdiff-runtime/0.7.0/kdiff-runtime-0.7.0.pom
```

### One-time setup, and how to rotate it

Four repository secrets drive the publish step. None of their values appears anywhere in this
repository, and none is written to disk by the workflow.

| secret | what it is | rotate by |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token name | generating a new token on the Portal's account page and replacing both halves |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password | as above — the two are issued together |
| `SIGNING_IN_MEMORY_KEY` | the ASCII-armoured PGP private key | generating a new key pair, publishing its public half, replacing the secret |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | that key's passphrase | replacing it alongside the key |

The namespace is verified once, not per release, and `io.github.rcapraro` already is — it is the
namespace `kalidation` publishes under, carried over when Sonatype migrated OSSRH to the Portal. A
namespace is verified by proving ownership of the matching GitHub account, in the way the Portal asks
for at the time. The group id has to be a namespace the maintainer can verify; the packages stay
`io.github.kdiff.*`, because Central has no opinion on package names and renaming packages would break
every consumer's imports for nothing.

The signing key is also the one `kalidation` is signed with — RSA 3072,
`EE270165E7B4128473CD14A3C593BBA5D2F9A1DC`, no expiry — whose public half is already on
`keyserver.ubuntu.com` and `pgp.mit.edu`, two of the three keyservers Central consults. The primary key
is the signing key, so `signingInMemoryKeyId` is not needed. If the private half is lost, artifacts
already published stay valid — signatures are checked against the public key, which is already out
there. Generate a new pair and publish its public half before the next release.

The first tagged release after a namespace or key change is the one worth watching: the Portal rejects a
bundle it will not accept rather than publishing a broken one, and it rejects a re-upload of a version
it has already released, which is why re-running a tag through `workflow_dispatch` is safe.
