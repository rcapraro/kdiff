package tutorial

import tutorial.domain.Address
import tutorial.domain.AddressId
import tutorial.domain.Contact
import tutorial.domain.Employed
import tutorial.domain.FullName
import tutorial.domain.Person
import tutorial.domain.PersonId
import tutorial.domain.Phone
import tutorial.money.Money

internal val ADA = PersonId("P1")

internal val HOME = Address(AddressId("A1"), "12 Bishopsgate", "London", "GB")
internal val WORK = Address(AddressId("A2"), "1 Ada Way", "Cambridge", "GB")
internal val SUMMER = Address(AddressId("A3"), "9 Rue Lovelace", "Lyon", "FR")

internal val PHONE = Phone("44", "7700900123")

internal val PERSON = Person(
    id = ADA,
    name = FullName("Ada", "Byron"),
    nickname = null,
    contact = Contact(email = null, phone = PHONE),
    addresses = listOf(HOME, WORK),
    employment = Employed("Analytical Engine Co", "1843"),
    salary = Money("1200", "GBP"),
    tags = setOf("mathematician"),
    lastSeenAt = "1843-01-01",
)
