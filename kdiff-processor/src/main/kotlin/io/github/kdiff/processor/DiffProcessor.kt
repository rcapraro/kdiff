package io.github.kdiff.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

public class DiffProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotated = resolver.getSymbolsWithAnnotation(DIFFABLE).toList()
        val (ready, deferred) = annotated.partition { it.validate() }

        reportTrackingWithoutDiffable(resolver)

        ready.filterIsInstance<KSClassDeclaration>()
            .filter { it.isSupported() }
            .forEach { it.generate() }

        return deferred
    }

    /**
     * Tracking annotations on a type the processor is never asked about, which would otherwise be
     * silently ignored: nothing is generated for it, so nothing could carry the scope.
     */
    private fun reportTrackingWithoutDiffable(resolver: Resolver) {
        resolver.getSymbolsWithAnnotation(TRACKABLE)
            .filterIsInstance<KSClassDeclaration>()
            .filterNot { it.isDiffable() }
            .forEach {
                logger.error(
                    "@Trackable requires @Diffable; ${it.simpleName.asString()} is not @Diffable",
                    it,
                )
            }

        listOf(TRACK_IGNORE, TRACK_DEPTH).forEach { annotation ->
            resolver.getSymbolsWithAnnotation(annotation)
                .filterIsInstance<KSPropertyDeclaration>()
                .filterNot { (it.parentDeclaration as? KSClassDeclaration)?.isDiffable() == true }
                .forEach { property ->
                    logger.error(
                        "@${annotation.substringAfterLast('.')} requires a @Trackable class; the " +
                            "class declaring ${property.simpleName.asString()} is neither @Diffable " +
                            "nor @Trackable, and needs both",
                        property,
                    )
                }
        }
    }

    private fun KSClassDeclaration.isSupported(): Boolean {
        if (typeParameters.isNotEmpty()) {
            logger.error(
                "@Diffable does not support type parameters; ${simpleName.asString()} is generic",
                this,
            )
            return false
        }
        if (isDataClass() || isSealedType()) return true
        logger.error(
            "@Diffable is only supported on data classes and sealed types; " +
                "${simpleName.asString()} is ${describeKind()}",
            this,
        )
        return false
    }

    private fun KSClassDeclaration.describeKind(): String = when {
        classKind == ClassKind.INTERFACE -> "an interface"
        classKind == ClassKind.OBJECT -> "an object"
        classKind == ClassKind.ENUM_CLASS -> "an enum class"
        classKind == ClassKind.ENUM_ENTRY -> "an enum entry"
        classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
        else -> "a class"
    }

    private fun KSClassDeclaration.generate() {
        val target = toClassName()
        val sources = mutableSetOf<KSFile>()
        containingFile?.let(sources::add)

        val body = if (isSealedType()) sealedBody(sources) else dataClassBody(sources)
        if (body == null) return

        val comparisons = if (isSealedType()) emptyList() else resolvedProperties(sources) ?: return
        val applyBody = if (isSealedType()) sealedApplyBody() else dataClassApplyBody(target, comparisons)

        val trackScope = resolveTrackScope()

        val differ = TypeSpec.objectBuilder(differName(target).simpleName)
            .addSuperinterface(DIFFER.parameterizedBy(target))
            .addSuperinterface(PATCHER.parameterizedBy(target))
            .apply {
                if (trackScope == null) return@apply
                addSuperinterface(TRACKED.parameterizedBy(target))
                addProperty(
                    PropertySpec.builder("trackScope", TRACK_SCOPE.parameterizedBy(target))
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer(trackScopeInitializer(trackScope))
                        .build(),
                )
            }
            .addFunction(
                FunSpec.builder("diff")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("before", target)
                    .addParameter("after", target)
                    .returns(DIFF)
                    .addCode(body)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("apply")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("before", target)
                    .addParameter("changes", LIST.parameterizedBy(CHANGE))
                    .returns(PATCH_RESULT.parameterizedBy(target))
                    .addCode(applyBody)
                    .build(),
            )
            .build()

        FileSpec.builder(target.packageName, "${target.simpleNames.joinToString("")}Diff")
            .addType(differ)
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, *sources.toTypedArray()))
    }

    /** One runtime call listing the scope, so resolution and matching stay in kdiff-runtime. */
    private fun trackScopeInitializer(scope: List<TrackedProperty>): CodeBlock {
        if (scope.isEmpty()) return CodeBlock.of("%M()", TRACK_SCOPE_OF)

        return CodeBlock.builder()
            .add("%M(\n", TRACK_SCOPE_OF)
            .apply {
                scope.forEach { add("%T(%S, %L),\n", TRACKED_FIELD, it.name, it.depth) }
            }
            .add(")")
            .build()
    }

    /** A straight-line sequence of runtime calls, one per compared property (design D1). */
    private fun KSClassDeclaration.dataClassBody(sources: MutableSet<KSFile>): CodeBlock? {
        if (!reportsAtMostOneKey()) return null

        val comparisons = comparableProperties().map { property ->
            val comparison = resolve(property) ?: return null
            comparison.sources.forEach(sources::add)
            property to comparison
        }

        return CodeBlock.builder()
            .add("return %T(\n", DIFF).indent()
            .add("%M<%T> {\n", BUILD_LIST, CHANGE).indent()
            .apply { comparisons.forEach { (property, comparison) -> add(emit(property, comparison)) } }
            .unindent().add("},\n")
            .unindent().add(")\n")
            .build()
    }

    private fun KSClassDeclaration.sealedBody(sources: MutableSet<KSFile>): CodeBlock? {
        val subclasses = getSealedSubclasses().toList()
        val unannotated = subclasses.filterNot { it.isDiffable() }
        if (unannotated.isNotEmpty()) {
            logger.error(
                "@Diffable on a sealed type requires every subclass to be @Diffable; " +
                    "${simpleName.asString()} has ${unannotated.joinToString { it.simpleName.asString() }}",
                this,
            )
            return null
        }
        subclasses.forEach { subclass -> subclass.containingFile?.let(sources::add) }

        // Properties the sealed parent itself declares: the only set comparable across a subclass
        // swap, and known from the parent alone (design D4).
        val shared = comparableProperties().map { property ->
            val comparison = resolve(property) ?: return null
            comparison.sources.forEach(sources::add)
            property to comparison
        }

        val branches = CodeBlock.builder()
        subclasses.forEach { subclass ->
            val name = subclass.toClassName()
            branches.add(
                "before is %T && after is %T -> addAll(%T.diff(before, after).changes)\n",
                name, name, differName(name),
            )
        }
        branches.add("else -> {\n").indent()
            .add(
                "add(%T(%T.ROOT, before::class.simpleName.orEmpty(), " +
                    "after::class.simpleName.orEmpty(), before, after))\n",
                TYPE_CHANGED, FIELD_PATH,
            )
            .apply { shared.forEach { (property, comparison) -> add(emit(property, comparison)) } }
            .unindent().add("}\n")

        return CodeBlock.builder()
            .add("return %T(\n", DIFF).indent()
            .add("%M<%T> {\n", BUILD_LIST, CHANGE).indent()
            .add("when {\n").indent()
            .add(branches.build())
            .unindent().add("}\n")
            .unindent().add("},\n")
            .unindent().add(")\n")
            .build()
    }

    /** The same resolution the comparison uses, read a second time for reconstruction. */
    private fun KSClassDeclaration.resolvedProperties(
        sources: MutableSet<KSFile>,
    ): List<Pair<KSPropertyDeclaration, Comparison>>? = comparableProperties().map { property ->
        val comparison = resolve(property) ?: return null
        comparison.sources.forEach(sources::add)
        property to comparison
    }

    private fun KSClassDeclaration.dataClassApplyBody(
        target: ClassName,
        comparisons: List<Pair<KSPropertyDeclaration, Comparison>>,
    ): CodeBlock {
        val constructorParameters = primaryConstructor?.parameters
            ?.mapNotNull { it.name?.asString() }.orEmpty().toSet()
        val names = comparisons.map { (property, _) -> property.simpleName.asString() }

        val body = CodeBlock.builder()
            .add("val grouped = %M(changes, setOf(%L))\n", GROUP_BY_PROPERTY, names.joinToString { "\"$it\"" })

        comparisons.forEach { (property, comparison) ->
            body.add(emitPatch(property, comparison, property.simpleName.asString() in constructorParameters))
        }

        body.add("return %T(\n", PATCH_RESULT).indent()
        val patchable = names.filter { it in constructorParameters }
        if (patchable.isEmpty()) {
            body.add("before,\n")
        } else {
            body.add("before.copy(\n").indent()
            patchable.forEach { body.add("%N = %N.value,\n", it, "${it}Patched") }
            body.unindent().add("),\n")
        }
        val failures = listOf("grouped.%M(%S)") + names.map { "${it}Patched.failures" }
        body.add(failures.joinToString(" + ") + ",\n", UNMATCHED_FAILURES, target.simpleName)
        body.unindent().add(")\n")

        return body.build()
    }

    /**
     * A subclass swap carries the target value, so applying it substitutes wholesale. Otherwise the
     * instance is still the same subclass and its own patcher handles the changes.
     */
    private fun KSClassDeclaration.sealedApplyBody(): CodeBlock {
        val target = toClassName()
        val body = CodeBlock.builder()
            .add(
                "val swap = changes.firstOrNull { it is %T && it.path.segments.isEmpty() } as? %T\n",
                TYPE_CHANGED, TYPE_CHANGED,
            )
            .add("if (swap != null) return %T(swap.after as %T)\n\n", PATCH_RESULT, target)
            .add("return when (before) {\n").indent()

        getSealedSubclasses().forEach { subclass ->
            val name = subclass.toClassName()
            body.add("is %T -> {\n", name).indent()
                .add("val result = %T.apply(before, changes)\n", differName(name))
                .add("%T(result.value, result.failures)\n", PATCH_RESULT)
                .unindent().add("}\n")
        }

        return body.add("else -> %T(before)\n", PATCH_RESULT).unindent().add("}\n").build()
    }

    private fun emitPatch(
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
                patched, NOT_CONSTRUCTOR_PROPERTY, name, changes, name,
            )
        }

        val nullable = property.type.resolve().isMarkedNullable

        return when (comparison) {
            Comparison.ByValue ->
                CodeBlock.of("val %N = %M(before.%N, %L)\n", patched, PATCH_VALUE, name, changes)

            is Comparison.Nested -> when {
                !comparison.canPatch -> CodeBlock.of(
                    "val %N = %M(before.%N, %L, %S)\n",
                    patched, UNPATCHABLE, name, changes, name,
                )
                nullable -> CodeBlock.of(
                    "val %N = %M(before.%N, %L, %T)\n",
                    patched, PATCH_NESTED_NULLABLE, name, changes, comparison.differ,
                )
                else -> CodeBlock.of(
                    "val %N = %M(before.%N, %L, %T)\n",
                    patched, PATCH_NESTED, name, changes, comparison.differ,
                )
            }

            is Comparison.KeyedList -> CodeBlock.of(
                "val %N = %M(before.%N, %L, %T) { it.%N }\n",
                patched, PATCH_KEYED_LIST, name, changes, comparison.differ, comparison.keyProperty,
            )

            is Comparison.PositionalList -> if (comparison.differ == null) {
                CodeBlock.of("val %N = %M(before.%N, %L, null)\n", patched, PATCH_POSITIONAL_LIST, name, changes)
            } else {
                CodeBlock.of(
                    "val %N = %M(before.%N, %L, %T)\n",
                    patched, PATCH_POSITIONAL_LIST, name, changes, comparison.differ,
                )
            }

            Comparison.AsSet ->
                CodeBlock.of("val %N = %M(before.%N, %L)\n", patched, PATCH_SET, name, changes)

            is Comparison.AsMap -> if (comparison.valueDiffer == null) {
                CodeBlock.of("val %N = %M(before.%N, %L, null)\n", patched, PATCH_MAP, name, changes)
            } else {
                CodeBlock.of(
                    "val %N = %M(before.%N, %L, %T)\n",
                    patched, PATCH_MAP, name, changes, comparison.valueDiffer,
                )
            }
        }
    }

    private fun KSClassDeclaration.comparableProperties(): List<KSPropertyDeclaration> =
        getDeclaredProperties().filterNot { it.hasAnnotation(DIFF_IGNORE) }.toList()

    /**
     * The tracking scope this class declares, or null when it declares none and so gets no scope.
     *
     * Returns null after reporting, so a rejected annotation fails the build rather than producing a
     * scope the author did not ask for.
     */
    private fun KSClassDeclaration.resolveTrackScope(): List<TrackedProperty>? {
        if (!reportsHonourableTrackingAnnotations()) return null
        if (!isTrackable()) return null

        val classDepth = depthArgument(TRACKABLE) ?: UNLIMITED_DEPTH
        if (!reportsValidDepth(classDepth, this, simpleName.asString(), "@Trackable")) return null

        val scope = mutableListOf<TrackedProperty>()
        comparableProperties().forEach { property ->
            if (property.hasAnnotation(TRACK_IGNORE)) return@forEach
            val name = property.simpleName.asString()
            val depth = property.depthArgument(TRACK_DEPTH) ?: classDepth
            if (!reportsValidDepth(depth, property, name, "@TrackDepth")) return null
            scope += TrackedProperty(name, depth)
        }
        return scope
    }

    private fun reportsValidDepth(
        depth: Int,
        symbol: KSAnnotated,
        name: String,
        annotation: String,
    ): Boolean {
        if (depth == UNLIMITED_DEPTH || depth >= 1) return true
        logger.error(
            "$annotation on $name declares depth $depth; depth must be at least 1, or " +
                "UNLIMITED_DEPTH ($UNLIMITED_DEPTH)",
            symbol,
        )
        return false
    }

    /**
     * Property-level tracking annotations that could not do what their author meant.
     *
     * Checked across every declared property, not only the compared ones: the whole point of the
     * `@DiffIgnore` cases is that an ignored property yields no changes, so tracking it would be a
     * pair of annotations that both stay silent.
     */
    private fun KSClassDeclaration.reportsHonourableTrackingAnnotations(): Boolean {
        var honourable = true

        getDeclaredProperties().forEach { property ->
            val name = property.simpleName.asString()
            val ignored = property.hasAnnotation(TRACK_IGNORE)
            val depthed = property.hasAnnotation(TRACK_DEPTH)
            if (!ignored && !depthed) return@forEach

            if (ignored && depthed) {
                logger.error(
                    "@TrackIgnore and @TrackDepth conflict on $name; one excludes the property from " +
                        "the scope and the other configures it within it",
                    property,
                )
                honourable = false
                return@forEach
            }
            if (property.hasAnnotation(DIFF_IGNORE)) {
                logger.error(
                    "${if (ignored) "@TrackIgnore" else "@TrackDepth"} on $name conflicts with " +
                        "@DiffIgnore; an ignored property produces no changes and so can never be " +
                        "tracked",
                    property,
                )
                honourable = false
                return@forEach
            }
            if (!isTrackable()) {
                logger.error(
                    "${if (ignored) "@TrackIgnore" else "@TrackDepth"} on $name requires @Trackable " +
                        "on ${simpleName.asString()}; without a declared scope it has no effect",
                    property,
                )
                honourable = false
            }
        }

        return honourable
    }

    private fun KSClassDeclaration.reportsAtMostOneKey(): Boolean {
        val keys = getDeclaredProperties().filter { it.hasAnnotation(DIFF_KEY) }.toList()
        if (keys.size <= 1) return true
        logger.error(
            "a type may declare at most one @DiffKey; ${simpleName.asString()} declares " +
                keys.joinToString { it.simpleName.asString() },
            this,
        )
        return false
    }

    /** Design D3's ordered table. First match wins; `null` means an error was already reported. */
    private fun resolve(property: KSPropertyDeclaration): Comparison? {
        val type = property.type.resolve()

        diffWithTarget(property)?.let { return it }
        if (property.hasAnnotation(DIFF_WITH)) return null

        if (type.isValueType()) return Comparison.ByValue

        type.declarationOrNull()?.takeIf { it.isDiffable() }?.let { nested ->
            return Comparison.Nested(nested.differClassName(), nested.file())
        }

        if (type.isList()) return resolveList(property, type)
        if (type.isSet()) return Comparison.AsSet
        if (type.isMap()) return resolveMap(property, type)

        logger.error(
            "kdiff cannot compare ${property.simpleName.asString()} of type " +
                "${type.declaration.qualifiedName?.asString() ?: type}; annotate its type with " +
                "@Diffable or point the property at a hand-written differ with @DiffWith",
            property,
        )
        return null
    }

    private fun resolveList(property: KSPropertyDeclaration, type: KSType): Comparison? {
        val element = type.typeArgumentAt(0) ?: return unsupportedElement(property, type)
        val declaration = element.declarationOrNull()

        if (declaration != null && declaration.isDiffable()) {
            val key = declaration.keyProperty()
            val differ = declaration.differClassName()
            return if (key == null) {
                Comparison.PositionalList(differ, declaration.file())
            } else {
                Comparison.KeyedList(
                    element = declaration.toClassName(),
                    differ = differ,
                    keyProperty = key.simpleName.asString(),
                    sources = declaration.file(),
                )
            }
        }

        if (element.isValueType()) return Comparison.PositionalList(differ = null, sources = emptyList())
        return unsupportedElement(property, element)
    }

    private fun resolveMap(property: KSPropertyDeclaration, type: KSType): Comparison? {
        val value = type.typeArgumentAt(1) ?: return unsupportedElement(property, type)
        val declaration = value.declarationOrNull()

        if (declaration != null && declaration.isDiffable()) {
            return Comparison.AsMap(declaration.differClassName(), declaration.file())
        }
        if (value.isValueType()) return Comparison.AsMap(valueDiffer = null, sources = emptyList())
        return unsupportedElement(property, value)
    }

    private fun unsupportedElement(property: KSPropertyDeclaration, type: KSType): Comparison? {
        logger.error(
            "kdiff cannot compare elements of ${property.simpleName.asString()} of type " +
                "${type.declaration.qualifiedName?.asString() ?: type}; annotate that type with " +
                "@Diffable or point the property at a hand-written differ with @DiffWith",
            property,
        )
        return null
    }

    /** Resolves `@DiffWith`, verifying the named class is an object implementing `Differ<P>`. */
    private fun diffWithTarget(property: KSPropertyDeclaration): Comparison? {
        val annotation = property.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == DIFF_WITH
        } ?: return null

        val argument = annotation.arguments.firstOrNull()?.value as? KSType
        val declaration = argument?.declarationOrNull()
        if (declaration == null) {
            logger.error("@DiffWith needs a differ class", property)
            return null
        }
        if (declaration.classKind != ClassKind.OBJECT) {
            logger.error(
                "@DiffWith requires an object; ${declaration.simpleName.asString()} is not one",
                property,
            )
            return null
        }
        if (!declaration.implementsDifferFor(property.type.resolve())) {
            logger.error(
                "@DiffWith on ${property.simpleName.asString()} names " +
                    "${declaration.simpleName.asString()}, which does not implement Differ of that " +
                    "property's type",
                property,
            )
            return null
        }
        return Comparison.Nested(
            declaration.toClassName(),
            declaration.file(),
            canPatch = declaration.implementsFor("Patcher", property.type.resolve()),
        )
    }

    private fun KSClassDeclaration.implementsDifferFor(propertyType: KSType): Boolean =
        implementsFor("Differ", propertyType)

    private fun KSClassDeclaration.implementsFor(simpleName: String, propertyType: KSType): Boolean =
        superTypes.map { it.resolve() }.any { supertype ->
            val matches = supertype.declaration.qualifiedName?.asString() ==
                "io.github.kdiff.runtime.$simpleName"
            matches && supertype.typeArgumentAt(0)?.declaration?.qualifiedName ==
                propertyType.declaration.qualifiedName
        }

    private fun emit(property: KSPropertyDeclaration, comparison: Comparison): CodeBlock {
        val name = property.simpleName.asString()
        val nullable = property.type.resolve().isMarkedNullable

        return when (comparison) {
            Comparison.ByValue ->
                CodeBlock.of("%M(%S, before.%N, after.%N)\n", COMPARE_VALUE, name, name, name)

            is Comparison.Nested -> if (nullable) {
                CodeBlock.of(
                    "%M(%S, before.%N, after.%N, %T)\n",
                    COMPARE_NESTED_NULLABLE, name, name, name, comparison.differ,
                )
            } else {
                CodeBlock.of(
                    "%M(%S, before.%N, after.%N, %T)\n",
                    COMPARE_NESTED, name, name, name, comparison.differ,
                )
            }

            is Comparison.KeyedList -> CodeBlock.of(
                "%M(%S, %S, before.%N, after.%N, %T) { it.%N }\n",
                COMPARE_KEYED_LIST, name, comparison.keyProperty, name, name,
                comparison.differ, comparison.keyProperty,
            )

            is Comparison.PositionalList -> if (comparison.differ == null) {
                CodeBlock.of(
                    "%M(%S, before.%N, after.%N, null)\n",
                    COMPARE_POSITIONAL_LIST, name, name, name,
                )
            } else {
                CodeBlock.of(
                    "%M(%S, before.%N, after.%N, %T)\n",
                    COMPARE_POSITIONAL_LIST, name, name, name, comparison.differ,
                )
            }

            Comparison.AsSet ->
                CodeBlock.of("%M(%S, before.%N, after.%N)\n", COMPARE_SET, name, name, name)

            is Comparison.AsMap -> if (comparison.valueDiffer == null) {
                CodeBlock.of("%M(%S, before.%N, after.%N, null)\n", COMPARE_MAP, name, name, name)
            } else {
                CodeBlock.of(
                    "%M(%S, before.%N, after.%N, %T)\n",
                    COMPARE_MAP, name, name, name, comparison.valueDiffer,
                )
            }
        }
    }
}

