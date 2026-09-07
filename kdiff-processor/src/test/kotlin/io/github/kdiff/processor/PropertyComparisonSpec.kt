package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class PropertyComparisonSpec :
    FunSpec({

        test("a changed value property is reported at its path with both sides") {
            val result = compile(
                SourceFile.kotlin(
                    "Person.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Person(val id: String, val name: String)
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.runDiffer("demo.Person", listOf("1", "Ada"), listOf("1", "Grace"))

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("name")
            diff.valueChange("name").before shouldBe "Ada"
            diff.valueChange("name").after shouldBe "Grace"
        }

        test("identical instances produce no changes") {
            val result = compile(personSource)

            result.runDiffer("demo.Person", listOf("1", "Ada"), listOf("1", "Ada")).isEmpty() shouldBe true
        }

        test("several changed properties are reported in declaration order") {
            val result = compile(personSource)

            val diff = result.runDiffer("demo.Person", listOf("1", "Ada"), listOf("2", "Grace"))

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("id", "name")
        }

        test("an enum property is compared by value") {
            val result = compile(
                SourceFile.kotlin(
                    "Task.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                enum class Status { OPEN, CLOSED }

                @Diffable
                data class Task(val id: String, val status: Status)

                object Fixture {
                    val before = Task("1", Status.OPEN)
                    val after = Task("1", Status.CLOSED)
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.TaskDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("status")
            diff.valueChange("status").before.toString() shouldBe "OPEN"
        }

        test("an ignored property never produces a change") {
            val result = compile(
                SourceFile.kotlin(
                    "Person.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffIgnore

                @Diffable
                data class Person(val id: String, @DiffIgnore val lastSeen: String)
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            result.runDiffer("demo.Person", listOf("1", "monday"), listOf("1", "friday"))
                .isEmpty() shouldBe true
        }

        test("ignoring one property leaves the others compared") {
            val result = compile(
                SourceFile.kotlin(
                    "Person.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffIgnore

                @Diffable
                data class Person(val name: String, @DiffIgnore val lastSeen: String)
                    """.trimIndent(),
                ),
            )

            val diff = result.runDiffer("demo.Person", listOf("Ada", "monday"), listOf("Grace", "friday"))

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("name")
        }

        test("a nullable property reports a null transition as a value change") {
            val result = compile(
                SourceFile.kotlin(
                    "Person.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Person(val id: String, val nickname: String?)

                object Fixture {
                    val before = Person("1", null)
                    val after = Person("1", "Ada")
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PersonDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("nickname")
            diff.valueChange("nickname").before shouldBe null
            diff.valueChange("nickname").after shouldBe "Ada"
        }

        test("a change inside a nested type is reported at a nested path") {
            val result = compile(
                nestedSource(
                    """Person("1", Address("Rue X", "Paris"))""",
                    """Person("1", Address("Rue Y", "Paris"))""",
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PersonDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("address.street")
        }

        test("nesting composes to three levels") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable data class Address(val city: String)
                @Diffable data class Company(val address: Address)
                @Diffable data class Person(val company: Company)

                object Fixture {
                    val before = Person(Company(Address("Paris")))
                    val after = Person(Company(Address("Lyon")))
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PersonDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("company.address.city")
        }

        test("a nullable nested property becoming null is a value change with nothing beneath it") {
            val result = compile(nestedSource("""Person("1", Address("Rue X", "Paris"))""", """Person("1", null)"""))
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.PersonDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("address")
        }
    })

private val personSource = SourceFile.kotlin(
    "Person.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Person(val id: String, val name: String)
    """.trimIndent(),
)

private fun nestedSource(before: String, after: String) = SourceFile.kotlin(
    "Model.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Address(val street: String, val city: String)

    @Diffable
    data class Person(val id: String, val address: Address?)

    object Fixture {
        val before = $before
        val after = $after
    }
    """.trimIndent(),
)
