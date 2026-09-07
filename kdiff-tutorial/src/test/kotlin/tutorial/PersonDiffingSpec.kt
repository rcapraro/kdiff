package tutorial

import io.github.kdiff.runtime.trackedDiff
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import tutorial.diff.PersonDiffer
import tutorial.diff.PersonScope
import tutorial.domain.Person
import tutorial.domain.Retired
import tutorial.money.Money

private fun paths(desired: Person) = PersonDiffer.diff(PERSON, desired).changes.map { it.path.toString() }

private fun tracked(desired: Person) =
    PersonDiffer.trackedDiff(PERSON, desired, PersonScope).changes.map { it.path.toString() }

/** What the hand-written description reports, one shape at a time. */
class PersonDiffingSpec :
    FunSpec({

        test("a value object reports one level down") {
            paths(PERSON.copy(name = PERSON.name.copy(family = "Lovelace"))) shouldContainExactly
                listOf("name.family")
        }

        test("a value object inside a value object reports two levels down") {
            paths(PERSON.copy(contact = PERSON.contact.copy(phone = PHONE.copy(number = "7700900999"))))
                .shouldContainExactly(listOf("contact.phone.number"))
        }

        test("a keyed list reports an edit under the element's key, however deep the scope") {
            paths(PERSON.copy(addresses = listOf(HOME.copy(city = "Ockham"), WORK))) shouldContainExactly
                listOf("addresses[id=A1].city")
        }

        test("a reordered element reports as moved, once per element that changed position") {
            paths(PERSON.copy(addresses = listOf(WORK, HOME))) shouldContainExactly
                listOf("addresses[id=A1]", "addresses[id=A2]")
        }

        test("a sealed swap reports a type change at the property") {
            paths(PERSON.copy(employment = Retired("1852"))) shouldContainExactly listOf("employment")
        }

        test("a type kdiff was told about by hand reports inside itself") {
            paths(PERSON.copy(salary = Money("1300", "GBP"))) shouldContainExactly listOf("salary.amount")
        }

        test("a set reports membership") {
            paths(PERSON.copy(tags = PERSON.tags + "programmer")) shouldContainExactly listOf("tags")
        }

        test("the excluded property is compared but never tracked") {
            val touched = PERSON.copy(lastSeenAt = "1852-11-27")

            paths(touched) shouldContainExactly listOf("lastSeenAt")
            tracked(touched).shouldBeEmpty()
        }

        test("the scope reaches as deep as the model goes without naming a depth") {
            tracked(PERSON.copy(addresses = listOf(HOME.copy(city = "Ockham"), WORK))) shouldContainExactly
                listOf("addresses[id=A1].city")
        }
    })
