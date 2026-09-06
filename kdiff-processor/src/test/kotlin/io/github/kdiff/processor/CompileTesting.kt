package io.github.kdiff.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import io.github.kdiff.runtime.Change
import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.PatchResult
import io.github.kdiff.runtime.Patcher
import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.Tracked
import io.github.kdiff.runtime.TrackedField
import io.github.kdiff.runtime.TrackerBuilder
import io.github.kdiff.runtime.ValueChanged
import io.github.kdiff.runtime.tracker

internal fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        configureKsp {
            symbolProcessorProviders += DiffProcessorProvider()
        }
        inheritClassPath = true
        messageOutputStream = System.out
    }.compile()

internal val JvmCompilationResult.generatedFileNames: List<String>
    get() = sourcesGeneratedBySymbolProcessor.map { it.name }.toList()

internal fun JvmCompilationResult.generatedSource(name: String): String =
    sourcesGeneratedBySymbolProcessor.first { it.name == name }.readText()

/**
 * Loads a generated differ and runs it against two instances built from string constructor
 * arguments, so tests assert on behaviour rather than on generated text.
 */
internal fun JvmCompilationResult.runDiffer(
    targetClassName: String,
    beforeArgs: List<String>,
    afterArgs: List<String>,
): Diff {
    val target = classLoader.loadClass(targetClassName)
    val differ = loadObject("${targetClassName}Differ")

    val constructor = target.getConstructor(*Array(beforeArgs.size) { String::class.java })
    val before = constructor.newInstance(*beforeArgs.toTypedArray())
    val after = constructor.newInstance(*afterArgs.toTypedArray())

    return differ.javaClass.getMethod("diff", target, target).invoke(differ, before, after) as Diff
}

/**
 * Runs a differ against `Fixture.before` and `Fixture.after` declared in the compiled snippet.
 *
 * Needed wherever the instances cannot be built from strings alone — nested types, collections,
 * enums, nulls and sealed hierarchies.
 */
@Suppress("UNCHECKED_CAST")
internal fun JvmCompilationResult.diffFixture(
    differClassName: String,
    fixtureClassName: String = "demo.Fixture",
): Diff {
    val fixture = loadObject(fixtureClassName)
    val before = fixture.javaClass.getMethod("getBefore").invoke(fixture)
    val after = fixture.javaClass.getMethod("getAfter").invoke(fixture)

    val differ = loadObject(differClassName) as Differ<Any?>
    return differ.diff(before, after)
}

private fun JvmCompilationResult.loadObject(className: String): Any =
    classLoader.loadClass(className).getField("INSTANCE").get(null)

/**
 * Applies [changes] to `Fixture.before` without diffing first.
 *
 * Separate from [roundTripFixture] because a patcher's own behaviour has to be reachable when the
 * comparison would refuse the same instance — otherwise the diff throws and the patcher is never
 * reached.
 */
@Suppress("UNCHECKED_CAST")
internal fun JvmCompilationResult.patchFixture(
    differClassName: String,
    changes: List<Change>,
    fixtureClassName: String = "demo.Fixture",
): PatchResult<Any?> {
    val fixture = loadObject(fixtureClassName)
    val before = fixture.javaClass.getMethod("getBefore").invoke(fixture)

    return (loadObject(differClassName) as Patcher<Any?>).apply(before, changes)
}

/** Round-trips a compiled fixture: diff `Fixture.before` against `Fixture.after`, then apply it. */
@Suppress("UNCHECKED_CAST")
internal fun JvmCompilationResult.roundTripFixture(
    differClassName: String,
    fixtureClassName: String = "demo.Fixture",
): PatchResult<Any?> {
    val fixture = loadObject(fixtureClassName)
    val before = fixture.javaClass.getMethod("getBefore").invoke(fixture)
    val after = fixture.javaClass.getMethod("getAfter").invoke(fixture)

    val target = loadObject(differClassName)
    val differ = target as Differ<Any?>
    val patcher = target as Patcher<Any?>

    return patcher.apply(before, differ.diff(before, after).changes)
}

/** A round trip with extra changes appended, for asserting that one failure does not stop the rest. */
@Suppress("UNCHECKED_CAST")
internal fun JvmCompilationResult.applyWithExtra(
    differClassName: String,
    vararg extra: Change,
    fixtureClassName: String = "demo.Fixture",
): PatchResult<Any?> {
    val fixture = loadObject(fixtureClassName)
    val before = fixture.javaClass.getMethod("getBefore").invoke(fixture)
    val after = fixture.javaClass.getMethod("getAfter").invoke(fixture)

    val target = loadObject(differClassName)
    val differ = target as Differ<Any?>
    val patcher = target as Patcher<Any?>

    return patcher.apply(before, differ.diff(before, after).changes + extra)
}

/** The target of a round trip, so a test can assert the rebuilt value equals it. */
internal fun JvmCompilationResult.fixtureAfter(fixtureClassName: String = "demo.Fixture"): Any? {
    val fixture = loadObject(fixtureClassName)
    return fixture.javaClass.getMethod("getAfter").invoke(fixture)
}

internal fun Diff.valueChange(path: String): ValueChanged =
    changes.filterIsInstance<ValueChanged>().single { it.path.toString() == path }

/** The scope a generated declaration exposes, read back through `Tracked` rather than off its text. */
internal fun JvmCompilationResult.trackedFields(differClassName: String): List<TrackedField>? {
    val differ = loadObject(differClassName) as Tracked<*>
    return differ.trackScope.trackedFields
}

/** Whether a generated declaration carries the tracking capability at all. */
internal fun JvmCompilationResult.declaresTracking(differClassName: String): Boolean =
    Tracked::class.java.isAssignableFrom(classLoader.loadClass(differClassName))

/** A tracker over a compiled fixture, so selection and depth can be exercised end to end. */
@Suppress("UNCHECKED_CAST")
internal fun JvmCompilationResult.trackFixture(
    differClassName: String,
    fixtureClassName: String = "demo.Fixture",
    block: TrackerBuilder<Any?>.() -> Unit = {},
): Diff {
    val fixture = loadObject(fixtureClassName)
    val before = fixture.javaClass.getMethod("getBefore").invoke(fixture)
    val after = fixture.javaClass.getMethod("getAfter").invoke(fixture)

    val differ = loadObject(differClassName) as Differ<Any?>
    return tracker(differ, before, block).update(after)
}
