## 1. Artefacts a consumer can resolve

- [ ] 1.1 Add a Maven repository under the root build directory to each published module's publishing
  configuration in the root `build.gradle.kts` (design D2). Verify the publishing task writes the three
  modules' jars and descriptors there, that nothing is signed, and that `~/.m2` is untouched.
- [ ] 1.2 Create the `kdiff-integration` module — included in `settings.gradle.kts`, absent from
  `publishedModules`, so it gets no `explicitApi()`, no ABI dump, no Dokka and no publication (design
  D1). Verify `./gradlew :kdiff-integration:test` runs and that `./gradlew publishToMavenLocal`
  produces nothing for it.
- [ ] 1.3 Add the TestKit harness: a consumer project written to the test's temporary directory, with
  the settings and build script of design D3, its plugin versions and its kdiff version passed in from
  the build as system properties. Point TestKit at the build's own Gradle user home so the dependency
  cache is shared. Verify the harness runs a build and returns its output.

## 2. The consumer build is a gate

- [ ] 2.1 Write the consumer spec's fixtures: one `@Diffable` data class with a `@DiffKey` list of a
  nested `@Diffable`, and a test in the consumer project that diffs two instances, asserts the changes,
  applies the diff and asserts the value.
- [ ] 2.2 Assert *a consumer build compiles and runs against the published artifacts*: the harness
  build succeeds with only the three coordinates declared. Verify it fails when a coordinate is
  removed, so the gate is shown to be load-bearing rather than merely green.
- [ ] 2.3 Assert *generation dependencies stay off the consumer's compile classpath* by whichever of
  design D7's two forms proves less coupled to Gradle's API — resolving the compile classpath, or a
  source file naming KotlinPoet failing to compile. Record which was chosen and why in the design.
- [ ] 2.4 Wire the spec's task to the publishing task of 1.1 and into `check`. Verify `./gradlew check`
  from a clean tree runs it, and record how much longer `check` takes (design D6) — the figure goes in
  `CONTRIBUTING.md` in task 5.2.

## 3. Regeneration, in both directions

- [ ] 3.1 Add the incremental spec on the same harness: build, edit, build again. Cover the three rows
  of design D4 — an inline `value class` redeclared as an ordinary class gives the diagnostic a clean
  build gives; an edited annotated class has its generated file rewritten; an unrelated annotated
  class's generated file is byte-identical to the first build's.
- [ ] 3.2 Verify the third assertion actually fails when the processor declares its dependencies as
  aggregating, by making that change locally, watching the test fail, and reverting it. A test for a
  narrowness claim is worth nothing until it has been seen to fail.

## 4. The messages the documentation quotes

- [ ] 4.1 Add the messages spec beside `DocumentationSamplesSpec` in `kdiff-sample`, implementing design
  D5: join concatenated string literals in the sources, split each quoted message at its `<…>`
  placeholders, and require every fragment of substance to appear. The failure names the page, the
  message and the missing fragment.
- [ ] 4.2 Add `kdiff-processor/src/main` to the test task's declared inputs beside
  `kdiff-runtime/src/main`, so editing a diagnostic re-runs the check rather than leaving it up to date.
- [ ] 4.3 Verify the gate on the failure it exists for: reword one diagnostic in `DiffProcessor.kt`,
  watch the check fail naming that message, and revert. Then run it against `docs/errors.md` as it
  stands and fix whatever it finds — the page has never been checked, so expect it to find something.

## 5. Documented versions, and the documentation about all of this

- [ ] 5.1 Extend `DocumentationSamplesSpec` with design D8: `kdiff.kotlinVersion` and `kdiff.kspVersion`
  passed from the catalog, and a check that every documented Kotlin and KSP plugin version with a
  literal value names them. Verify it fails on `README.md` with a version bumped by hand.
- [ ] 5.2 `CONTRIBUTING.md`: delete the paragraph beginning *"**Nothing checks that page against
  them.**"*, describe the three new gates and how to run each on its own, state the constraint design
  D5 creates — a diagnostic is one string expression of adjacent literals and interpolations — and
  record what `check` now costs.
- [ ] 5.3 `CLAUDE.md`: remove the sentence in *Docs are checked prose* saying nothing in `check`
  compares a quoted message to the string the processor emits, and add `kdiff-integration` to the
  module graph with the one line saying what it is for.

## 6. Continuous integration

- [ ] 6.1 Add the matrix of design D7 to `.github/workflows/ci.yml`: `ubuntu-latest` and
  `windows-latest` on JDK 21, and one `ubuntu-latest` job on the later JDK settled in the design's open
  question. Every job runs `./gradlew check` and nothing else.
- [ ] 6.2 Verify the Windows job passes, and fix what it finds. The build's `File.separator` filters and
  the documentation spec's root resolution have never run there, so treat a first-run failure as the
  gate working rather than as a reason to drop the row.

## 7. Done

- [ ] 7.1 Run `./gradlew check` and verify it passes. Report any failure verbatim.
