package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private data class Rejection(val declaration: String, val source: String, val expectedKind: String)

private val rejections = listOf(
    Rejection("Person", "class Person(val name: String)", "a class"),
    Rejection("Shape", "interface Shape", "an interface"),
    Rejection("Registry", "object Registry", "an object"),
    Rejection("Status", "enum class Status { OPEN, CLOSED }", "an enum class"),
)

private const val UNSUPPORTED_TARGET = "@Diffable is only supported on data classes and sealed types"

/** `Order` lands on line 11 and its first property on line 12, so locations can be asserted. */
private fun tracking(annotations: String, properties: String): SourceFile = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.annotations.DiffIgnore
    import io.github.kdiff.annotations.TrackDepth
    import io.github.kdiff.annotations.TrackIgnore
    import io.github.kdiff.annotations.Trackable

    @Diffable
    $annotations
    data class Order(
        $properties
    )
    """.trimIndent(),
)

class DiagnosticSpec :
    FunSpec({

        rejections.forEach { (declaration, source, expectedKind) ->
            test("@Diffable on $expectedKind is rejected at the declaration") {
                val result = compile(
                    SourceFile.kotlin(
                        "$declaration.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    $source
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "$UNSUPPORTED_TARGET; $declaration is $expectedKind"
                result.messages shouldContain "$declaration.kt:6"
                result.generatedFileNames shouldBe emptyList()
            }
        }

        test("a rejected declaration fails the build even alongside a valid one") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Person(val id: String, val name: String)

                @Diffable
                interface Shape
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "$UNSUPPORTED_TARGET; Shape is an interface"
            result.messages shouldNotContain "Person is"
        }

        test("a property kdiff cannot compare is rejected at the property") {
            val result = compile(
                SourceFile.kotlin(
                    "Invoice.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                class Money(val amount: String)

                @Diffable
                data class Invoice(val id: String, val total: Money)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "kdiff cannot compare total of type demo.Money"
            result.messages shouldContain "@DiffWith"
            result.messages shouldContain "Invoice.kt:8"
        }

        test("the same property compiles once it is pointed at a hand-written differ") {
            val result = compile(diffWithSource)

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }

        test("a generic annotated class is rejected") {
            val result = compile(
                SourceFile.kotlin(
                    "Box.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Box<T>(val value: T)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "@Diffable does not support type parameters; Box is generic"
            result.messages shouldContain "Box.kt:6"
        }

        test("a sealed type with an unannotated subclass is rejected, naming both") {
            val result = compile(
                SourceFile.kotlin(
                    "Payment.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                sealed interface Payment

                @Diffable
                data class Card(val last4: String) : Payment

                data class Transfer(val iban: String) : Payment
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "requires every subclass to be @Diffable"
            result.messages shouldContain "Payment"
            result.messages shouldContain "Transfer"
        }

        test("more than one key on a type is rejected, naming both properties") {
            val result = compile(
                SourceFile.kotlin(
                    "Address.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffKey

                @Diffable
                data class Address(@DiffKey val id: String, @DiffKey val code: String)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "at most one @DiffKey"
            result.messages shouldContain "id"
            result.messages shouldContain "code"
        }

        test("@DiffWith naming something that is not an object is rejected at the property") {
            val result = compile(
                SourceFile.kotlin(
                    "Invoice.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffWith

                class Money(val amount: String)
                class NotAnObject

                @Diffable
                data class Invoice(@DiffWith(NotAnObject::class) val total: Money)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "@DiffWith requires an object"
        }

        context("tracking annotations") {
            test("a zero depth on the class is rejected at the class") {
                val result = compile(tracking("@Trackable(depth = 0)", "val reference: String"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@Trackable on Order declares depth 0"
                result.messages shouldContain "depth must be at least 1, or UNLIMITED_DEPTH (-1)"
                result.messages shouldContain "Order.kt:11"
            }

            test("a negative depth other than the unlimited constant is rejected at the property") {
                val result = compile(tracking("@Trackable", "@TrackDepth(-2) val reference: String"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@TrackDepth on reference declares depth -2"
                result.messages shouldContain "Order.kt:12"
            }

            test("@Trackable on a class that is not @Diffable is rejected at the class") {
                val result = compile(
                    SourceFile.kotlin(
                        "Order.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.Trackable

                    @Trackable
                    data class Order(val reference: String)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@Trackable requires @Diffable; Order is not @Diffable"
                result.messages shouldContain "Order.kt:6"
            }

            listOf("@TrackIgnore" to "@TrackIgnore", "@TrackDepth" to "@TrackDepth(2)").forEach {
                val (name, usage) = it

                test("$name on a property of a class that is not @Diffable is rejected") {
                    val result = compile(
                        SourceFile.kotlin(
                            "Order.kt",
                            """
                        package demo

                        import io.github.kdiff.annotations.TrackDepth
                        import io.github.kdiff.annotations.TrackIgnore

                        data class Order($usage val reference: String)
                            """.trimIndent(),
                        ),
                    )

                    result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                    result.messages shouldContain "$name requires a @Trackable class"
                    result.messages shouldContain "neither @Diffable nor @Trackable, and needs both"
                }

                test("$name on a @Diffable class that is not @Trackable is rejected at the property") {
                    val result = compile(tracking("", "$usage val reference: String"))

                    result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                    result.messages shouldContain "$name on reference requires @Trackable on Order"
                    result.messages shouldContain "without a declared scope it has no effect"
                }

                test("$name on a @DiffIgnore property is rejected at the property") {
                    val result = compile(
                        tracking("@Trackable", "@DiffIgnore $usage val lastTouched: String"),
                    )

                    result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                    result.messages shouldContain "$name on lastTouched conflicts with @DiffIgnore"
                    result.messages shouldContain "an ignored property produces no changes"
                }
            }

            test("@TrackIgnore and @TrackDepth on the same property are rejected as conflicting") {
                val result = compile(
                    tracking("@Trackable", "@TrackIgnore @TrackDepth(2) val reference: String"),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@TrackIgnore and @TrackDepth conflict on reference"
                result.messages shouldContain "Order.kt:12"
            }

            test("a rejected tracking annotation blocks the build alongside a valid trackable class") {
                val result = compile(
                    SourceFile.kotlin(
                        "Model.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.TrackDepth
                    import io.github.kdiff.annotations.TrackIgnore
                    import io.github.kdiff.annotations.Trackable

                    @Diffable
                    @Trackable(depth = 1)
                    data class Order(val reference: String)

                    @Diffable
                    @Trackable
                    data class Invoice(@TrackIgnore @TrackDepth(2) val reference: String)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@TrackIgnore and @TrackDepth conflict on reference"
                result.messages shouldNotContain "conflict on Order"
            }
        }

        test("@DiffWith naming an object that differs the wrong type is rejected") {
            val result = compile(
                SourceFile.kotlin(
                    "Invoice.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffWith
                import io.github.kdiff.runtime.Differ
                import io.github.kdiff.runtime.differ

                class Money(val amount: String)
                class Other(val name: String)

                object OtherDiffer : Differ<Other> by differ({ field(Other::name) })

                @Diffable
                data class Invoice(@DiffWith(OtherDiffer::class) val total: Money)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "does not implement Differ of that property's type"
        }
    })

internal val diffWithSource = SourceFile.kotlin(
    "Invoice.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.annotations.DiffWith
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.differ

    class Money(val amount: String, val currency: String)

    object MoneyDiffer : Differ<Money> by differ({
        field(Money::amount)
        field(Money::currency)
    })

    @Diffable
    data class Invoice(val id: String, @DiffWith(MoneyDiffer::class) val total: Money)

    object Fixture {
        val before = Invoice("1", Money("10", "EUR"))
        val after = Invoice("1", Money("12", "EUR"))
    }
    """.trimIndent(),
)
