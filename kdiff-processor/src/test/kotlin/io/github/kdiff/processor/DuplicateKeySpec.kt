package io.github.kdiff.processor

import com.tschuchort.compiletesting.SourceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * A repeated `@DiffKey` value is a property of the data, so it cannot be a compile error. These
 * assert that the generated code rejects it at runtime, in both directions, rather than collapsing
 * the duplicates as it once did.
 */
class DuplicateKeySpec : FunSpec({

    val model = SourceFile.kotlin(
        "Team.kt",
        """
        package demo

        import io.github.kdiff.annotations.DiffKey
        import io.github.kdiff.annotations.Diffable

        @Diffable
        data class Member(@DiffKey val id: String, val name: String)

        @Diffable
        data class Team(val members: List<Member>)

        object Fixture {
            val before = Team(listOf(Member("M1", "Ada"), Member("M1", "Grace")))
            val after = Team(listOf(Member("M1", "Ada")))
        }
        """.trimIndent(),
    )

    test("a generated differ rejects a list holding two elements with one key") {
        val result = compile(model)

        val thrown = shouldThrow<IllegalArgumentException> { result.diffFixture("demo.TeamDiffer") }

        thrown.message.shouldContain("members is keyed by id")
        thrown.message.shouldContain("share the key M1")
        thrown.message.shouldContain("members[id=M1] cannot name one of them")
    }

    test("a generated patcher rejects the same list, so both directions agree") {
        val result = compile(model)

        val thrown = shouldThrow<IllegalArgumentException> {
            result.patchFixture("demo.TeamDiffer", emptyList())
        }

        thrown.message.shouldContain("members is keyed by id")
        thrown.message.shouldContain("share the key M1")
    }
})
