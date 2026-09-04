## Why

Nothing exists yet: kdiff has a charter but no build, no modules and no processor. Before any
diff semantics can be specified, the compile-time pipeline has to exist and be provably wired —
an annotation that a processor sees, code it generates into the right package, and a consumer
module that compiles against that generated code.

Landing that skeleton on its own keeps the interesting change (the diff algorithm) reviewable as
an algorithm, instead of burying it under Gradle wiring and KSP registration that can only fail
in ways unrelated to diffing.

## What Changes

- New Gradle multi-project build: `settings.gradle.kts`, root `build.gradle.kts`, and a version
  catalog at `gradle/libs.versions.toml` pinning the stack the project charter fixes. JVM
  toolchain 21; `explicitApi()` on every published module.
- New module **`kdiff-annotations`**: the `@Diffable` annotation, marker-only for now. It targets
  classes, is retained in binary form, and carries no parameters yet.
- New module **`kdiff-runtime`**: the types generated code targets — `Differ<T>`, `Diff`,
  `Change`, `FieldPath`. This change introduces them at the smallest shape that lets generated
  code compile and report "no differences"; the change that follows fills the change model out.
- New module **`kdiff-processor`**: a `SymbolProcessorProvider` and `SymbolProcessor` that, for
  every `@Diffable` data class, generates `<Type>Diff.kt` into the annotated class's package
  containing a `<Type>Differ` object implementing `Differ<Type>`. The generated differ compares
  no fields yet and always reports no differences.
- One compile-time diagnostic: `@Diffable` on anything that is not a data class is a compile
  error reported against the offending declaration. Unsupported shapes fail the build; they are
  never silently skipped.
- New module **`kdiff-sample`**: applies the KSP plugin, annotates a data class, and calls the
  generated differ from a test. It is the end-to-end proof that the pipeline works from a
  consumer's point of view.
- Tests: a processor spec that compiles Kotlin snippets in-process and asserts both the success
  path (the file is generated and the generated code runs) and the diagnostic path (message and
  location); a runtime unit spec; and the sample's own test.

Not breaking: no API exists to break. Every module and every public type here is new.

The generated API surface is introduced by this change, so it is the baseline that later changes
are measured against. `@Diffable` gains semantics here for the first time — annotating a data
class starts producing a differ for it, which no existing code depends on.

Deliberately **not** in this change, and specified next: `@DiffKey` and `@DiffIgnore`, actual
field comparison, nested `@Diffable` types, collections, maps, enums, nullability rules, sealed
hierarchies, the hand-written differ DSL, the tree view and the text renderer. Patch application
(`apply(before, changes) -> after`) is a third change after those.

## Capabilities

### New Capabilities

- `diff-generation`: what annotating a Kotlin class produces — the differ that becomes available
  for it, the result that differ returns, and the compile errors that reject an annotation the
  library cannot honour. This change establishes the capability with its trivial baseline
  behaviour; the following change grows it into real field-by-field comparison.

### Modified Capabilities

None. This is the first change in the project; `openspec/specs/` is empty.

## Impact

- **Affected modules**: all four — `kdiff-annotations`, `kdiff-runtime`, `kdiff-processor`,
  `kdiff-sample`. All are created here.
- **Affected APIs**: `@Diffable`, `Differ<T>`, `Diff`, `Change`, `FieldPath` and the generated
  `<Type>Differ` object are all new public API. `Diff`, `Change` and `FieldPath` are expected to
  grow in the next change; that growth is additive and planned.
- **Dependencies**: Kotlin, KSP, KotlinPoet, Kotest and the kotlin-compile-testing fork enter the
  build for the first time, at the versions the charter pins. The processor is a compile-time-only
  dependency for consumers, applied through the `ksp` configuration — it must never reach a
  consumer's runtime classpath.
- **Build**: `./gradlew check` becomes meaningful for the first time and is the definition of done.
- **Risk**: the version set is pinned but has never been resolved together in this project. The
  first build task doubles as the check that these coordinates exist and are mutually compatible;
  a mismatch surfaces there, before any processor logic is written.
