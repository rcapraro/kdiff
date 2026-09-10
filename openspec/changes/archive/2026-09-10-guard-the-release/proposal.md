## Why

Four promises this repository makes are defended by a human remembering to check them, and
`CONTRIBUTING.md` says so in as many words about one of them: *"**Nothing checks that page against
them.** … a reworded message can leave `check` green and the page wrong."* A `1.0.0` is the point at
which a promise nobody checks becomes a promise nobody can withdraw.

```
  what is promised                             what checks it today
  -------------------------------------------  ------------------------------------------------
  the three coordinates resolve and compile    a maintainer running publishToMavenLocal and
  a consumer's build                           reading ~/.m2 by eye, before a tag
  errors.md quotes every message verbatim      nothing -- stated as a known hole in CONTRIBUTING
  a generated differ is regenerated when a     nothing -- kctfork compiles once, so the
  declaration it read changes                  incremental claim is untestable there
  the build behaves the same everywhere        one job, one JDK, one operating system
```

The sample and the tutorial are excellent consumers of the *project*, and they exercise nothing about
the artefacts: a `project(":kdiff-processor")` dependency never reads a POM. The one failure a library
cannot recover from — a release whose coordinates do not work — is the one no test covers.

## What Changes

**A consumer build compiles against the published artefacts, inside `./gradlew check`.** A new,
unpublished `kdiff-integration` module drives a Gradle TestKit build that declares only the three
coordinates, resolved from a build-local file repository the publishing tasks write to. It annotates a
class, compiles, and runs the generated differ. This exercises what the sample cannot: the generated
POMs, that three coordinates are sufficient, that the processor works when resolved as an artefact
rather than as a project, and that nothing drags KotlinPoet or the KSP API onto a consumer's compile
classpath.

**Every message `docs/errors.md` quotes is checked against the source that emits it.** A spec beside
`DocumentationSamplesSpec` reads the page's quoted messages, splits each on its `<Type>`/`<prop>`
placeholders, and asserts the literal fragments still appear in the sources `CONTRIBUTING.md` already
names. The known hole closes, and `CONTRIBUTING.md` loses the paragraph admitting it.

**The incremental-regeneration promise gets a test.** The same TestKit harness builds a consumer
twice, edits a declaration between the runs, and asserts which generated files were rewritten and which
were not. This is the claim `docs/architecture.md` makes about non-aggregating dependencies, and the
one 0.7.0 fixed a real bug in — found by reasoning, because nothing could catch it.

**The toolchain versions the documentation names are pinned to the build.** `README.md`'s install
snippet hand-maintains `kotlin("jvm") version "2.4.10"` and the KSP plugin version, in the same fenced
block whose coordinates are already checked. They are the same class of thing a reader copies, and they
are the half that can go stale silently.

**Not in scope**

- Cross-Kotlin-version testing. The consumer build exercises the pair kdiff declares; a matrix over
  neighbouring Kotlin minors is a separate change with its own resolution problems.
- **A continuous-integration matrix over operating systems or JDKs.** Proposed, implemented, and then
  removed: it tests whether the *build* runs elsewhere, not whether the *library* works — nothing in
  the published jars is OS-dependent — and no contributor on another platform exists to benefit.
  Design D7 records the reasoning.
- Publishing anything from `check`. The consumer build resolves from a repository inside `build/`;
  nothing touches `~/.m2` or the network beyond the dependencies Gradle already resolves.
- Benchmark regression gating. JMH stays a tool a maintainer runs, not a gate.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `build-quality-gates`:
  - MODIFIED *Published artifacts are publicly resolvable and carry their sources and documentation* —
    that a consumer resolving only the published coordinates compiles and runs generated code is
    checked by the build rather than by inspection, and the toolchain versions the documentation names
    are checked like its coordinates.
  - MODIFIED *One command runs every gate* — it stays runnable without the network and without writing
    outside the build directory, which is what lets a gate needing a published artefact hang off it.
  - ADDED *Every message the documentation quotes is the message the code emits*.
- `diff-generation`:
  - MODIFIED *A generated differ is regenerated when a declaration it read changes* — the requirement
    gains scenarios stating what a second build rewrites and what it leaves alone, which is what makes
    it checkable at all.

## Impact

- **Modules**: a new `kdiff-integration` module — not published, not on any consumer's path, holding the
  TestKit harness and its two specs. `kdiff-sample` gains one spec and two declared task inputs. The
  root build wires the file-repository publication and the new module into `check`.
- **Public API**: unchanged, in every module. No dump moves.
- **Generated API surface**: unchanged. Nothing about generation changes; the incremental test observes
  what the processor already does.
- **Annotation semantics**: unchanged.
- **Build**: `./gradlew check` grows two test tasks and a publication to `build/`. Expect it to get
  slower — a TestKit build is a second Gradle invocation — which is the cost the design has to bound.
- **CI**: unchanged. It runs `./gradlew check` on one Ubuntu image at JDK 21, as it did, and picks up
  the three new gates because they hang off that command.
- **Docs**: `CONTRIBUTING.md` loses the paragraph admitting the `errors.md` hole and gains the new
  gates; `CLAUDE.md`'s *Docs are checked prose* section loses the sentence saying nothing in `check`
  compares a quoted message to the string the processor emits.
- **Dependencies**: `gradleTestKit()` from the Gradle distribution. No third-party dependency, no
  version bumped.
