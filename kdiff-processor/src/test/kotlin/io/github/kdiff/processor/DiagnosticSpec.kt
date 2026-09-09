package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
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

/** `Order`'s first property lands on line 15, so locations can be asserted. */
private fun undiffable(properties: String): SourceFile = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.DiffIgnore
    import io.github.kdiff.annotations.DiffKey
    import io.github.kdiff.annotations.DiffWith
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.differ

    class Money(val amount: String)

    object MoneyDiffer : Differ<Money> by differ({ field(Money::amount) })

    data class Order(
        $properties
    )
    """.trimIndent(),
)

/** An element type reached through a differ, so its nullability is the thing under test. */
private fun elements(property: String): SourceFile = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Address(val id: String, val street: String)

    @Diffable
    data class Order(
        $property
    )
    """.trimIndent(),
)

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

        test("@Diffable on an object says an object in a sealed hierarchy needs no annotation") {
            val result = compile(
                SourceFile.kotlin(
                    "Registry.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                object Registry
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain
                "Registry is an object, and an object in a @Diffable sealed hierarchy needs no annotation of its own"
        }

        context("a collection whose elements are nullable and reached through a differ") {
            test("a list of nullable annotated elements is rejected at the property") {
                val result = compile(elements("val stops: List<Address?>"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "kdiff cannot compare stops: its elements are nullable demo.Address, and elements " +
                    "compared by a differ cannot be null; declare them non-null, or point the property " +
                    "at a hand-written differ with @DiffWith"
                result.messages shouldContain "Order.kt:10"
            }

            test("a map of nullable annotated values is rejected at the property") {
                val result = compile(elements("val byRegion: Map<String, Address?>"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "kdiff cannot compare byRegion: its values are nullable demo.Address, and values " +
                    "compared by a differ cannot be null; declare them non-null, or point the property " +
                    "at a hand-written differ with @DiffWith"
            }

            test("a list of nullable values compiles, because equality is defined for null") {
                val result = compile(elements("val notes: List<String?>"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            }

            test("a set of nullable annotated elements compiles, because a set compares by membership") {
                val result = compile(elements("val visited: Set<Address?>"))

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            }
        }

        context("comparison annotations with nothing to configure") {
            listOf(
                "@DiffIgnore" to "@DiffIgnore val note: String",
                "@DiffKey" to "@DiffKey val id: String",
                "@DiffWith" to "@DiffWith(MoneyDiffer::class) val total: Money",
                "@DiffAsValue" to "@DiffAsValue val billing: Money",
            ).forEach { (name, usage) ->
                test("$name on a property of a class that is not @Diffable is rejected") {
                    val result = compile(undiffable(usage))

                    result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                    result.messages shouldContain "$name on ${usage.substringAfter("val ").substringBefore(":")} " +
                        "requires @Diffable on Order"
                    result.messages shouldContain "without a generated differ it has no effect"
                    result.messages shouldContain "Order.kt:15"
                }
            }

            test("@DiffWith on a @DiffIgnore property is rejected as a conflict") {
                val result = compile(
                    SourceFile.kotlin(
                        "Invoice.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.DiffIgnore
                    import io.github.kdiff.annotations.DiffWith
                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.runtime.Differ
                    import io.github.kdiff.runtime.differ

                    class Money(val amount: String)

                    object MoneyDiffer : Differ<Money> by differ({ field(Money::amount) })

                    @Diffable
                    data class Invoice(
                        val id: String,
                        @DiffIgnore @DiffWith(MoneyDiffer::class) val total: Money,
                    )
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@DiffWith on total conflicts with @DiffIgnore"
                result.messages shouldContain "an ignored property is never compared"
            }

            // A key identifies an element; ignoring the same property excludes it from that element's own
            // comparison. The two answer different questions, so together they are not a conflict.
            test("@DiffKey beside @DiffIgnore compiles and still keys the list") {
                val result = compile(
                    SourceFile.kotlin(
                        "Order.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.DiffIgnore
                    import io.github.kdiff.annotations.DiffKey
                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    data class Address(@DiffKey @DiffIgnore val id: String, val city: String)

                    @Diffable
                    data class Order(val addresses: List<Address>)

                    object Fixture {
                        val before = Order(listOf(Address("A1", "Paris"), Address("A2", "Nice")))
                        val after = Order(listOf(Address("A2", "Nice"), Address("A1", "Lyon")))
                    }
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK

                // The moves prove the list is still keyed by `id`; the absence of an `.id` path proves
                // `@DiffIgnore` still excludes it from the element's own comparison.
                result.diffFixture("demo.OrderDiffer").changes.map { it.path.toString() } shouldContainExactly
                    listOf("addresses[id=A1]", "addresses[id=A1].city", "addresses[id=A2]")
            }

            test("a rejected comparison annotation blocks the build alongside a valid class") {
                val result = compile(
                    SourceFile.kotlin(
                        "Model.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.DiffIgnore
                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    data class Person(val id: String, val name: String)

                    data class Loose(@DiffIgnore val note: String)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@DiffIgnore on note requires @Diffable on Loose"
                result.messages shouldNotContain "on Person"
            }
        }

        context("@DiffAsValue where it could change nothing or contradict something") {
            test("on a class that is also @Diffable it is rejected at the declaration") {
                val result = compile(
                    valued("@Diffable @DiffAsValue data class Money(val amount: String)"),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "@DiffAsValue on Money conflicts with @Diffable; one compares the type as a " +
                    "single value and the other property by property"
                result.messages shouldContain "Value.kt:7"
            }

            // A type on the built-in list cannot carry the annotation at all — nobody can annotate
            // `BigDecimal` — so the reachable no-effect declarations are these two.
            listOf(
                "an enum" to ("Status" to "@DiffAsValue enum class Status { OPEN, CLOSED }"),
                "a value class" to ("Email" to "@DiffAsValue @JvmInline value class Email(val value: String)"),
            ).forEach { (kind, declaration) ->
                val (name, source) = declaration

                test("on $kind it is rejected as having no effect") {
                    val result = compile(valued(source))

                    result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                    result.messages shouldContain
                        "@DiffAsValue on $name has no effect; $name is already compared as a value"
                    result.messages shouldContain "Value.kt:7"
                }
            }

            test("on a property whose type is already a value it is rejected at the property") {
                val result = compile(
                    valued(
                        """
                        @Diffable
                        data class Invoice(
                            @DiffAsValue val total: java.math.BigDecimal,
                        )
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "@DiffAsValue on total has no effect; java.math.BigDecimal is already compared as a value"
                result.messages shouldContain "Value.kt:9"
            }

            test("beside @DiffWith on one property it is rejected as a conflict") {
                val result = compile(
                    valued(
                        """
                        class Money(val amount: String)

                        object MoneyDiffer : Differ<Money> by differ({ field(Money::amount) })

                        @Diffable
                        data class Invoice(@DiffAsValue @DiffWith(MoneyDiffer::class) val total: Money)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "@DiffAsValue on total conflicts with @DiffWith; a property is compared one way"
            }

            test("beside @DiffIgnore on one property it is rejected as a conflict") {
                val result = compile(
                    valued(
                        """
                        @Diffable data class Address(val city: String)

                        @Diffable
                        data class Order(@DiffAsValue @DiffIgnore val note: Address)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "@DiffAsValue on note conflicts with @DiffIgnore; an ignored property is never compared"
            }

            // The key is refused by the rule that already covers a comparison annotation outside a
            // `@Diffable` class: a type compared as one value generates no differ to read it.
            test("a key on a type declared as a value is rejected") {
                val result = compile(
                    valued("@DiffAsValue data class Tag(@DiffKey val id: String, val label: String)"),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain "@DiffKey on id requires @Diffable on Tag"
            }
        }

        context("the cannot-compare messages name @DiffAsValue as the third way out") {
            test("a property kdiff cannot compare names all three") {
                val result = compile(
                    valued(
                        """
                        class Money(val amount: String)

                        @Diffable
                        data class Invoice(val total: Money)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "kdiff cannot compare total of type demo.Money; annotate its type with @Diffable, " +
                    "mark the property @DiffAsValue to compare it by equality, or point the property at " +
                    "a hand-written differ with @DiffWith"
            }

            test("elements kdiff cannot compare name the two type-level declarations") {
                val result = compile(
                    valued(
                        """
                        class Money(val amount: String)

                        @Diffable
                        data class Invoice(val lines: List<Money>)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
                result.messages shouldContain
                    "kdiff cannot compare elements of lines of type demo.Money; annotate that type " +
                    "with @Diffable or @DiffAsValue, or point the property at a hand-written differ " +
                    "with @DiffWith"
            }

            test("the same property declared as a value compiles and is compared by equality") {
                val result = compile(
                    valued(
                        """
                        class Money(val amount: String)

                        @Diffable
                        data class Invoice(@DiffAsValue val total: Money)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
                result.generatedSource("InvoiceDiff.kt") shouldContain "compareValue(\"total\""
            }
        }
    })

/** A snippet whose first declaration lands on line 7, so locations can be asserted. */
private fun valued(declarations: String): SourceFile = SourceFile.kotlin(
    "Value.kt",
    """
    package demo

    import io.github.kdiff.annotations.*
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.differ

    $declarations
    """.trimIndent(),
)

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
