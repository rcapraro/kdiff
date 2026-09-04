# Tasks — add-kdiff-skeleton

Ordering follows design.md D9: resolve the pinned version set before writing code, and surface the
kctfork/Kotlin skew risk (Risks) as early as possible.

## 1. Build skeleton

- [x] 1.1 Add the Gradle wrapper at 9.7.1 and verify `./gradlew --version` reports Gradle 9.7.1 and a JVM 21 toolchain is available
- [x] 1.2 Add `gradle/libs.versions.toml` pinning exactly the charter's versions (Kotlin 2.4.10, KSP 2.3.11, KotlinPoet 2.3.0, Kotest 6.2.4, kctfork 0.13.0) and verify `./gradlew help` succeeds with the catalog parsed
- [x] 1.3 Add `settings.gradle.kts` declaring the four modules and `dependencyResolutionManagement`, and verify `./gradlew projects` lists `kdiff-annotations`, `kdiff-runtime`, `kdiff-processor` and `kdiff-sample`
- [x] 1.4 Add the root `build.gradle.kts` applying the Kotlin JVM plugin, JVM toolchain 21, and `explicitApi()` on the three published modules (not `kdiff-sample`), and verify `./gradlew build` succeeds on the empty modules
- [x] 1.5 Resolve every pinned dependency ahead of writing code — add them to the modules per design D1 and verify `./gradlew dependencies` resolves with no version conflict; per the charter, if a coordinate is wrong or incompatible, report it and stop rather than bumping a version in this change

## 2. Annotations and runtime

- [x] 2.1 Add `@Diffable` in `kdiff-annotations` as a marker annotation targeting classes with binary retention, KDoc'd, no parameters, and verify `kdiff-annotations` has no dependencies beyond the Kotlin stdlib in `./gradlew :kdiff-annotations:dependencies`
- [x] 2.2 Add `Differ<T>`, `Diff`, `Change`, `FieldPath` and `Segment` to `kdiff-runtime` exactly at the shape in design D3, with KDoc on each public declaration, and verify `./gradlew :kdiff-runtime:build` succeeds under `explicitApi()`
- [x] 2.3 Add a Kotest spec for `kdiff-runtime` covering the spec's "A diff result reports an ordered list of changes" requirement — `isEmpty` agrees with an empty change list, and two `Diff` values built from the same changes compare equal — and verify `./gradlew :kdiff-runtime:test` passes
- [x] 2.4 Verify `kdiff-runtime` declares no dependency on `kdiff-processor` or `kdiff-annotations` by inspecting `./gradlew :kdiff-runtime:dependencies` (design D1)

## 3. Processor

- [x] 3.1 Add the `SymbolProcessorProvider` and `SymbolProcessor` skeleton in `kdiff-processor` with `symbol-processing-api`, `kotlinpoet-ksp` and `kdiff-annotations` on its compile classpath, and verify `./gradlew :kdiff-processor:build` succeeds
- [x] 3.2 Register the provider in `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` and verify the resource is present in the built jar via `./gradlew :kdiff-processor:jar` and listing the archive
- [x] 3.3 **Risk-first** — add the simplest possible kctfork spec (compile a snippet with no annotations, assert `exitCode == OK`) and verify `./gradlew :kdiff-processor:test` passes; this is the earliest signal on the kctfork 0.13.0 / Kotlin 2.4.10 skew flagged in design Risks. If it fails fatally, stop and report before continuing — the fallback is covering the processor through `kdiff-sample` only, and raising kctfork as its own change
- [x] 3.4 Implement generation: for each `@Diffable` data class emit `<Type>Diff.kt` into the annotated class's package containing `object <Type>Differ : Differ<Type>` whose `diff` returns `Diff(emptyList())`, matching the generated-file sample in design.md exactly
- [x] 3.5 Declare `Dependencies(aggregating = false, containingFile)` on every generated file per design D7, and generate through `CodeGenerator` using `FileSpec.writeTo(codeGenerator, dependencies)`
- [x] 3.6 Return unvalidated symbols from `process()` for retry in a later round and leave `finish()` free of state per design D6, and verify no processor state survives a round in review
- [x] 3.7 Add a kctfork spec for the spec's "A differ is generated for every annotated data class" requirement — compile an annotated data class, then invoke the generated `PersonDiffer.diff` reflectively on the compiled classloader and assert the result reports no changes (compile and invoke, do not assert on generated text) — and verify `./gradlew :kdiff-processor:test` passes
- [x] 3.8 Add a kctfork spec asserting an unannotated class produces no `<Type>Diff.kt`, and that two annotated classes each produce their own differ, and verify `./gradlew :kdiff-processor:test` passes

## 4. Diagnostic

- [x] 4.1 Reject non-data-class targets with `logger.error(message, declaration)` naming the declaration and stating that `@Diffable` requires a data class, skipping that declaration without throwing, per design D5
- [x] 4.2 Add a kctfork spec covering every rejection scenario in the spec — regular class, interface, object, enum class — asserting `exitCode` is a compilation error, the message names the declaration, and the diagnostic's location is the offending declaration, and verify `./gradlew :kdiff-processor:test` passes
- [x] 4.3 Add a kctfork spec for the mixed case — one valid data class plus one annotated interface — asserting the build fails and the failure is attributable to the interface, and verify `./gradlew :kdiff-processor:test` passes

## 5. Sample integration

- [x] 5.1 Configure `kdiff-sample` to apply the `com.google.devtools.ksp` plugin, depend on `kdiff-annotations` and `kdiff-runtime` as `implementation`, and on `kdiff-processor` through the `ksp` configuration only, and verify `./gradlew :kdiff-sample:build` succeeds
- [x] 5.2 Add `@Diffable data class Person(val id: String, val name: String)` to `kdiff-sample` and verify the generated `PersonDiff.kt` appears under the module's KSP generated-sources directory after `./gradlew :kdiff-sample:kspKotlin`
- [x] 5.3 Add a Kotest spec in `kdiff-sample` named for what it proves — that the generated differ is callable from consumer code — asserting `PersonDiffer.diff(a, b)` returns a `Diff` that reports no changes for both equal and differing instances, per the spec's temporary baseline, and verify `./gradlew :kdiff-sample:test` passes
- [x] 5.4 Verify the spec's "Generating differs adds no runtime dependency beyond the result types" requirement: `./gradlew :kdiff-sample:dependencies --configuration runtimeClasspath` contains `kdiff-annotations` and `kdiff-runtime` and contains neither `kdiff-processor`, `symbol-processing-api` nor KotlinPoet

## 6. Verification

- [x] 6.1 Review the generated `PersonDiff.kt` against the sample in design.md and confirm it is code a human would be happy to read — correct package, no redundant imports, no processor-generated noise
- [x] 6.2 Verify incremental correctness by hand: run `./gradlew :kdiff-sample:build`, add an unrelated field to `Person`, rebuild, and confirm the regenerated differ reflects the edit and the build is not fully invalidated (design D7)
- [x] 6.3 Run `./gradlew check` from a clean state (`./gradlew clean check`) and confirm it passes; report any failure verbatim
