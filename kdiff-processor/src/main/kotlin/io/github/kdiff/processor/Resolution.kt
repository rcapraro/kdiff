package io.github.kdiff.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
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

    /**
     * [sources] is empty for a built-in value and carries the declaring file for a `@DiffAsValue`
     * type, so removing that annotation regenerates every class that read it (design D7).
     */
    data class ByValue(override val sources: List<KSFile> = emptyList()) : Comparison

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

/** Whether the property's patch helper returns a rebuilt collection, and so cannot itself take a null. */
internal fun Comparison.rebuildsACollection(): Boolean = when (this) {
    is Comparison.ByValue, is Comparison.Nested -> false
    is Comparison.KeyedList, is Comparison.PositionalList, Comparison.AsSet, is Comparison.AsMap -> true
}

/** One property of a declared tracking scope, mirroring the runtime's `TrackedField`. */
internal data class TrackedProperty(val name: String, val depth: Int)

internal fun KSClassDeclaration.isDiffable(): Boolean = hasAnnotation(DIFFABLE)

/**
 * Every property carrying [annotation] whose declaring class is not `@Diffable`.
 *
 * Such an annotation is never read, because nothing is generated for that class — so each of these is a
 * declaration that silently configures nothing, and is reported.
 */
internal fun Resolver.propertiesOutsideDiffable(annotation: String): List<KSPropertyDeclaration> =
    getSymbolsWithAnnotation(annotation)
        .filterIsInstance<KSPropertyDeclaration>()
        .filterNot { (it.parentDeclaration as? KSClassDeclaration)?.isDiffable() == true }
        .toList()

/**
 * Every declaration the search for a property's comparison annotations consults, nearest first.
 *
 * The property itself, then each property it overrides, stopping at the first that carries one —
 * nothing above that is read. When none does, the whole chain has been consulted, and what was read is
 * the *absence*: adding an annotation anywhere in it changes how this property is compared.
 *
 * Which makes this, not [comparisonDeclaration], the list whose files have to join the generated
 * file's originating set. It is strictly longer in the two cases that matter: a property excluded by an
 * inherited `@DiffIgnore` produces no comparison to carry its files at all, and a property annotated
 * nowhere would record nothing, leaving an annotation added to its parent unable to reach it
 * (design D3).
 */
internal fun KSPropertyDeclaration.comparisonChain(): List<KSPropertyDeclaration> = buildList {
    var declaration: KSPropertyDeclaration? = this@comparisonChain
    while (declaration != null) {
        add(declaration)
        if (declaration.hasComparisonAnnotation()) break
        declaration = declaration.findOverridee()
    }
}

/**
 * The declaration a property's comparison annotations are read from.
 *
 * Itself when it carries one, and the nearest property it overrides that does otherwise — because
 * Kotlin puts none of an overridden declaration's annotations on the `override`, so a sealed parent's
 * `@DiffAsValue` reaches the subclass's differ only if it is looked for. A subclass delegating to its
 * own differ and a subclass swap comparing the parent's properties would otherwise disagree about how
 * one property is compared (design D2).
 *
 * All three comparison annotations are taken from *one* declaration rather than merged: an override
 * carrying `@DiffIgnore` over a parent's `@DiffAsValue` means the override's word is final, not a
 * property that is both. Choosing the declaration is what keeps a conflict the processor rejects on one
 * declaration from being reachable through two.
 *
 * Falls back to the property itself when nothing in the chain carries one. Compare by identity, never
 * by whether this has a containing file: a declaration KSP read from a class file has none, so a
 * property inheriting an annotation across a module boundary is still an inherited one.
 */
internal fun KSPropertyDeclaration.comparisonDeclaration(): KSPropertyDeclaration =
    comparisonChain().last().takeIf { it.hasComparisonAnnotation() } ?: this

/** The files [comparisonChain] consulted, which must become originating dependencies (design D3). */
internal fun KSPropertyDeclaration.comparisonSources(): List<KSFile> = comparisonChain().flatMap { it.file() }

private fun KSPropertyDeclaration.hasComparisonAnnotation(): Boolean =
    hasAnnotation(DIFF_WITH) || hasAnnotation(DIFF_AS_VALUE) || hasAnnotation(DIFF_IGNORE)

/** The class a property is declared in, for a message that names it. */
internal fun KSPropertyDeclaration.ownerName(): String =
    (parentDeclaration as? KSClassDeclaration)?.simpleName?.asString() ?: "the class declaring it"

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

/**
 * An `object`, `data object` included.
 *
 * A singleton subclass of a sealed type has no state, so it needs no differ of its own and carries no
 * annotation: its branch reports nothing and applies by returning the instance.
 */
internal fun KSClassDeclaration.isSingleton(): Boolean = classKind == ClassKind.OBJECT

/** The single `@DiffKey` property of a type, or null. Multiplicity is diagnosed separately. */
internal fun KSClassDeclaration.keyProperty(): KSPropertyDeclaration? =
    getDeclaredProperties().firstOrNull { it.hasAnnotation(DIFF_KEY) }

internal fun KSType.declarationOrNull(): KSClassDeclaration? = declaration as? KSClassDeclaration

/**
 * The types kdiff compares by equality without being told to (design D2).
 *
 * The criterion, stated in `docs/annotations.md` so membership can be read rather than remembered:
 * immutable, equal by value, from the JDK or the Kotlin standard library. `java.util.Date` is
 * mutable and `java.io.File` names something rather than being a value, so neither is here.
 * `kotlin.time.Duration` is a `value class` and needs no entry.
 */
private val VALUE_TYPES = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char",
    "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
    "kotlin.Float", "kotlin.Double",
    "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
    "kotlin.time.Instant", "kotlin.uuid.Uuid",
    "java.math.BigDecimal", "java.math.BigInteger",
    "java.util.UUID", "java.util.Currency", "java.util.Locale",
    "java.net.URI",
    "java.time.Instant", "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
    "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.ZonedDateTime",
    "java.time.Duration", "java.time.Period",
    "java.time.Year", "java.time.YearMonth", "java.time.MonthDay",
    "java.time.ZoneId", "java.time.ZoneOffset",
)

/**
 * A value by what it is, rather than by what an author declared about it.
 *
 * Separate from [isValueType] because `@DiffAsValue` on one of these is rejected as having no
 * effect, which cannot be asked of a test the annotation itself satisfies.
 */
internal fun KSClassDeclaration.isIntrinsicValueType(): Boolean =
    qualifiedName?.asString() in VALUE_TYPES || isDeclaredValueShape()

/**
 * A value by the shape its own author gave it, rather than by being on [VALUE_TYPES].
 *
 * Split from [isIntrinsicValueType] because only these have a declaration the author can edit: a
 * `BigDecimal` is a value because the JDK says so, in a file no build can change, while an enum and a
 * `value class` are declarations in the module being compiled. The difference is what
 * [declaredValueSources] turns into an originating dependency.
 */
private fun KSClassDeclaration.isDeclaredValueShape(): Boolean =
    classKind == ClassKind.ENUM_CLASS || isInlineValueClass()

/** Both faces of a `value class`: the modifier when read from source, `@JvmInline` once compiled. */
private fun KSClassDeclaration.isInlineValueClass(): Boolean = Modifier.VALUE in modifiers || hasAnnotation(JVM_INLINE)

internal fun KSType.isValueType(): Boolean {
    val declaration = declarationOrNull() ?: return false
    return declaration.isIntrinsicValueType() || declaration.hasAnnotation(DIFF_AS_VALUE)
}

/**
 * The file the declaration a value classification came off lives in, empty when there is none.
 *
 * Read wherever a value classification came off another type's declaration, so that declaration's
 * file joins the originating set of what depended on it (design D7).
 *
 * Every way a type can be a value *because its author said so* belongs here, not only `@DiffAsValue`:
 * an enum and a `value class` are edited in the same build as the class comparing them, and a
 * classification that outlived its declaration is a generated differ an incremental build keeps and a
 * clean build refuses. A type on [VALUE_TYPES] has no such file, and would be no use if it did.
 */
internal fun KSType.declaredValueSources(): List<KSFile> = declarationOrNull()
    ?.takeIf { it.isDeclaredValueShape() || it.hasAnnotation(DIFF_AS_VALUE) }
    ?.file()
    .orEmpty()

private fun KSType.qualified(): String? = declarationOrNull()?.qualifiedName?.asString()

internal fun KSType.isList(): Boolean =
    qualified() in setOf("kotlin.collections.List", "kotlin.collections.MutableList")

internal fun KSType.isSet(): Boolean = qualified() in setOf("kotlin.collections.Set", "kotlin.collections.MutableSet")

internal fun KSType.isMap(): Boolean = qualified() in setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

internal fun KSType.typeArgumentAt(index: Int): KSType? = arguments.getOrNull(index)?.type?.resolve()

internal fun KSDeclaration.file(): List<KSFile> = listOfNotNull(containingFile)

internal fun KSClassDeclaration.differClassName(): ClassName = differName(toClassName())
