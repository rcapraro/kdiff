package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A minor version may declare a further `PatchFailure.Reason`; a consumer may not.
 *
 * The set is open to the library and closed to everyone else, and the second half is what keeps every
 * case inspectable — a reason a consumer invented would carry facts nothing can render. Compiled here
 * for the reason `DiffShapeSpec` is: the absence of a capability cannot be asserted from inside the
 * module that has it.
 */
private fun snippet(body: String): SourceFile = SourceFile.kotlin(
    "Consumer.kt",
    """
    package demo

    import io.github.kdiff.runtime.PatchFailure

    $body
    """.trimIndent(),
)

class ReasonSealingSpec :
    FunSpec({

        test("a consumer cannot declare a reason of its own") {
            val result = compile(snippet("object Mine : PatchFailure.Reason"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "sealed"
        }

        test("a consumer branches on the reasons it knows and says what it does with the rest") {
            val result = compile(
                snippet(
                    """
                    fun act(failure: PatchFailure): String = when (failure.reason) {
                        is PatchFailure.Reason.NoElementForKey -> "reinstated"
                        else -> "logged"
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }
    })
