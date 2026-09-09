package io.github.kdiff.processor

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.CodeBlock

/*
 * Turning a resolved comparison into the code that performs it.
 *
 * Split from `DiffProcessor` along the seam it already had: everything here is a pure function from a
 * property and its [Comparison] to a `CodeBlock`, reaching neither the logger nor the code generator.
 * The processor drives KSP, reports diagnostics and assembles the `TypeSpec`; these decide what one
 * line of the generated body says.
 *
 * Every emitter produces a call into `kdiff-runtime` rather than the logic itself, so an algorithm fix
 * ships as a dependency bump instead of a recompile of every consumer.
 */

/** One runtime call listing the scope, so resolution and matching stay in kdiff-runtime. */
internal fun trackScopeInitializer(scope: List<TrackedProperty>): CodeBlock {
    if (scope.isEmpty()) return CodeBlock.of("%M()", TRACK_SCOPE_OF)

    return CodeBlock.builder()
        .add("%M(\n", TRACK_SCOPE_OF)
        .apply {
            scope.forEach { add("%T(%S, %L),\n", TRACKED_FIELD, it.name, it.depth) }
        }
        .add(")")
        .build()
}

/** The comparison of one property, as the single runtime call the generated `diff` body makes for it. */
internal fun emit(property: KSPropertyDeclaration, comparison: Comparison): CodeBlock {
    val name = property.simpleName.asString()
    val nullable = property.type.resolve().isMarkedNullable

    return when (comparison) {
        is Comparison.ByValue ->
            CodeBlock.of("%M(%S, before.%N, after.%N)\n", COMPARE_VALUE, name, name, name)

        is Comparison.Nested -> if (nullable) {
            CodeBlock.of(
                "%M(%S, before.%N, after.%N, %T)\n",
                COMPARE_NESTED_NULLABLE,
                name,
                name,
                name,
                comparison.differ,
            )
        } else {
            CodeBlock.of(
                "%M(%S, before.%N, after.%N, %T)\n",
                COMPARE_NESTED,
                name,
                name,
                name,
                comparison.differ,
            )
        }

        is Comparison.KeyedList -> CodeBlock.of(
            "%M(%S, %S, before.%N, after.%N, %T) { it.%N }\n",
            COMPARE_KEYED_LIST,
            name,
            comparison.keyProperty,
            name,
            name,
            comparison.differ,
            comparison.keyProperty,
        )

        // A nullable collection needs no branch here: the compare helpers take either side, which is
        // why the generated `diff` line is the same for `List<E>` and `List<E>?`.
        is Comparison.PositionalList -> if (comparison.differ == null) {
            CodeBlock.of(
                "%M(%S, before.%N, after.%N, null)\n",
                COMPARE_POSITIONAL_LIST,
                name,
                name,
                name,
            )
        } else {
            CodeBlock.of(
                "%M(%S, before.%N, after.%N, %T)\n",
                COMPARE_POSITIONAL_LIST,
                name,
                name,
                name,
                comparison.differ,
            )
        }

        is Comparison.AsSet ->
            CodeBlock.of("%M(%S, before.%N, after.%N)\n", COMPARE_SET, name, name, name)

        is Comparison.AsMap -> if (comparison.valueDiffer == null) {
            CodeBlock.of("%M(%S, before.%N, after.%N, null)\n", COMPARE_MAP, name, name, name)
        } else {
            CodeBlock.of(
                "%M(%S, before.%N, after.%N, %T)\n",
                COMPARE_MAP,
                name,
                name,
                name,
                comparison.valueDiffer,
            )
        }
    }
}

/** The reconstruction of one property, as the `val <name>Patched = …` line the generated `apply` binds. */
internal fun emitPatch(
    property: KSPropertyDeclaration,
    comparison: Comparison,
    isConstructorParameter: Boolean,
): CodeBlock {
    val name = property.simpleName.asString()
    val patched = "${name}Patched"
    val changes = CodeBlock.of("grouped.forProperty(%S)", name)

    if (!isConstructorParameter) {
        return CodeBlock.of(
            "val %N = %M(before.%N, %L, %S)\n",
            patched,
            NOT_CONSTRUCTOR_PROPERTY,
            name,
            changes,
            name,
        )
    }

    val nullable = property.type.resolve().isMarkedNullable
    val source = CodeBlock.of("before.%N", name)

    // A nullable collection is the one shape whose own helper cannot take the null: that helper
    // *returns* the rebuilt collection, so widening it the way the compare helpers were widened
    // would change the return type every caller reads. `patchNullable` states the rule around it
    // instead. A value is nullable already, and a nested property has its own nullable helper.
    if (nullable && comparison.rebuildsACollection()) {
        return CodeBlock.builder()
            .add("val %N = %M(%L, %L) { value, own ->\n", patched, PATCH_NULLABLE, source, changes)
            .indent()
            .add(patchCall(comparison, CodeBlock.of("value"), CodeBlock.of("own"), name, nullable))
            .add("\n")
            .unindent()
            .add("}\n")
            .build()
    }

    return CodeBlock.builder()
        .add("val %N = ", patched)
        .add(patchCall(comparison, source, changes, name, nullable))
        .add("\n")
        .build()
}

/**
 * The call that rebuilds one property, over the source and change-list expressions it is given.
 *
 * Both are taken as expressions rather than built from the property name, because a nullable
 * collection needs the same call twice over different ones: once directly, and once as the body of
 * the lambda `patchNullable` hands the present value to.
 */
private fun patchCall(
    comparison: Comparison,
    source: CodeBlock,
    changes: CodeBlock,
    name: String,
    nullable: Boolean,
): CodeBlock = when (comparison) {
    is Comparison.ByValue -> CodeBlock.of("%M(%L, %L)", PATCH_VALUE, source, changes)

    is Comparison.Nested -> when {
        !comparison.canPatch -> CodeBlock.of("%M(%L, %L, %S)", UNPATCHABLE, source, changes, name)
        nullable -> CodeBlock.of("%M(%L, %L, %T)", PATCH_NESTED_NULLABLE, source, changes, comparison.differ)
        else -> CodeBlock.of("%M(%L, %L, %T)", PATCH_NESTED, source, changes, comparison.differ)
    }

    is Comparison.KeyedList -> CodeBlock.of(
        "%M(%L, %L, %T, %S, %S) { it.%N }",
        PATCH_KEYED_LIST,
        source,
        changes,
        comparison.differ,
        name,
        comparison.keyProperty,
        comparison.keyProperty,
    )

    is Comparison.PositionalList -> if (comparison.differ == null) {
        CodeBlock.of("%M(%L, %L, null)", PATCH_POSITIONAL_LIST, source, changes)
    } else {
        CodeBlock.of("%M(%L, %L, %T)", PATCH_POSITIONAL_LIST, source, changes, comparison.differ)
    }

    Comparison.AsSet -> CodeBlock.of("%M(%L, %L)", PATCH_SET, source, changes)

    is Comparison.AsMap -> if (comparison.valueDiffer == null) {
        CodeBlock.of("%M(%L, %L, null)", PATCH_MAP, source, changes)
    } else {
        CodeBlock.of("%M(%L, %L, %T)", PATCH_MAP, source, changes, comparison.valueDiffer)
    }
}
