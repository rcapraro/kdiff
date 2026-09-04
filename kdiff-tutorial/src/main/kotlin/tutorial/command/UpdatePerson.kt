package tutorial.command

import tutorial.domain.Address
import tutorial.domain.Employment
import tutorial.domain.FullName
import tutorial.domain.Person
import tutorial.domain.PersonId
import tutorial.money.Money

/**
 * What a caller may set, and who to set it on. No identity to overwrite, no bookkeeping fields.
 *
 * A differ compares two instances of *one* type, so a command is not something to diff a person
 * against. [applyTo] turns it into the person the caller is asking for, and `copy` carries everything
 * the command has no field for across untouched — which is what makes the difference between the two
 * exactly the command's intent, decomposed.
 */
data class UpdatePerson(
    val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val addresses: List<Address>,
    val employment: Employment,
    val salary: Money,
    val tags: Set<String>,
) {
    fun applyTo(current: Person): Person = current.copy(
        name = name,
        nickname = nickname,
        addresses = addresses,
        employment = employment,
        salary = salary,
        tags = tags,
    )
}

/** Marks a person as seen, which asks for no domain change at all. */
data class TouchPerson(val id: PersonId, val at: String)
