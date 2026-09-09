package io.github.kdiff.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp

/**
 * What resolution read for one property, so a test can assert on it.
 *
 * The originating files a generated file declares are not reachable through kctfork — it exposes the
 * generated sources and the diagnostics, not the `Dependencies` each was written with. So what
 * resolution reads is recorded as it runs, and the assertion is that resolution saw the declaration
 * rather than that KSP was told about it. The two are the same claim: the sources a `Comparison`
 * carries are exactly what the generating code drains into `Dependencies`.
 */
internal data class RecordedResolution(
    val owner: String,
    val property: String,
    val valueSourceFileNames: List<String>,
    val comparisonOwner: String,
    val comparisonSourceFileName: String?,
    val consultedFileNames: List<String>,
)

/**
 * Records what resolution reads for every property of every class in the compiled snippet, including
 * the properties an exclusion drops — those are precisely the ones whose consulted files nothing else
 * would carry.
 *
 * A processor rather than a plain function because a `KSType` only exists inside a processing round,
 * and `findOverridee()` needs the declarations KSP resolved. Generates nothing, so it changes neither
 * the output nor the diagnostics of the processor it runs beside.
 */
private class RecordingSymbolProcessor : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        recorded = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { declaration -> declaration.getDeclaredProperties() }
            .map { property ->
                val comparison = property.comparisonDeclaration()
                RecordedResolution(
                    owner = property.ownerName(),
                    property = property.simpleName.asString(),
                    valueSourceFileNames = property.type.resolve().declaredValueSources().map { it.fileName },
                    comparisonOwner = comparison.ownerName(),
                    comparisonSourceFileName = comparison.containingFile?.fileName,
                    // The same call `generate` drains into `Dependencies`, so a test asserts the rule
                    // rather than a transcription of it.
                    consultedFileNames = property.comparisonSources().map { it.fileName },
                )
            }
            .toList()
        return emptyList()
    }

    companion object {
        var recorded: List<RecordedResolution> = emptyList()
    }
}

private class RecordingProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = RecordingSymbolProcessor()
}

/**
 * Compiles [sources] and returns what resolution read for each declared property.
 *
 * Separate from [compile] because a test asserting on resolution wants the recording rather than the
 * compilation result, and because registering only the recording processor keeps a snippet that the
 * real processor would reject usable here.
 */
internal fun recordResolutions(vararg sources: SourceFile): List<RecordedResolution> {
    RecordingSymbolProcessor.recorded = emptyList()
    KotlinCompilation().apply {
        this.sources = sources.toList()
        configureKsp {
            symbolProcessorProviders += RecordingProcessorProvider()
        }
        inheritClassPath = true
        messageOutputStream = System.out
        jvmTarget = "21"
    }.compile()
    return RecordingSymbolProcessor.recorded
}

/** The one recording for [owner]'s [property], named by both because an override repeats a name. */
internal fun List<RecordedResolution>.of(owner: String, property: String): RecordedResolution =
    single { it.owner == owner && it.property == property }
