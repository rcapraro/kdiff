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
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

internal class DiffProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) :
    SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotated = resolver.getSymbolsWithAnnotation(DIFFABLE).toList()
        val (ready, deferred) = annotated.partition { it.validate() }

        reportAnnotationsWithoutDiffable(resolver)
        reportValueDeclarations(resolver)

        ready.filterIsInstance<KSClassDeclaration>()
            .filter { it.isSupported() }
            .forEach { it.generate() }

        return deferred
    }

    /**
     * An annotation on a type the processor is never asked about, which would otherwise be silently
     * ignored: nothing is generated for that type, so nothing could read the annotation.
     */
    private fun reportAnnotationsWithoutDiffable(resolver: Resolver) {
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
            resolver.propertiesOutsideDiffable(annotation).forEach { property ->
                logger.error(
                    "@${annotation.substringAfterLast('.')} requires a @Trackable class; the " +
                        "class declaring ${property.simpleName.asString()} is neither @Diffable " +
                        "nor @Trackable, and needs both",
                    property,
                )
            }
        }

        // The comparison annotations are read only off a `@Diffable` class, so anywhere else each one
        // silently configures nothing — the same failure the tracking annotations above are refused
        // for, and the same remedy: name the annotation that is missing.
        listOf(DIFF_KEY, DIFF_IGNORE, DIFF_WITH, DIFF_AS_VALUE).forEach { annotation ->
            resolver.propertiesOutsideDiffable(annotation).forEach { property ->
                logger.error(
                    "@${annotation.substringAfterLast('.')} on ${property.simpleName.asString()} " +
                        "requires @Diffable on ${property.ownerName()}; without a generated differ " +
                        "it has no effect",
                    property,
                )
            }
        }
    }

    /**
     * A class-level `@DiffAsValue` that contradicts `@Diffable` or restates what kdiff already does.
     *
     * Swept over the whole module rather than per generated class, because the declaration is read off
     * the *referenced* type and so has to be judged wherever it stands.
     */
    private fun reportValueDeclarations(resolver: Resolver) {
        resolver.getSymbolsWithAnnotation(DIFF_AS_VALUE)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                val name = declaration.simpleName.asString()
                if (declaration.isDiffable()) {
                    logger.error(
                        "@DiffAsValue on $name conflicts with @Diffable; one compares the type as a " +
                            "single value and the other property by property",
                        declaration,
                    )
                    return@forEach
                }
                if (declaration.isIntrinsicValueType()) {
                    logger.error(
                        "@DiffAsValue on $name has no effect; $name is already compared as a value",
                        declaration,
                    )
                }
            }
    }

    /**
     * A comparison annotation on a property of this class that cannot do what its author meant.
     *
     * Checked across every declared property, not only the compared ones — which is the whole point of
     * the case it reports: `@DiffIgnore` excludes the property from comparison, so `resolve` is never
     * asked about it and a differ named for it could never run.
     *
     * `@DiffKey` beside `@DiffIgnore` is deliberately **not** a conflict. A key identifies an element;
     * ignoring the same property excludes it from that element's own comparison, which is meaningful
     * wherever the element type appears outside a keyed list. The two answer different questions.
     */
    private fun KSClassDeclaration.reportsHonourableComparisonAnnotations(): Boolean {
        var honourable = true

        getDeclaredProperties().forEach { property ->
            val name = property.simpleName.asString()
            val ignored = property.hasAnnotation(DIFF_IGNORE)
            val differed = property.hasAnnotation(DIFF_WITH)

            if (differed && ignored) {
                logger.error(
                    "@DiffWith on $name conflicts with @DiffIgnore; an ignored property is never compared",
                    property,
                )
                honourable = false
            }
            if (!property.hasAnnotation(DIFF_AS_VALUE)) return@forEach

            if (differed) {
                logger.error(
                    "@DiffAsValue on $name conflicts with @DiffWith; a property is compared one way",
                    property,
                )
                honourable = false
            }
            if (ignored) {
                logger.error(
                    "@DiffAsValue on $name conflicts with @DiffIgnore; an ignored property is never compared",
                    property,
                )
                honourable = false
            }
        }

        return honourable
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
                "${simpleName.asString()} is ${describeKind()}${objectHint()}",
            this,
        )
        return false
    }

    /** A sealed hierarchy is the one place `@Diffable` is reached for on an object and not needed. */
    private fun KSClassDeclaration.objectHint(): String =
        if (isSingleton()) ", and an object in a @Diffable sealed hierarchy needs no annotation of its own" else ""

    private fun KSClassDeclaration.describeKind(): String = when {
        classKind == ClassKind.INTERFACE -> "an interface"
        classKind == ClassKind.OBJECT -> "an object"
        classKind == ClassKind.ENUM_CLASS -> "an enum class"
        classKind == ClassKind.ENUM_ENTRY -> "an enum entry"
        classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
        else -> "a class"
    }

    private fun KSClassDeclaration.generate() {
        if (!reportsHonourableComparisonAnnotations()) return

        val target = toClassName()
        val sources = mutableSetOf<KSFile>()
        containingFile?.let(sources::add)
        // Recorded here, over *every* declared property rather than the compared ones, because a
        // `Comparison` cannot carry what was consulted before it existed: a property an inherited
        // `@DiffIgnore` excludes never reaches `resolve`, and a property annotated nowhere in its chain
        // still read that chain. Both are edits that must regenerate this file (design D3).
        getDeclaredProperties().forEach { property -> property.comparisonSources().forEach(sources::add) }

        val sealed = isSealedType()

        val body = if (sealed) sealedBody(sources) else dataClassBody(sources)
        if (body == null) return

        val comparisons = if (sealed) emptyList() else resolvedProperties(sources) ?: return
        val applyBody = if (sealed) sealedApplyBody() else dataClassApplyBody(target, comparisons)

        val trackScope = resolveTrackScope()

        val differ = TypeSpec.objectBuilder(differName(target).simpleName)
            .addSuperinterface(DIFFER.parameterizedBy(target))
            .addSuperinterface(PATCHER.parameterizedBy(target))
            .apply {
                // A constant of the type, so it is built once rather than on every `apply` call.
                // Private: it is an implementation detail of reconstruction, not generated API.
                // A sealed type reconstructs through its subclass's patcher and never groups.
                if (sealed) return@apply
                addProperty(
                    PropertySpec.builder("comparedProperties", SET.parameterizedBy(STRING), KModifier.PRIVATE)
                        .initializer(
                            "setOf(%L)",
                            comparisons.joinToString { (property, _) -> "\"${property.simpleName.asString()}\"" },
                        )
                        .build(),
                )
            }
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
        // An `object` subclass is exempt: its branch names no differ, so there is nothing an annotation
        // on it could generate. Only a subclass whose branch delegates needs one.
        val unannotated = subclasses.filterNot { it.isSingleton() || it.isDiffable() }
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
            if (subclass.isSingleton()) {
                // Two references to one object differ in nothing, so there is nothing to report and no
                // differ to delegate to.
                branches.add("before is %T && after is %T -> Unit\n", name, name)
                return@forEach
            }
            branches.add(
                "before is %T && after is %T -> addAll(%T.diff(before, after).changes)\n",
                name,
                name,
                differName(name),
            )
        }
        branches.add("else -> {\n").indent()
            .add(
                "add(%T(%T.ROOT, before::class.simpleName.orEmpty(), " +
                    "after::class.simpleName.orEmpty(), before, after))\n",
                TYPE_CHANGED,
                FIELD_PATH,
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
            .add("val grouped = %M(changes, comparedProperties)\n", GROUP_BY_PROPERTY)

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
        // One list rather than a left-fold of `+`, which copies a new list per property and, in the
        // common case where nothing failed, allocates one per property to produce an empty result.
        body.add("%M<%T> {\n", BUILD_LIST, PATCH_FAILURE).indent()
        body.add("addAll(grouped.%M(%S))\n", UNMATCHED_FAILURES, target.simpleName)
        names.forEach { body.add("addAll(%N.failures)\n", "${it}Patched") }
        body.unindent().add("},\n")
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
                TYPE_CHANGED,
                TYPE_CHANGED,
            )
            .add("if (swap != null) return %T(swap.after as %T)\n\n", PATCH_RESULT, target)
            .add("return when (before) {\n").indent()

        val subclasses = getSealedSubclasses().toList()
        subclasses.forEach { subclass ->
            val name = subclass.toClassName()
            if (subclass.isSingleton()) {
                // The parent's type argument is stated because `PatchResult` is invariant: inferred
                // from `before` alone the branch would produce a `PatchResult` of the subclass.
                body.add("is %T -> %M<%T>(before, changes, %S)\n", name, PATCH_SINGLETON, target, name.simpleName)
                return@forEach
            }
            body.add("is %T -> {\n", name).indent()
                .add("val result = %T.apply(before, changes)\n", differName(name))
                .add("%T(result.value, result.failures)\n", PATCH_RESULT)
                .unindent().add("}\n")
        }

        // A `when` over an enumerated sealed hierarchy is already exhaustive, and a trailing `else`
        // draws a warning in every consumer that annotates a sealed type. It is still needed for a
        // sealed type declaring no subclasses at all — which is accepted, and for which a branchless
        // `when` would not compile.
        if (subclasses.isEmpty()) body.add("else -> %T(before)\n", PATCH_RESULT)

        return body.unindent().add("}\n").build()
    }

    // Filtered on the declaration the exclusion is written on, so `@DiffIgnore` on a sealed parent's
    // property excludes it from a subclass that overrides it too (design D2). The filter is where the
    // annotation takes effect, so inheriting it anywhere else would leave this one reading past it.
    private fun KSClassDeclaration.comparableProperties(): List<KSPropertyDeclaration> =
        getDeclaredProperties().filterNot { it.comparisonDeclaration().hasAnnotation(DIFF_IGNORE) }.toList()

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

    private fun reportsValidDepth(depth: Int, symbol: KSAnnotated, name: String, annotation: String): Boolean {
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
            // The one annotation check that asks about effect rather than authorship, so the one that
            // reads the effective `@DiffIgnore` rather than the written one (design D4): a property
            // excluded by a declaration it overrides produces no changes just as surely, and tracking
            // it would still be two annotations that both stay silent.
            if (property.comparisonDeclaration().hasAnnotation(DIFF_IGNORE)) {
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

        // Annotations come from the declaration carrying them, the type from the property itself: an
        // override is what is being compared, and only its annotations can be elsewhere (design D2).
        // Identity, never the presence of a file: a declaration KSP read from a class file has none, so
        // a cross-module inherited annotation would otherwise read as one written here.
        val declared = property.comparisonDeclaration()
        val inherited = declared !== property

        // The two rejections below suppress on different terms, because they refuse different things
        // (design D4). A redundant `@DiffAsValue` is harmless — the property compares as a value either
        // way — so an inherited one is never reported and never fails a build. A `@DiffWith` naming an
        // unusable differ leaves the property uncomparable, so silence is not an option: it is reported
        // unless the declaration carrying it is in this compilation, where its own class reports it once
        // and at the right line. A declaration from a class file is reported by nobody.
        val diffWithReportedElsewhere = inherited && declared.containingFile != null

        diffWithTarget(property, declared, diffWithReportedElsewhere)?.let { return it }
        if (declared.hasAnnotation(DIFF_WITH)) return null

        if (declared.hasAnnotation(DIFF_AS_VALUE)) return declaredValue(property, type, inherited)

        if (type.isValueType()) return Comparison.ByValue(type.declaredValueSources())

        type.declarationOrNull()?.takeIf { it.isDiffable() }?.let { nested ->
            return Comparison.Nested(nested.differClassName(), nested.file())
        }

        if (type.isList()) return resolveList(property, type)
        if (type.isSet()) return Comparison.AsSet
        if (type.isMap()) return resolveMap(property, type)

        logger.error(
            "kdiff cannot compare ${property.simpleName.asString()} of type " +
                "${type.declaration.qualifiedName?.asString() ?: type}; annotate its type with " +
                "@Diffable, mark the property @DiffAsValue to compare it by equality, or point the " +
                "property at a hand-written differ with @DiffWith",
            property,
        )
        return null
    }

    /**
     * A property-level `@DiffAsValue`, which overrides classification for that property alone.
     *
     * Rejected where the property would be compared by equality without it, so the annotation never
     * reads as configuration that is not in effect (design D5).
     *
     * That test reads the property type's own declaration, so its file joins the originating set even
     * though the comparison did not come from it: annotating that type later turns this property into
     * the rejection above, and an incremental build has to see it (design D7).
     */
    private fun declaredValue(property: KSPropertyDeclaration, type: KSType, inherited: Boolean): Comparison? {
        // Rejected only where the annotation is written (design D4). Reporting an inherited one here
        // would name a subclass that declared nothing and repeat one mistake once per subclass — and
        // there is nothing to salvage by reporting it: the annotation is redundant precisely because the
        // property already compares as a value, which is what this returns.
        if (type.isValueType() && !inherited) {
            logger.error(
                "@DiffAsValue on ${property.simpleName.asString()} has no effect; " +
                    "${type.declaration.qualifiedName?.asString() ?: type} is already compared as a value",
                property,
            )
            return null
        }
        return Comparison.ByValue(type.declarationOrNull()?.file().orEmpty())
    }

    private fun resolveList(property: KSPropertyDeclaration, type: KSType): Comparison? {
        val element = type.typeArgumentAt(0) ?: return unsupportedElement(property, type)
        val declaration = element.declarationOrNull()

        if (declaration != null && declaration.isDiffable()) {
            if (element.isMarkedNullable) return nullableElement(property, "elements", element)
            val key = declaration.keyProperty()
            val differ = declaration.differClassName()
            return if (key == null) {
                Comparison.PositionalList(differ, declaration.file())
            } else {
                Comparison.KeyedList(
                    differ = differ,
                    keyProperty = key.simpleName.asString(),
                    sources = declaration.file(),
                )
            }
        }

        if (element.isValueType()) {
            return Comparison.PositionalList(differ = null, sources = element.declaredValueSources())
        }
        return unsupportedElement(property, element)
    }

    private fun resolveMap(property: KSPropertyDeclaration, type: KSType): Comparison? {
        val value = type.typeArgumentAt(1) ?: return unsupportedElement(property, type)
        val declaration = value.declarationOrNull()

        if (declaration != null && declaration.isDiffable()) {
            if (value.isMarkedNullable) return nullableElement(property, "values", value)
            return Comparison.AsMap(declaration.differClassName(), declaration.file())
        }
        if (value.isValueType()) return Comparison.AsMap(valueDiffer = null, sources = value.declaredValueSources())
        return unsupportedElement(property, value)
    }

    /**
     * An element or map value that is both nullable and reached through a differ.
     *
     * A differ takes an instance, so there is nothing for it to compare a null against, and a keyed
     * list could not read a key off one either. Reported rather than supported because supporting it
     * needs a per-element null rule in every collection helper — a separate change, and additive.
     *
     * A nullable element compared *as a value* is untouched: equality is defined for null. So is any
     * set element, since a set is compared by membership alone.
     */
    private fun nullableElement(property: KSPropertyDeclaration, part: String, type: KSType): Comparison? {
        logger.error(
            "kdiff cannot compare ${property.simpleName.asString()}: its $part are nullable " +
                "${type.declaration.qualifiedName?.asString() ?: type}, and $part compared by a " +
                "differ cannot be null; declare them non-null, or point the property at a " +
                "hand-written differ with @DiffWith",
            property,
        )
        return null
    }

    private fun unsupportedElement(property: KSPropertyDeclaration, type: KSType): Comparison? {
        logger.error(
            "kdiff cannot compare elements of ${property.simpleName.asString()} of type " +
                "${type.declaration.qualifiedName?.asString() ?: type}; annotate that type with " +
                "@Diffable or @DiffAsValue, or point the property at a hand-written differ with @DiffWith",
            property,
        )
        return null
    }

    /**
     * Resolves `@DiffWith`, verifying the named class is an object implementing `Differ<P>`.
     *
     * The annotation is read off [declared], which is [property] itself unless the property overrides
     * one carrying it; the differ is checked against [property]'s own type, since that is what will be
     * compared. [reportedElsewhere] suppresses the diagnostics, for the annotation whose own class in
     * this compilation reports them instead (design D2, D4).
     */
    private fun diffWithTarget(
        property: KSPropertyDeclaration,
        declared: KSPropertyDeclaration,
        reportedElsewhere: Boolean,
    ): Comparison? {
        val annotation = declared.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == DIFF_WITH
        } ?: return null

        // Returning null refuses the property either way: `resolve` sees the annotation and stops. The
        // message is what is suppressed, and only when another class in this compilation will give it.
        fun reject(message: String): Comparison? {
            if (!reportedElsewhere) logger.error(message, property)
            return null
        }

        val propertyType = property.type.resolve()
        val argument = annotation.arguments.firstOrNull()?.value as? KSType
        val declaration = argument?.declarationOrNull()
            ?: return reject("@DiffWith needs a differ class")
        if (declaration.classKind != ClassKind.OBJECT) {
            return reject("@DiffWith requires an object; ${declaration.simpleName.asString()} is not one")
        }
        if (!declaration.implementsDifferFor(propertyType)) {
            return reject(
                "@DiffWith on ${property.simpleName.asString()} names " +
                    "${declaration.simpleName.asString()}, which does not implement Differ of that " +
                    "property's type",
            )
        }
        return Comparison.Nested(
            declaration.toClassName(),
            declaration.file(),
            canPatch = declaration.implementsFor("Patcher", propertyType),
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
}
