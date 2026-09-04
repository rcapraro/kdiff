## 1. License

- [x] 1.1 Add `LICENSE` at the repository root: MIT, copyright the project author, current year —
  verify the file names the licence in its first line so GitHub's licence detection picks it up

## 2. Publishing

- [x] 2.1 Set `group = "io.github.kdiff"` and `version = "0.1.0"` in the root `build.gradle.kts`, and
  inside the existing `if (project.name in publishedModules)` branch apply `maven-publish` and
  register a publication from the `java` component (design D1) — verify
  `./gradlew publishToMavenLocal` succeeds and produces artifacts for `kdiff-annotations`,
  `kdiff-runtime` and `kdiff-processor` only, with no `kdiff-sample` publication
- [x] 2.2 Add POM metadata to that single publication block — name, description, MIT licence, SCM
  pointing at `github.com/rcapraro/kdiff`, developer — verify each of the three generated POMs under
  `~/.m2/repository/io/github/kdiff/` carries the licence and SCM, and that all three agree
- [x] 2.3 Confirm the published POMs preserve `diff-generation`'s requirement that a consumer's
  runtime classpath excludes the generator (design D2): read the generated
  `kdiff-processor-0.1.0.pom` and `kdiff-runtime-0.1.0.pom` and verify the processor is not a
  transitive runtime dependency of the runtime or annotations modules — report what each POM
  actually declares rather than asserting it in the abstract
- [x] 2.4 Confirm `gradle/libs.versions.toml` is unchanged and no plugin or dependency version was
  added — verify by diffing the version catalog and the four build scripts

## 3. Sample verification harness

- [x] 3.1 Pass the repository root to `kdiff-sample`'s tests via
  `systemProperty("kdiff.repoRoot", rootProject.projectDir.absolutePath)` (design D4) — verify the
  property is readable from a test run through Gradle
- [x] 3.2 Add `kdiff-sample/src/test/kotlin/demo/DocumentationSamplesSpec.kt`: walk `README.md` and
  `docs/*.md`, and for every fenced Kotlin block preceded by a `<!-- from: <path> -->` marker assert
  the named file exists and the block's distinctive lines appear in it (design D3) — verify the spec
  fails when a marker names a missing file and when a marked block cites a line absent from its
  source, then passes on correct input
- [x] 3.3 Assert unmarked Kotlin blocks appear only on the pages where illustrative snippets are
  expected, so the escape hatch cannot spread silently (design D3) — verify the spec fails if an
  unmarked block is added to a page not on that list

## 4. README

- [x] 4.1 Write `README.md`: what kdiff is and the problem it solves, a quickstart that goes from
  `@Diffable` to a printed diff, and links to the `docs/` pages — verify every sample carries a
  `from:` marker and `./gradlew :kdiff-sample:test` passes
- [x] 4.2 Write the install section, leading with the GitHub Packages authentication requirement,
  showing the `repositories` block with `credentials` in full, naming the `read:packages` scope, and
  reading the token from an environment variable rather than inlining it (design D6) — verify the
  dependency snippet declares `kdiff-processor` through `ksp(...)` and never `implementation(...)`
  (design D2)
- [x] 4.3 List every place the version string `0.1.0` appears, and keep it to the fewest possible
  (design, Risks) — verify by grepping the docs for the version and reporting the list in the task
  result so the next release knows what to update

## 5. Guides

- [x] 5.1 Write `docs/diffing.md`: `@Diffable`, the `Change` vocabulary and why it is closed,
  `FieldPath`/`Segment` and how paths read, and one worked sample each for values, nullables, nested
  types, keyed lists, positional lists, sets, maps and sealed types — verify each sample is marked
  and traced to `kdiff-sample`, and `./gradlew :kdiff-sample:test` passes
- [x] 5.2 Write `docs/patching.md`: applying a diff, `PatchResult`/`isClean`, `PatchFailure` and the
  cases that produce one (non-constructor property, compare-only `@DiffWith`), and the round-trip
  property — verify the samples trace to `RoundTripSpec` and `Model.kt`
- [x] 5.3 Write `docs/tracking.md`: `Tracker`, `update`/`reset`, both callback shapes with the
  change-to-sides table, `@Trackable`/`@TrackIgnore`/`@TrackDepth`, scopes, and the depth rule
  including that element identity does not consume depth and that depth filters rather than rolls up
  — verify the samples trace to `OrderTrackingSpec` and `Model.kt`
- [x] 5.4 Write `docs/hand-written.md`: `differ { }` and `trackScope { }`, when each is needed, that a
  hand-written one is indistinguishable from a generated one, and that patching has no builder
  because it must construct — verify the samples trace to `Model.kt`'s `MoneyDiffer` and
  `WeightDiffer`, including the compare-only case that is unpatchable yet trackable
- [x] 5.5 Write `docs/annotations.md`: a reference table of all seven annotations with targets,
  parameters, defaults and what each rejects at compile time — verify every compile error listed
  matches a scenario in `openspec/specs/diff-generation/spec.md` and a test in `DiagnosticSpec`
- [x] 5.6 Write `docs/architecture.md` for a human audience: the four modules and their dependency
  rules, the "does it construct?" axis that decides which capabilities get a DSL builder, why runtime
  helpers are not generated, and the `UNLIMITED_DEPTH` duplication — verify it does not contradict
  `CLAUDE.md` and does not simply restate it (design D7)

## 6. CI and badges

- [x] 6.1 Add `.github/workflows/ci.yml` running `./gradlew check` on push and pull request with JDK
  21 and Gradle caching — verify the workflow's JDK matches the `jvmToolchain(21)` in the root build
- [x] 6.2 Add CI, licence and Kotlin badges to the top of `README.md` (design D5, badges last) —
  verify the CI badge URL matches the workflow file's actual path and the licence badge says MIT
- [x] 6.3 Write `CONTRIBUTING.md`: the OpenSpec propose/apply/archive workflow, the module dependency
  rules, and that `./gradlew check` gates every change — verify it points at `openspec/config.yaml`
  as authoritative rather than duplicating it

## 7. Verification

- [x] 7.1 Confirm no file under any `src/main` changed — verify by listing every file this change
  touched and checking the only source file is the new `DocumentationSamplesSpec.kt`
- [x] 7.2 Confirm the generated sample sources are byte-identical to before this change, proving no
  behaviour moved — verify by regenerating with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` and
  comparing `kdiff-sample/build/generated/ksp/main/kotlin/demo/*.kt`
- [x] 7.3 Verify every internal link in `README.md` and `docs/*.md` resolves to a file that exists
- [x] 7.4 Run `./gradlew check` and report the result verbatim
