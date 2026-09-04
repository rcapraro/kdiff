package io.github.kdiff.runtime

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec

private data class Money(val amount: String, val currency: String)

private data class PostalCode(val value: String)

private data class Location(val id: String, val street: String, val postalCode: PostalCode)

private sealed interface Channel {
    val label: String
}

private data class Email(override val label: String, val address: String) : Channel

private data class Phone(override val label: String, val number: String) : Channel

private data class Person(
    val name: String,
    val salary: Money?,
    val addresses: List<Location>,
    val aliases: List<String>,
    val contact: Channel,
    val tags: Set<String>,
    val externalIds: Map<String, String>,
)

private val MoneyDiffer: Differ<Money> = differ {
    field(Money::amount)
    field(Money::currency)
}

private val PostalCodeDiffer: Differ<PostalCode> = differ { field(PostalCode::value) }

private val LocationDiffer: Differ<Location> = differ {
    field(Location::id)
    field(Location::street)
    nested(Location::postalCode, PostalCodeDiffer)
}

private val EmailDiffer: Differ<Email> = differ {
    field(Email::label)
    field(Email::address)
}

private val PhoneDiffer: Differ<Phone> = differ {
    field(Phone::label)
    field(Phone::number)
}

private val ChannelDiffer: Differ<Channel> = differ {
    subtype(Email::class, EmailDiffer)
    subtype(Phone::class, PhoneDiffer)
    field(Channel::label)
}

private val PersonDiffer: Differ<Person> = differ {
    field(Person::name)
    nested(Person::salary, MoneyDiffer)
    keyedList(Person::addresses, Location::id, LocationDiffer)
    list(Person::aliases)
    nested(Person::contact, ChannelDiffer)
    set(Person::tags)
    map(Person::externalIds)
}

private val home = Location("A1", "12 Bishopsgate", PostalCode("EC2N"))
private val work = Location("A2", "1 Ada Way", PostalCode("CB1"))

private val ada = Person(
    name = "Ada",
    salary = Money("1200", "GBP"),
    addresses = listOf(home, work),
    aliases = listOf("AAL"),
    contact = Email("primary", "ada@example.com"),
    tags = setOf("mathematician"),
    externalIds = mapOf("legacy" to "AL-1"),
)

class DslSpec : FunSpec({

    context("values and nesting") {
        test("a hand-written differ compares the fields it names") {
            val diff = MoneyDiffer.diff(Money("10", "EUR"), Money("12", "EUR"))

            diff.changes.map { it.path.toString() } shouldBe listOf("amount")
        }

        test("a hand-written differ reports nothing for equal instances") {
            MoneyDiffer.diff(Money("10", "EUR"), Money("10", "EUR")).isEmpty shouldBe true
        }

        test("a property named nowhere is not compared") {
            val nameOnly = differ<Money> { field(Money::amount) }

            nameOnly.diff(Money("10", "EUR"), Money("10", "USD")).isEmpty shouldBe true
        }

        test("a hand-written differ nests inside another hand-written differ at the full path") {
            data class Invoice(val id: String, val total: Money)

            val invoiceDiffer = differ<Invoice> {
                field(Invoice::id)
                nested(Invoice::total, MoneyDiffer)
            }

            val diff = invoiceDiffer.diff(
                Invoice("1", Money("10", "EUR")),
                Invoice("1", Money("12", "EUR")),
            )

            diff.changes.map { it.path.toString() } shouldBe listOf("total.amount")
        }
    }

    context("keyed lists") {
        test("a reordered element is reported as moved, not as a removal and an addition") {
            val changes = PersonDiffer.diff(ada, ada.copy(addresses = listOf(work, home))).changes

            changes shouldContainExactlyInAnyOrder listOf(
                Moved(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A1"))), 0, 1),
                Moved(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2"))), 1, 0),
            )
        }

        test("an element present on one side only is reported at its key") {
            val changes = PersonDiffer.diff(ada, ada.copy(addresses = listOf(home))).changes

            changes shouldContainExactly listOf(
                Removed(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2"))), work),
            )
        }

        test("an element's own change is reported beneath its key") {
            val edited = home.copy(postalCode = PostalCode("EC2M"))
            val changes = PersonDiffer.diff(ada, ada.copy(addresses = listOf(edited, work))).changes

            changes.map { it.path.toString() } shouldContainExactly
                listOf("addresses[id=A1].postalCode.value")
        }
    }

    context("positional lists") {
        test("a differing element is reported under its index") {
            val changes = PersonDiffer.diff(ada, ada.copy(aliases = listOf("Lovelace"))).changes

            changes shouldContainExactly listOf(
                ValueChanged(
                    FieldPath(listOf(Segment.Field("aliases"), Segment.Index(0))),
                    "AAL",
                    "Lovelace",
                ),
            )
        }

        test("a longer list reports the surplus as added") {
            val changes = PersonDiffer.diff(ada, ada.copy(aliases = listOf("AAL", "ALB"))).changes

            changes shouldContainExactly listOf(
                Added(FieldPath(listOf(Segment.Field("aliases"), Segment.Index(1))), "ALB"),
            )
        }
    }

    context("sets and maps") {
        test("a set reports membership only") {
            val changes = PersonDiffer.diff(ada, ada.copy(tags = setOf("programmer"))).changes

            changes shouldContainExactlyInAnyOrder listOf(
                Removed(FieldPath.of("tags"), "mathematician"),
                Added(FieldPath.of("tags"), "programmer"),
            )
        }

        test("a map reports a changed entry at its key") {
            val changes = PersonDiffer.diff(ada, ada.copy(externalIds = mapOf("legacy" to "AL-2"))).changes

            changes shouldContainExactly listOf(
                ValueChanged(
                    FieldPath(listOf(Segment.Field("externalIds"), Segment.Key("key", "legacy"))),
                    "AL-1",
                    "AL-2",
                ),
            )
        }
    }

    context("nested properties") {
        test("a nested change is lifted under the property name") {
            val changes = PersonDiffer.diff(ada, ada.copy(salary = Money("1300", "GBP"))).changes

            changes shouldContainExactly listOf(
                ValueChanged(FieldPath(listOf(Segment.Field("salary"), Segment.Field("amount"))), "1200", "1300"),
            )
        }

        test("a nested property null on one side is one value change at the property") {
            val changes = PersonDiffer.diff(ada, ada.copy(salary = null)).changes

            changes shouldContainExactly listOf(
                ValueChanged(FieldPath.of("salary"), Money("1200", "GBP"), null),
            )
        }

        test("a nested property null on both sides reports nothing") {
            val without = ada.copy(salary = null)

            PersonDiffer.diff(without, without).changes shouldBe emptyList()
        }
    }

    context("subtypes") {
        test("two instances of one declared subtype are compared by that subtype's differ") {
            val changes = ChannelDiffer.diff(
                Email("primary", "ada@example.com"),
                Email("primary", "ada@lovelace.example"),
            ).changes

            changes shouldContainExactly listOf(
                ValueChanged(FieldPath.of("address"), "ada@example.com", "ada@lovelace.example"),
            )
        }

        test("a subclass swap reports a type change and the properties named alongside") {
            val before = Email("primary", "ada@example.com")
            val after = Phone("work", "555")

            ChannelDiffer.diff(before, after).changes shouldContainExactly listOf(
                TypeChanged(FieldPath.ROOT, "Email", "Phone", before, after),
                ValueChanged(FieldPath.of("label"), "primary", "work"),
            )
        }

        test("two instances of an undeclared subtype report no type change") {
            val partial = differ<Channel> { subtype(Email::class, EmailDiffer) }
            val phone = Phone("work", "555")

            partial.diff(phone, phone).isEmpty shouldBe true
        }

        test("an undeclared subtype is still compared by the properties named alongside") {
            val partial = differ<Channel> {
                subtype(Email::class, EmailDiffer)
                field(Channel::label)
            }

            partial.diff(Phone("home", "555"), Phone("work", "555")).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("label"), "home", "work"))
        }

        test("a swap nested in another differ is reported at the property") {
            val changes = PersonDiffer.diff(ada, ada.copy(contact = Phone("work", "555"))).changes

            changes.map { it.path.toString() } shouldContainExactly listOf("contact", "contact.label")
        }
    }
})
