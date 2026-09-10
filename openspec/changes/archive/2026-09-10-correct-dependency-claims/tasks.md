## 1. The authoritative description

- [x] 1.1 Correct the `kdiff-annotations` line in `openspec/config.yaml` to state that the module
  depends on no kdiff module and no third-party library, carrying the Kotlin stdlib like every Kotlin
  module (design D1). First because it is the project description fed as context into every OpenSpec
  command, and the only one of the seven that is not under `docs/`. Verify by running
  `openspec instructions proposal --change correct-dependency-claims --json` and reading the
  `context` field back: it must no longer say "No dependencies".

## 2. Published source

- [x] 2.1 Correct the trailing clause of the `UNLIMITED_DEPTH` KDoc in
  `kdiff-annotations/src/main/kotlin/io/github/kdiff/annotations/Tracking.kt`, leaving the argument
  for why the constant is declared twice word for word (design D3). Verify the sentence still reads as
  one clause explaining why neither module may depend on the other.
- [x] 2.2 Correct the matching clause in
  `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/TrackScope.kt` so the two blocks describe
  both modules identically (design D3). Verify by reading the two KDoc blocks side by side: the only
  difference between them should be which module is "this one".
- [x] 2.3 Change the comment in `kdiff-annotations/build.gradle.kts` to say *declared* dependencies,
  keeping the reason it gives (design D4). Verify the file still contains no `dependencies { }` block.
- [x] 2.4 Confirm no Kotlin declaration changed: run `./gradlew :kdiff-annotations:apiCheck
  :kdiff-runtime:apiCheck` and verify both pass with no ABI dump edit, since KDoc is not part of the
  dump and `updateKotlinAbi` must not be needed.

## 3. Contributor and reference documentation

- [x] 3.1 Correct the `kdiff-annotations` bullet in `CLAUDE.md`'s *Module graph* section, keeping the
  neighbouring `kdiff-runtime` and `kdiff-processor` bullets untouched (design D1). Verify the three
  bullets still read as one consistent set.
- [x] 3.2 Replace the annotations box's second label line in `docs/architecture.md` with
  `Kotlin stdlib only`, padded so the `published | not published` divider stays at column 51
  (design D2). Verify with
  `awk 'NR>=10 && NR<=34 {print NR, length($0), substr($0, 51, 1)}' docs/architecture.md` that every
  diagram line still carries `|` at column 51 and that no line's length changed.
- [x] 3.3 Correct the `kdiff-annotations` clause in `docs/api-stability.md` §6, leaving the
  `kotlin-reflect`, `kdiff-runtime` and `kdiff-processor` clauses of that paragraph as they are
  (design D1). Verify §6 still states the platform, the absence of a reflection library, and the
  three modules' dependency positions.
- [x] 3.4 Read `docs/annotations.md`'s account of the duplicated `UNLIMITED_DEPTH` and confirm it
  needs no edit, since it speaks only of `kdiff-runtime` and is already accurate (design D5). Verify
  it does not contradict the wording settled in 2.1 and 2.2; if it does, correct it and say so.

## 4. Agreement across the seven

- [x] 4.1 Search the working tree for the old claim — `no dependencies at all`, `depends on nothing at
  all`, `No dependencies` — and verify every remaining hit is either inside
  `openspec/changes/archive/` (history, not to be edited) or a statement that is true as written.
- [x] 4.2 Verify the corrected prose against the artifact rather than against itself: run
  `./gradlew :kdiff-annotations:dependencies --configuration runtimeClasspath` and
  `:kdiff-runtime:dependencies --configuration runtimeClasspath`, and confirm each resolves the Kotlin
  stdlib and nothing beyond what the new wording claims.

## 5. Done

- [x] 5.1 Run `./gradlew check` and verify it passes, with no ABI dump change and no docs spec failure
  — `DocumentationSamplesSpec` reads every page edited here, so a broken marker block or a coordinate
  disturbed in passing fails it.
