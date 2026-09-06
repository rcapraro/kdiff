# Contributing

## The build

```bash
./gradlew check                                   # the definition of done for any change
./gradlew :kdiff-runtime:test                     # one module
./gradlew :kdiff-runtime:test --tests '*SelectSpec*'   # one spec class
./gradlew :kdiff-sample:kspKotlin --rerun-tasks   # regenerate the sample's differs
```

Generated code lands in `kdiff-sample/build/generated/ksp/main/kotlin/demo/`. Read it after any
processor change — it is how you check what the processor actually did.

Requires a JDK; the build uses a JVM 21 toolchain and Gradle will provision one if needed.

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

The same spec pins the published version. Every `io.github.kdiff:<module>:<version>` on a documentation
page — this one included, in a fenced block, a shell snippet or plain prose — must name the version the
build publishes,
which `kdiff-sample` passes its tests as `kdiff.version`. So an install snippet cannot survive a
release that leaves it behind, and the version belongs in as few places as possible: the release notes
are generated from `CHANGELOG.md`, and the README links to them rather than restating which release is
current. A coordinate whose version is a variable — `kdiff-runtime:$kdiffVersion` — is skipped, since
what it resolves to is not written on the page and cannot go stale.

## Commits and pull requests

Run `./gradlew check` before opening a pull request. Say which modules changed and whether the
generated API surface changed — a change consumers must recompile or adapt to is breaking, and should
say so.
