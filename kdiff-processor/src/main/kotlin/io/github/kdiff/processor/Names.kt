package io.github.kdiff.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

internal const val DIFFABLE = "io.github.kdiff.annotations.Diffable"
internal const val DIFF_KEY = "io.github.kdiff.annotations.DiffKey"
internal const val DIFF_IGNORE = "io.github.kdiff.annotations.DiffIgnore"
internal const val DIFF_WITH = "io.github.kdiff.annotations.DiffWith"
internal const val DIFF_AS_VALUE = "io.github.kdiff.annotations.DiffAsValue"

/**
 * How a `value class` is recognised once it has been compiled: `Modifier.VALUE` is surfaced only for a
 * declaration KSP reads from *source*, so a value class from another module or a library carries no
 * modifier here. `@JvmInline` is mandatory on a Kotlin/JVM `value class` and has binary retention, so
 * it survives into the class file and is the same test for both.
 */
internal const val JVM_INLINE = "kotlin.jvm.JvmInline"

internal const val TRACKABLE = "io.github.kdiff.annotations.Trackable"
internal const val TRACK_IGNORE = "io.github.kdiff.annotations.TrackIgnore"
internal const val TRACK_DEPTH = "io.github.kdiff.annotations.TrackDepth"

/** Mirrors `UNLIMITED_DEPTH` in both annotation and runtime modules, which cannot depend on this one. */
internal const val UNLIMITED_DEPTH = -1

private const val RUNTIME = "io.github.kdiff.runtime"

internal val DIFFER = ClassName(RUNTIME, "Differ")
internal val DIFF = ClassName(RUNTIME, "Diff")
internal val CHANGE = ClassName(RUNTIME, "Change")
internal val TYPE_CHANGED = ClassName(RUNTIME, "TypeChanged")
internal val FIELD_PATH = ClassName(RUNTIME, "FieldPath")

internal val COMPARE_VALUE = MemberName(RUNTIME, "compareValue")
internal val COMPARE_NESTED = MemberName(RUNTIME, "compareNested")
internal val COMPARE_NESTED_NULLABLE = MemberName(RUNTIME, "compareNestedNullable")
internal val COMPARE_KEYED_LIST = MemberName(RUNTIME, "compareKeyedList")
internal val COMPARE_POSITIONAL_LIST = MemberName(RUNTIME, "comparePositionalList")
internal val COMPARE_SET = MemberName(RUNTIME, "compareSet")
internal val COMPARE_MAP = MemberName(RUNTIME, "compareMap")

internal val BUILD_LIST = MemberName("kotlin.collections", "buildList")

internal val PATCHER = ClassName(RUNTIME, "Patcher")
internal val PATCH_RESULT = ClassName(RUNTIME, "PatchResult")
internal val PATCH_FAILURE = ClassName(RUNTIME, "PatchFailure")

internal val GROUP_BY_PROPERTY = MemberName(RUNTIME, "groupByProperty")
internal val UNMATCHED_FAILURES = MemberName(RUNTIME, "unmatchedFailures")
internal val PATCH_VALUE = MemberName(RUNTIME, "patchValue")
internal val PATCH_NESTED = MemberName(RUNTIME, "patchNested")
internal val PATCH_NESTED_NULLABLE = MemberName(RUNTIME, "patchNestedNullable")
internal val PATCH_NULLABLE = MemberName(RUNTIME, "patchNullable")
internal val PATCH_SINGLETON = MemberName(RUNTIME, "patchSingleton")
internal val PATCH_KEYED_LIST = MemberName(RUNTIME, "patchKeyedList")
internal val PATCH_POSITIONAL_LIST = MemberName(RUNTIME, "patchPositionalList")
internal val PATCH_SET = MemberName(RUNTIME, "patchSet")
internal val PATCH_MAP = MemberName(RUNTIME, "patchMap")
internal val UNPATCHABLE = MemberName(RUNTIME, "unpatchable")
internal val NOT_CONSTRUCTOR_PROPERTY = MemberName(RUNTIME, "notConstructorProperty")

internal val TRACKED = ClassName(RUNTIME, "Tracked")
internal val TRACK_SCOPE = ClassName(RUNTIME, "TrackScope")
internal val TRACKED_FIELD = ClassName(RUNTIME, "TrackedField")
internal val TRACK_SCOPE_OF = MemberName(RUNTIME, "trackScopeOf")

/** The differ generated for a type, named so the processor can reference it from another file. */
internal fun differName(type: ClassName): ClassName =
    ClassName(type.packageName, "${type.simpleNames.joinToString("")}Differ")
