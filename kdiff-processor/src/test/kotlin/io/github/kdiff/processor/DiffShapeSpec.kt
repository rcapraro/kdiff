package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `Diff` is not a data class, and the two members that would fix its shape are absent.
 *
 * Compiled here rather than asserted in `kdiff-runtime`, because the absence of a member is not
 * something a test in the same module can state: a call that does not compile cannot be written down
 * beside one that does. This module already compiles snippets, so the snippet is the assertion.
 */
private fun snippet(body: String): SourceFile = SourceFile.kotlin(
    "Consumer.kt",
    """
    package demo

    import io.github.kdiff.runtime.Diff

    $body
    """.trimIndent(),
)

class DiffShapeSpec :
    FunSpec({

        test("a diff cannot be copied with different changes") {
            val result = compile(snippet("fun copied(diff: Diff): Diff = diff.copy(changes = emptyList())"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "copy"
        }

        test("a diff cannot be destructured into its components") {
            val result = compile(snippet("fun destructured(diff: Diff) { val (changes) = diff }"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "component1"
        }

        test("constructing a diff from its changes is how one is made") {
            val result = compile(snippet("fun rebuilt(diff: Diff): Diff = Diff(diff.changes.drop(1))"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }
    })
