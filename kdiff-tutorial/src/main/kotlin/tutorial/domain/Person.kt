package tutorial.domain

import tutorial.money.Money

/**
 * The aggregate: one immutable data class, and the only type compared.
 *
 * Nothing in this package imports kdiff. The model is ordinary Kotlin a domain expert could read,
 * and everything kdiff needs to know about it is described in one file elsewhere — `tutorial.diff`.
 *
 * [lastSeenAt] is bookkeeping: it is compared like any other property, but the tracking scope leaves
 * it out, so touching it alone asks for nothing and produces no event.
 */
data class Person(
    val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val addresses: List<Address>,
    val employment: Employment,
    val salary: Money,
    val tags: Set<String>,
    val lastSeenAt: String,
) {
    fun addressBy(id: AddressId): Address? = addresses.firstOrNull { it.id == id }
}

/**
 * Identifiers are single-property data classes rather than inline value classes: a `value class`
 * cannot be a data class, and equality is what a diff compares by.
 *
 * [toString] is overridden because a key is retained in a path as its own value and rendered through
 * `toString`. Without it a path reads `addresses[id=AddressId(value=A1)].city`.
 */
data class PersonId(val value: String) {
    override fun toString(): String = value
}

data class AddressId(val value: String) {
    override fun toString(): String = value
}

/** A value object: two fields, so a rename is reported one level down, at `name.family`. */
data class FullName(val given: String, val family: String) {
    override fun toString(): String = "$given $family"
}
