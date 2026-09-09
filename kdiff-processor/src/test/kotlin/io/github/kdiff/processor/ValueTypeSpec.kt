package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.FieldPath
import io.github.kdiff.runtime.PatchFailure
import io.github.kdiff.runtime.Segment
import io.github.kdiff.runtime.ValueChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun model(declarations: String, before: String, after: String) = SourceFile.kotlin(
    "Model.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.Diffable
    import java.math.BigDecimal
    import java.time.Instant
    import java.time.LocalDate
    import java.util.UUID

    $declarations

    object Fixture {
        val before = $before
        val after = $after
    }
    """.trimIndent(),
)

private const val ID = """UUID.fromString("00000000-0000-0000-0000-000000000001")"""
private const val OTHER_ID = """UUID.fromString("00000000-0000-0000-0000-000000000002")"""

class ValueTypeSpec :
    FunSpec({

        test("a standard-library value type compiles and is compared by equality") {
            val result = compile(
                model(
                    "@Diffable data class Invoice(val id: UUID, val total: BigDecimal, val issuedAt: Instant)",
                    """Invoice($ID, BigDecimal("10.00"), Instant.ofEpochSecond(1))""",
                    """Invoice($ID, BigDecimal("10.00"), Instant.ofEpochSecond(2))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.InvoiceDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("issuedAt")
            diff.valueChange("issuedAt").before shouldBe java.time.Instant.ofEpochSecond(1)
            diff.valueChange("issuedAt").after shouldBe java.time.Instant.ofEpochSecond(2)
        }

        test("a standard-library value type is a value inside collections") {
            val result = compile(
                model(
                    "@Diffable data class Plan(val due: List<LocalDate>, val rates: Map<String, BigDecimal>)",
                    """Plan(listOf(LocalDate.of(2026, 1, 1)), mapOf("eur" to BigDecimal("1.0")))""",
                    """Plan(listOf(LocalDate.of(2026, 1, 2)), mapOf("eur" to BigDecimal("1.1")))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.PlanDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("due[0]", "rates[key=eur]")
        }

        test("a big decimal differing only in scale reports a change") {
            val result = compile(
                model(
                    "@Diffable data class Invoice(val total: BigDecimal)",
                    """Invoice(BigDecimal("10"))""",
                    """Invoice(BigDecimal("10.00"))""",
                ),
            )

            result.diffFixture("demo.InvoiceDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("total")
        }

        test("an inline value class compiles and is compared by equality") {
            val result = compile(
                model(
                    """
                    @JvmInline value class Email(val value: String)
                    @Diffable data class Person(val email: Email)
                    """.trimIndent(),
                    """Person(Email("ada@x"))""",
                    """Person(Email("grace@x"))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PersonDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("email")
            diff.valueChange("email").before.toString() shouldBe "Email(value=ada@x)"
        }

        // A `value class` compiled elsewhere is read from its class file rather than from source, and
        // `Modifier.VALUE` does not survive that trip — so detection cannot rest on the modifier alone.
        // `FieldPath` is kdiff's own `@JvmInline value class`, and stands in for any library's.
        test("an inline value class from another module is a value") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.runtime.FieldPath

                @Diffable
                data class Trace(val where: FieldPath, val everywhere: List<FieldPath>)

                object Fixture {
                    val before = Trace(FieldPath.of("a"), listOf(FieldPath.of("a")))
                    val after = Trace(FieldPath.of("b"), listOf(FieldPath.of("b")))
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.TraceDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("where", "everywhere[0]")
        }

        test("kotlin.time.Duration is a value, being an inline value class of the standard library") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import kotlin.time.Duration
                import kotlin.time.Duration.Companion.seconds

                @Diffable
                data class Job(val timeout: Duration)

                object Fixture {
                    val before = Job(1.seconds)
                    val after = Job(2.seconds)
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.JobDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("timeout")
        }

        // Both are in the set, and in the pinned standard library `Instant` is stable while `Uuid` is
        // still `@ExperimentalUuidApi` — hence the opt-in here, which is the consumer's to make and not
        // kdiff's. `annotations.md` says so rather than leaving a reader to discover it.
        test("the Kotlin standard library's own instant and uuid compile as values") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import kotlin.time.Instant
                import kotlin.uuid.ExperimentalUuidApi
                import kotlin.uuid.Uuid

                @OptIn(ExperimentalUuidApi::class)
                @Diffable
                data class Session(val id: Uuid, val startedAt: Instant)

                @OptIn(ExperimentalUuidApi::class)
                object Fixture {
                    val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
                    val before = Session(id, Instant.fromEpochSeconds(1))
                    val after = Session(id, Instant.fromEpochSeconds(2))
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.SessionDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("startedAt")
        }

        // The set is a list, not a package: a mutable JDK type is rejected however close it sits to one
        // that is accepted.
        test("a mutable JDK type is not a value") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Log(val seen: java.util.Date)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "kdiff cannot compare seen of type java.util.Date"
        }

        test("a round trip over standard-library value types reproduces the target") {
            val result = compile(
                model(
                    "@Diffable data class Invoice(val id: UUID, val total: BigDecimal, val issuedAt: Instant)",
                    """Invoice($ID, BigDecimal("10.00"), Instant.ofEpochSecond(1))""",
                    """Invoice($OTHER_ID, BigDecimal("11.50"), Instant.ofEpochSecond(2))""",
                ),
            )

            val patched = result.roundTripFixture("demo.InvoiceDiffer")

            patched.value shouldBe result.fixtureAfter()
            patched.failures.shouldBeEmpty()
        }

        test("a round trip over an inline value class reproduces the target") {
            val result = compile(
                model(
                    """
                    @JvmInline value class Email(val value: String)
                    @Diffable data class Person(val email: Email)
                    """.trimIndent(),
                    """Person(Email("ada@x"))""",
                    """Person(Email("grace@x"))""",
                ),
            )

            val patched = result.roundTripFixture("demo.PersonDiffer")

            patched.value shouldBe result.fixtureAfter()
            patched.failures.shouldBeEmpty()
        }
    })

private const val COORDINATES = "@DiffAsValue data class Coordinates(val lat: Double, val lon: Double)"

class DiffAsValueSpec :
    FunSpec({

        test("a type declared as a value is compared by equality as a property") {
            val result = compile(
                model(
                    """
                    $COORDINATES
                    @Diffable data class Place(val name: String, val at: Coordinates)
                    """.trimIndent(),
                    """Place("home", Coordinates(1.0, 2.0))""",
                    """Place("home", Coordinates(1.0, 3.0))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PlaceDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("at")
            diff.valueChange("at").before.toString() shouldBe "Coordinates(lat=1.0, lon=2.0)"
            diff.valueChange("at").after.toString() shouldBe "Coordinates(lat=1.0, lon=3.0)"
        }

        // The declaration is read off the *referenced* type, which usually lives in another file — the
        // case whose file has to join the generated file's originating set (design D7).
        test("a type declared as a value in another file is a value in this one") {
            val result = compile(
                SourceFile.kotlin(
                    "Coordinates.kt",
                    """
                package demo

                import io.github.kdiff.annotations.DiffAsValue

                $COORDINATES
                    """.trimIndent(),
                ),
                SourceFile.kotlin(
                    "Place.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Place(val at: Coordinates, val seen: List<Coordinates>)

                object Fixture {
                    val before = Place(Coordinates(1.0, 2.0), listOf(Coordinates(1.0, 2.0)))
                    val after = Place(Coordinates(1.0, 3.0), listOf(Coordinates(1.0, 3.0)))
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.PlaceDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("at", "seen[0]")
        }

        test("a type declared as a value is a value inside collections") {
            val result = compile(
                model(
                    """
                    $COORDINATES
                    @Diffable data class Trip(
                        val route: List<Coordinates>,
                        val byName: Map<String, Coordinates>,
                    )
                    """.trimIndent(),
                    """
                    Trip(
                        listOf(Coordinates(1.0, 1.0), Coordinates(2.0, 2.0)),
                        mapOf("home" to Coordinates(3.0, 3.0)),
                    )
                    """.trimIndent(),
                    """
                    Trip(
                        listOf(Coordinates(1.0, 1.0), Coordinates(2.0, 9.0)),
                        mapOf("home" to Coordinates(3.0, 9.0)),
                    )
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.diffFixture("demo.TripDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("route[1]", "byName[key=home]")
        }

        // The annotation is the only thing standing between one change at `billing` and a nested
        // comparison: `Address` is `@Diffable`, so removing it reports `billing.city` instead.
        test("a property declared as a value compares a nested type as a whole") {
            val result = compile(model(NESTED_DECLARED, NESTED_BEFORE, NESTED_AFTER))
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.OrderDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("billing")
            diff.valueChange("billing").after.toString() shouldBe "Address(street=Rue X, city=Lyon)"
        }

        test("the same nested property without the annotation is compared property by property") {
            val result = compile(
                model(NESTED_DECLARED.replace("@DiffAsValue ", ""), NESTED_BEFORE, NESTED_AFTER),
            )

            result.diffFixture("demo.OrderDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("billing.city")
        }

        test("a property declared as a value compares a collection as a whole") {
            val result = compile(
                model(
                    "@Diffable data class Post(@DiffAsValue val tags: List<String>)",
                    """Post(listOf("a", "b"))""",
                    """Post(listOf("a", "c"))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PostDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("tags")
            diff.valueChange("tags").before shouldBe listOf("a", "b")
            diff.valueChange("tags").after shouldBe listOf("a", "c")
        }

        test("a round trip over a property declared as a value sets it wholesale") {
            val result = compile(model(NESTED_DECLARED, NESTED_BEFORE, NESTED_AFTER))

            val patched = result.roundTripFixture("demo.OrderDiffer")

            patched.value shouldBe result.fixtureAfter()
            patched.failures.shouldBeEmpty()
        }

        test("a round trip over a collection declared as a value sets it wholesale") {
            val result = compile(
                model(
                    "@Diffable data class Post(@DiffAsValue val tags: List<String>)",
                    """Post(listOf("a", "b"))""",
                    """Post(listOf("a", "c"))""",
                ),
            )

            val patched = result.roundTripFixture("demo.PostDiffer")

            patched.value shouldBe result.fixtureAfter()
            patched.failures.shouldBeEmpty()
        }

        test("a change beneath a property declared as a value is reported as a failure") {
            val result = compile(model(NESTED_DECLARED, NESTED_BEFORE, NESTED_AFTER))

            val beneath = ValueChanged(
                FieldPath.of("city").prefixedWith(Segment.Field("billing")),
                "Paris",
                "Lyon",
            )

            val patched = result.patchFixture("demo.OrderDiffer", listOf(beneath))

            patched.failures.map { it.reason } shouldContainExactly
                listOf(PatchFailure.Reason.NotApplicableToValue)
            patched.value shouldBe result.fixtureBefore()
        }
    })

private const val NESTED_DECLARED = """
@Diffable data class Address(val street: String, val city: String)
@Diffable data class Order(@DiffAsValue val billing: Address)
"""

private const val NESTED_BEFORE = """Order(Address("Rue X", "Paris"))"""
private const val NESTED_AFTER = """Order(Address("Rue X", "Lyon"))"""
