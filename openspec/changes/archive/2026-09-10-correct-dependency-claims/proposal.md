## Why

Seven places in this repository tell a reader that `kdiff-annotations` has **no dependencies at all**.
The artifact published to Maven Central says otherwise:

```
runtimeClasspath - Runtime classpath of 'main'.
\--- org.jetbrains.kotlin:kotlin-stdlib:2.4.10
     \--- org.jetbrains:annotations:13.0
```

A consumer of `io.github.rcapraro:kdiff-annotations:0.7.0` resolves two jars, not zero, and the
published POM declares the first of them. The claim is true of what the module's build file *declares*
— it declares nothing — and false of what a consumer *resolves*. The prose conflates the two.

Three things make this worth a change rather than a passing correction:

- **Two of the seven are KDoc in published source.** `UNLIMITED_DEPTH` in `kdiff-annotations` and its
  twin in `kdiff-runtime` both carry the sentence, so it now ships inside the sources jar and the
  Dokka HTML on Maven Central. The claim reaches consumers, not just contributors.
- **One of them is `openspec/config.yaml`**, which is the authoritative project description fed as
  context into every OpenSpec planning command. An inaccurate line there is repeated into the planning
  of every future change.
- **The right words already exist in the repository.** `docs/architecture.md` states the rule
  correctly one paragraph below the diagram that states it wrongly: *"`kdiff-annotations` and
  `kdiff-runtime` do not depend on each other, and neither depends on the processor."* That is the
  load-bearing rule. "No dependencies at all" is a stronger claim that was never the rule and is not
  true.

The main specs are already accurate and need no change: `diff-generation` requires that generated code
"references only the annotated class, **the Kotlin standard library**, and the result types", and
`change-tracking` speaks of "the annotations and the result types on the runtime classpath". The
behaviour contracts got this right; only the prose drifted.

## What Changes

**The wording changes; nothing the build produces changes.** Each of the seven statements is corrected
to say what is true — that neither published module depends on another kdiff module or on any
third-party library, and that both carry the Kotlin standard library like every Kotlin module does.

The seven:

| where | what it says now |
|---|---|
| `openspec/config.yaml` | `kdiff-annotations`: … **No dependencies.** |
| `CLAUDE.md` | `kdiff-annotations` — annotations only, **no dependencies at all**. |
| `docs/architecture.md` | the module diagram's cell: `no dependencies at all` |
| `docs/api-stability.md` §6 | `kdiff-annotations` **depends on nothing at all**; `kdiff-runtime` depends only on the Kotlin standard library |
| `kdiff-annotations/…/Tracking.kt` KDoc | this one **carries no dependencies at all**, and `kdiff-runtime` carries none but the Kotlin standard library |
| `kdiff-runtime/…/TrackScope.kt` KDoc | that one **carries no dependencies at all**, and this one carries none but the Kotlin standard library |
| `kdiff-annotations/build.gradle.kts` | `// No dependencies: the annotations module must stay consumable on its own.` |

**The dependency rules themselves are unchanged and are what the new wording expresses**: no kdiff
module depends on another except the processor on the annotations; `kdiff-runtime` pulls in no
third-party library; the processor never reaches a consumer's runtime classpath. Those are the
load-bearing facts, and they survive intact.

**Not in scope**

- **Changing what the artifact depends on.** Making the claim true by declaring the stdlib
  `compileOnly` for `kdiff-annotations` would change what a consumer resolves, for a module whose
  annotations are `BINARY` retention and whose consumers are Kotlin projects that already have the
  stdlib. That is a distribution decision, not a documentation fix, and it is not proposed here.
- **A check that keeps the claim honest.** Nothing in `check` compares a documented dependency claim
  against a resolved classpath, which is why this drifted unnoticed across seven releases — the same
  gap `DocumentationSamplesSpec` closes for coordinates. Deliberately left out after weighing it: it
  would carry a `build-quality-gates` requirement and a new test. Worth its own change.
- **The `UNLIMITED_DEPTH` duplication.** The two declarations and the reason for them stay exactly as
  they are. Only the parenthetical describing what each module carries is corrected; the sentence
  explaining *why* the constant is declared twice is untouched and still correct.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. No requirement changes: this corrects prose that describes the build, and the main specs
already state the dependency rules accurately. `skip_specs: true` is set in `.openspec.yaml`
accordingly, rather than inventing a requirement to satisfy validation.

## Impact

- **Modules**: `kdiff-annotations` (one KDoc block, one build-file comment) and `kdiff-runtime` (one
  KDoc block). No Kotlin declaration, signature or body changes in either.
- **Public API**: unchanged. KDoc is not part of the ABI dump, so `api/` does not move and
  `updateKotlinAbi` is not run.
- **Generated API surface**: unchanged. The processor is not touched.
- **Annotation semantics**: unchanged. `@Diffable`, `@Trackable` and `UNLIMITED_DEPTH` behave
  identically; `UNLIMITED_DEPTH` keeps its value of `-1` in both modules.
- **Published artifacts**: the next release's sources jar and Dokka HTML carry the corrected KDoc.
  Nothing about resolution, coordinates or signing changes.
- **Not breaking.** No consumer recompiles or adapts.
- **Documentation**: `openspec/config.yaml`, `CLAUDE.md`, `docs/architecture.md`,
  `docs/api-stability.md`. `docs/annotations.md`'s account of the same constant is already accurate
  and is checked for consistency rather than edited.
