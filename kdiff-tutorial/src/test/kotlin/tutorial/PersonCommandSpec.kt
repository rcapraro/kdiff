package tutorial

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tutorial.app.InMemoryPersonRepository
import tutorial.app.UpdatePersonHandler
import tutorial.command.TouchPerson
import tutorial.command.UpdatePerson
import tutorial.domain.Contact
import tutorial.domain.FullName
import tutorial.domain.Person
import tutorial.domain.Retired
import tutorial.event.AddressAdded
import tutorial.event.AddressEdited
import tutorial.event.AddressRemoved
import tutorial.event.AddressesReordered
import tutorial.event.EmailChanged
import tutorial.event.EmploymentChanged
import tutorial.event.NicknameChanged
import tutorial.event.PersonRenamed
import tutorial.event.PhoneCountryCorrected
import tutorial.event.PhoneNumberChanged
import tutorial.event.SalaryAdjusted
import tutorial.money.Money

private fun Person.asCommand() = UpdatePerson(
    id = id,
    name = name,
    nickname = nickname,
    contact = contact,
    addresses = addresses,
    employment = employment,
    salary = salary,
    tags = tags,
)

private class Fixture {
    val repository = InMemoryPersonRepository(listOf(PERSON))
    val handler = UpdatePersonHandler(repository)

    fun handle(change: UpdatePerson.() -> UpdatePerson) = handler.handle(PERSON.asCommand().change())

    fun stored(): Person = repository.load(ADA)!!
}

class PersonCommandSpec : FunSpec({

    test("a command that asks for nothing produces no events") {
        Fixture().handle { this }.shouldBeEmpty()
    }

    test("a rename becomes one event carrying both sides") {
        val events = Fixture().handle { copy(name = FullName("Ada", "Lovelace")) }

        events shouldContainExactly listOf(
            PersonRenamed(ADA, FullName("Ada", "Byron"), FullName("Ada", "Lovelace")),
        )
    }

    test("a nullable scalar becoming a value is one event") {
        Fixture().handle { copy(nickname = "Countess") } shouldContainExactly listOf(
            NicknameChanged(ADA, before = null, after = "Countess"),
        )
    }

    test("an added address arrives as the address itself") {
        Fixture().handle { copy(addresses = addresses + SUMMER) } shouldContainExactly listOf(
            AddressAdded(ADA, SUMMER),
        )
    }

    test("a removed address arrives as the address itself") {
        Fixture().handle { copy(addresses = listOf(HOME)) } shouldContainExactly listOf(
            AddressRemoved(ADA, WORK),
        )
    }

    test("a swap reports a move for each element, with its key and both positions") {
        Fixture().handle { copy(addresses = listOf(WORK, HOME)) } shouldContainExactly listOf(
            AddressesReordered(ADA, HOME.id, from = 0, to = 1),
            AddressesReordered(ADA, WORK.id, from = 1, to = 0),
        )
    }

    test("an address edited in two places is one edit") {
        val edited = HOME.copy(city = "Ockham", line1 = "Ockham Park")

        Fixture().handle { copy(addresses = listOf(edited, WORK)) } shouldContainExactly listOf(
            AddressEdited(ADA, before = HOME, after = edited),
        )
    }

    test("a subclass swap is one employment change") {
        Fixture().handle { copy(employment = Retired("1852")) } shouldContainExactly listOf(
            EmploymentChanged(ADA, PERSON.employment, Retired("1852")),
        )
    }

    test("a value object one level down routes to its own property's event") {
        Fixture().handle {
            copy(contact = contact.copy(email = "ada@analyticalengine.co"))
        } shouldContainExactly listOf(
            EmailChanged(ADA, before = null, after = "ada@analyticalengine.co"),
        )
    }

    test("a value object two levels down routes through a frame inside a frame") {
        Fixture().handle {
            copy(contact = contact.copy(phone = PHONE.copy(number = "7700900999")))
        } shouldContainExactly listOf(
            PhoneNumberChanged(ADA, before = "7700900123", after = "7700900999"),
        )
    }

    test("two properties of one nested value object are two events, not one") {
        val events = Fixture().handle {
            copy(contact = Contact(email = "ada@analyticalengine.co", phone = PHONE.copy(country = "33")))
        }

        events shouldContainExactly listOf(
            EmailChanged(ADA, before = null, after = "ada@analyticalengine.co"),
            PhoneCountryCorrected(ADA, before = "44", after = "33"),
        )
    }

    test("a change inside a hand-written differ's type is one event") {
        Fixture().handle { copy(salary = Money("1300", "GBP")) } shouldContainExactly listOf(
            SalaryAdjusted(ADA, Money("1200", "GBP"), Money("1300", "GBP")),
        )
    }

    test("a tracked property the domain has no operation for is audited, not turned into an event") {
        val fixture = Fixture()

        fixture.handle { copy(tags = tags + "programmer") }.shouldBeEmpty()

        fixture.handler.audited.single().render() shouldBe "tags  ADDED \"programmer\""
    }

    test("marking someone as seen changes an untracked property, so nothing is reported at all") {
        val fixture = Fixture()

        fixture.handler.handle(TouchPerson(ADA, at = "1852-11-27")).shouldBeEmpty()
        fixture.handler.audited.shouldBeEmpty()
        fixture.stored().lastSeenAt shouldBe "1852-11-27"
    }

    test("the state the command asked for is what is stored") {
        val fixture = Fixture()

        fixture.handle { copy(name = FullName("Ada", "Lovelace"), addresses = listOf(WORK, HOME)) }

        fixture.stored().name shouldBe FullName("Ada", "Lovelace")
        fixture.stored().addresses shouldContainExactly listOf(WORK, HOME)
    }

    test("one command decomposes into one event per operation it asks for") {
        val events = Fixture().handle {
            copy(
                name = FullName("Ada", "Lovelace"),
                addresses = listOf(WORK, HOME.copy(city = "Ockham"), SUMMER),
                employment = Retired("1852"),
            )
        }

        events.first().shouldBeInstanceOf<PersonRenamed>()
        events.filterIsInstance<AddressesReordered>().size shouldBe 2
        events.filterIsInstance<AddressAdded>().single().address shouldBe SUMMER
        events.filterIsInstance<AddressEdited>().single().after.city shouldBe "Ockham"
        events.last().shouldBeInstanceOf<EmploymentChanged>()
    }
})
