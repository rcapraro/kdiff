package tutorial

import io.github.kdiff.runtime.Diff
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import tutorial.annotated.PersonDiffer as AnnotatedPersonDiffer
import tutorial.diff.PersonDiffer
import tutorial.domain.Person
import tutorial.domain.Retired
import tutorial.money.Money

/**
 * The two authoring routes, held to the same output.
 *
 * The mirror in `tutorial.annotated` describes the same model with annotations, so anything the
 * builder gets wrong shows up here as a difference in what is reported — the check the "a
 * hand-written differ is indistinguishable from a generated one" promise needs to be worth anything.
 */
private fun Person.annotated(): tutorial.annotated.Person = tutorial.annotated.Person(
    id = tutorial.annotated.PersonId(id.value),
    name = tutorial.annotated.FullName(name.given, name.family),
    nickname = nickname,
    addresses = addresses.map {
        tutorial.annotated.Address(
            id = tutorial.annotated.AddressId(it.id.value),
            line1 = it.line1,
            city = it.city,
            country = it.country,
        )
    },
    employment = when (val employment = employment) {
        is tutorial.domain.Employed ->
            tutorial.annotated.Employed(employment.employer, employment.since)
        is Retired -> tutorial.annotated.Retired(employment.since)
    },
    salary = salary,
    tags = tags,
    lastSeenAt = lastSeenAt,
)

private fun Diff.shape(): List<String> =
    changes.map { "${it::class.simpleName} ${it.path}" }

private infix fun Person.agreesWith(desired: Person) {
    val byHand = PersonDiffer.diff(this, desired)
    val byAnnotation = AnnotatedPersonDiffer.diff(annotated(), desired.annotated())

    byHand.shape() shouldContainExactly byAnnotation.shape()
    byHand.render() shouldBe byAnnotation.render()
}

class AnnotatedParitySpec : FunSpec({

    test("both routes report nothing for an unchanged person") {
        PERSON agreesWith PERSON
    }

    test("both routes report the same changes for a transition touching every shape") {
        val desired = PERSON.copy(
            name = PERSON.name.copy(family = "Lovelace"),
            nickname = "Countess",
            addresses = listOf(WORK, HOME.copy(city = "Ockham"), SUMMER),
            employment = Retired("1852"),
            salary = Money("1300", "GBP"),
            tags = PERSON.tags + "programmer",
            lastSeenAt = "1852-11-27",
        )

        PERSON agreesWith desired
    }

    test("both routes leave the key of a matched element uncompared") {
        PERSON agreesWith PERSON.copy(addresses = listOf(HOME.copy(city = "Ockham"), WORK))
    }

    test("both routes declare the same tracking scope") {
        val declared = AnnotatedPersonDiffer.trackScope.trackedFields?.map { it.name }

        declared shouldContainExactly listOf("name", "nickname", "addresses", "employment", "salary", "tags")
    }
})
