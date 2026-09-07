package io.github.kdiff.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/** How a single property is compared, decided once at resolution time (design D3). */
internal sealed interface Comparison {
    /** Files this comparison reads, which must become originating dependencies of the output. */
    val sources: List<KSFile>

    data object ByValue : Comparison {
        override val sources: List<KSFile> = emptyList()
    }

    /**
     * [canPatch] is false for a `@DiffWith` target that only implements `Differ`: the library can
     * compare that property but cannot reconstruct it, so its changes are reported instead.
     */
    data class Nested(val differ: ClassName, override val sources: List<KSFile>, val canPatch: Boolean = true) :
        Comparison

    data class KeyedList(val differ: ClassName, val keyProperty: String, override val sources: List<KSFile>) :
        Comparison

    data class PositionalList(val differ: ClassName?, override val sources: List<KSFile>) : Comparison

    data object AsSet : Comparison {
        override val sources: List<KSFile> = emptyList()
    }

    data class AsMap(val valueDiffer: ClassName?, override val sources: List<KSFile>) : Comparison
}

/** One property of a declared tracking scope, mirroring the runtime's `TrackedField`. */
internal data class TrackedProperty(val name: String, val depth: Int)

internal fun KSClassDeclaration.isDiffable(): Boolean = hasAnnotation(DIFFABLE)

internal fun KSClassDeclaration.isTrackable(): Boolean = hasAnnotation(TRACKABLE)

/** The `depth` argument of [fqName] on this declaration, or null when the annotation is absent. */
internal fun KSAnnotated.depthArgument(fqName: String): Int? {
    val annotation = annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == fqName
    } ?: return null
    return annotation.arguments.firstOrNull { it.name?.asString() == "depth" }?.value as? Int
        ?: UNLIMITED_DEPTH
}

internal fun KSAnnotated.hasAnnotation(fqName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqName }

internal fun KSClassDeclaration.isSealedType(): Boolean = Modifier.SEALED in modifiers

internal fun KSClassDeclaration.isDataClass(): Boolean = classKind == ClassKind.CLASS && Modifier.DATA in modifiers

/** The single `@DiffKey` property of a type, or null. Multiplicity is diagnosed separately. */
internal fun KSClassDeclaration.keyProperty(): KSPropertyDeclaration? =
    getDeclaredProperties().firstOrNull { it.hasAnnotation(DIFF_KEY) }

internal fun KSType.declarationOrNull(): KSClassDeclaration? = declaration as? KSClassDeclaration

private val VALUE_TYPES = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char",
    "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
    "kotlin.Float", "kotlin.Double",
    "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
)

internal fun KSType.isValueType(): Boolean {
    val declaration = declarationOrNull() ?: return false
    if (declaration.qualifiedName?.asString() in VALUE_TYPES) return true
    return declaration.classKind == ClassKind.ENUM_CLASS
}

private fun KSType.qualified(): String? = declarationOrNull()?.qualifiedName?.asString()

internal fun KSType.isList(): Boolean =
    qualified() in setOf("kotlin.collections.List", "kotlin.collections.MutableList")

internal fun KSType.isSet(): Boolean = qualified() in setOf("kotlin.collections.Set", "kotlin.collections.MutableSet")

internal fun KSType.isMap(): Boolean = qualified() in setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

internal fun KSType.typeArgumentAt(index: Int): KSType? = arguments.getOrNull(index)?.type?.resolve()

internal fun KSClassDeclaration.file(): List<KSFile> = listOfNotNull(containingFile)

internal fun KSClassDeclaration.differClassName(): ClassName = differName(toClassName())
