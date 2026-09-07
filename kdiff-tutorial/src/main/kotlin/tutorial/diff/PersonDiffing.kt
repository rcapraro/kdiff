package tutorial.diff

import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.differ
import io.github.kdiff.runtime.trackScope
import tutorial.domain.Address
import tutorial.domain.Contact
import tutorial.domain.Employed
import tutorial.domain.Employment
import tutorial.domain.FullName
import tutorial.domain.Person
import tutorial.domain.Phone
import tutorial.domain.Retired
import tutorial.money.Money

/**
 * Everything kdiff knows about the domain, in one file the domain does not know about.
 *
 * Each call names a property and says how to compare it — the same vocabulary the annotations offer,
 * so a model can move from here to `@Diffable` and back without any consumer noticing.
 */

val FullNameDiffer: Differ<FullName> = differ {
    field(FullName::given)
    field(FullName::family)
}

/**
 * `id` is named nowhere: it is the key, and two elements matched by key are equal in it by
 * construction, so comparing it could only ever report nothing.
 */
val AddressDiffer: Differ<Address> = differ {
    field(Address::line1)
    field(Address::city)
    field(Address::country)
}

/** A sealed type: two of one subtype delegate, and a swap reports a type change. */
val EmploymentDiffer: Differ<Employment> = differ {
    subtype(
        Employed::class,
        differ {
            field(Employed::employer)
            field(Employed::since)
        },
    )
    subtype(Retired::class, differ { field(Retired::since) })
}

/** An `object`, so the annotated mirror can point `@DiffWith` at it. */
object MoneyDiffer : Differ<Money> by differ({
    field(Money::amount)
    field(Money::currency)
})

val PhoneDiffer: Differ<Phone> = differ {
    field(Phone::country)
    field(Phone::number)
}

/** A value object holding another: `phone` delegates again, so a change is reported two levels down. */
val ContactDiffer: Differ<Contact> = differ {
    field(Contact::email)
    nested(Contact::phone, PhoneDiffer)
}

val PersonDiffer: Differ<Person> = differ {
    nested(Person::name, FullNameDiffer)
    field(Person::nickname)
    nested(Person::contact, ContactDiffer)
    keyedList(Person::addresses, Address::id, AddressDiffer)
    nested(Person::employment, EmploymentDiffer)
    nested(Person::salary, MoneyDiffer)
    set(Person::tags)
    field(Person::lastSeenAt)
}

/**
 * What counts as a meaningful change, which is a domain decision rather than a technical one.
 *
 * Naming only what it excludes keeps the scope one line and reaches as deep as the model goes: a
 * change at `addresses[id=A1].city` is reported without anyone having to choose a depth.
 */
val PersonScope = trackScope<Person> { except(Person::lastSeenAt) }
