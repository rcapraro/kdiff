package tutorial.app

import tutorial.command.TouchPerson
import tutorial.command.UpdatePerson
import tutorial.domain.Address
import tutorial.domain.AddressId
import tutorial.domain.Contact
import tutorial.domain.Employed
import tutorial.domain.FullName
import tutorial.domain.Person
import tutorial.domain.PersonId
import tutorial.domain.Phone
import tutorial.domain.Retired
import tutorial.money.Money

private val ada = PersonId("P1")

private val home = Address(AddressId("A1"), "12 Bishopsgate", "London", "GB")
private val work = Address(AddressId("A2"), "1 Ada Way", "Cambridge", "GB")
private val summer = Address(AddressId("A3"), "9 Rue Lovelace", "Lyon", "FR")

private val phone = Phone("44", "7700900123")

private val seed = Person(
    id = ada,
    name = FullName("Ada", "Byron"),
    nickname = null,
    contact = Contact(email = null, phone = phone),
    addresses = listOf(home, work),
    employment = Employed("Analytical Engine Co", "1843"),
    salary = Money("1200", "GBP"),
    tags = setOf("mathematician"),
    lastSeenAt = "1843-01-01",
)

fun main() {
    val repository = InMemoryPersonRepository(listOf(seed))
    val handler = UpdatePersonHandler(repository)

    // One command that renames, adds a nickname, records an email, corrects a dialling code, reorders
    // one address, edits another, adds a third, retires the person and adds a tag.
    val update = UpdatePerson(
        id = ada,
        name = FullName("Ada", "Lovelace"),
        nickname = "Countess",
        contact = Contact(email = "ada@analyticalengine.co", phone = phone.copy(country = "33")),
        addresses = listOf(work, home.copy(city = "Ockham"), summer),
        employment = Retired("1852"),
        salary = Money("1200", "GBP"),
        tags = setOf("mathematician", "programmer"),
    )

    report("UpdatePerson(id=$ada)", handler.handle(update))
    report("TouchPerson(id=$ada)", handler.handle(TouchPerson(ada, at = "1852-11-27")))

    println()
    println("audited transitions: ${handler.audited.size}")
    handler.audited.forEach { diff -> println(diff.render().prependIndent("  ")) }
}

private fun report(command: String, events: List<Any>) {
    println("> $command")
    if (events.isEmpty()) {
        println("  (no events)")
    }
    events.forEach { println("  $it") }
}
