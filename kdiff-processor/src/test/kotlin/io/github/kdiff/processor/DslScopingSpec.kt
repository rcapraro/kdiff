package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `@KdiffDsl` closes the builder scopes, so the only way to prove it is to compile code that would
 * have been accepted without it. These snippets are compiled in-test and asserted to be rejected.
 */
private fun snippet(body: String) = SourceFile.kotlin(
    "Scoping.kt",
    """
    package demo

    import io.github.kdiff.runtime.Diff
    import io.github.kdiff.runtime.at
    import io.github.kdiff.runtime.differ
    import io.github.kdiff.runtime.route
    import io.github.kdiff.runtime.tracker
    import io.github.kdiff.runtime.trackScope

    data class Address(val city: String, val street: String)
    data class Order(val reference: String, val billing: Address, val addresses: List<Address>)

    val addressDiffer = differ<Address> {
        field(Address::city)
        field(Address::street)
    }

    fun body(diff: Diff) {
        $body
    }
    """.trimIndent(),
)

class DslScopingSpec :
    FunSpec({

        test("a nested routing frame cannot register a handler on the enclosing routing") {
            val result = compile(
                snippet(
                    """
                    diff.route<Order> {
                        under(Order::billing) {
                            on(Order::reference) { }
                        }
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        }

        test("an element routing cannot reach the enclosing routing's members") {
            val result = compile(
                snippet(
                    """
                    diff.route<Order> {
                        onEach(Order::addresses) {
                            otherwise { }
                        }
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        }

        test("a scope block cannot reach an enclosing differ builder's members") {
            val result = compile(
                snippet(
                    """
                    differ<Order> {
                        nested(Order::billing, addressDiffer)
                        trackScope<Order> {
                            field(Order::reference)
                        }
                    }
                    """.trimIndent(),
                ),
            )

            // `field` inside the scope block resolves to the scope's own, so this compiles; what must
            // not compile is reaching the differ's `nested` from inside it.
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }

        test("a differ member is not reachable from a scope block nested inside it") {
            val result = compile(
                snippet(
                    """
                    differ<Order> {
                        field(Order::reference)
                        trackScope<Order> {
                            nested(Order::billing, addressDiffer)
                        }
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "nested"
        }

        // `Diff` carries no type argument, so narrowing checks the property only when the caller names
        // the type. Both halves are pinned here because the documentation states both.
        test("narrowing with the type named rejects a property of another type") {
            val result = compile(
                snippet(
                    """
                    diff.at<Order>(Address::street)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        }

        test("narrowing with the type inferred accepts one, matching nothing") {
            val result = compile(
                snippet(
                    """
                    diff.at(Address::street)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }

        test("an explicitly qualified receiver still reaches the outer routing") {
            val result = compile(
                snippet(
                    """
                    diff.route<Order> {
                        val outer = this
                        under(Order::billing) {
                            outer.on(Order::reference) { }
                        }
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }
    })
