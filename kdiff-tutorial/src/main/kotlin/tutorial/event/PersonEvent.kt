package tutorial.event

import tutorial.domain.Address
import tutorial.domain.AddressId
import tutorial.domain.Employment
import tutorial.domain.FullName
import tutorial.domain.PersonId
import tutorial.money.Money

/**
 * What happened, in domain language.
 *
 * No event exposes a `Change` or a `FieldPath`. An event outlives the mechanism that produced it, and
 * a subscriber should not have to know a diff was ever computed.
 */
sealed interface PersonEvent {
    val personId: PersonId
}

data class PersonRenamed(
    override val personId: PersonId,
    val before: FullName,
    val after: FullName,
) : PersonEvent

data class NicknameChanged(
    override val personId: PersonId,
    val before: String?,
    val after: String?,
) : PersonEvent

/**
 * Three events for one value object two levels down, because the domain distinguishes them: reaching
 * a new mobile number is not the same operation as correcting a dialling code.
 */
data class EmailChanged(
    override val personId: PersonId,
    val before: String?,
    val after: String?,
) : PersonEvent

data class PhoneNumberChanged(
    override val personId: PersonId,
    val before: String,
    val after: String,
) : PersonEvent

data class PhoneCountryCorrected(
    override val personId: PersonId,
    val before: String,
    val after: String,
) : PersonEvent

data class AddressAdded(override val personId: PersonId, val address: Address) : PersonEvent

data class AddressRemoved(override val personId: PersonId, val address: Address) : PersonEvent

data class AddressesReordered(
    override val personId: PersonId,
    val addressId: AddressId,
    val from: Int,
    val to: Int,
) : PersonEvent

data class AddressEdited(
    override val personId: PersonId,
    val before: Address,
    val after: Address,
) : PersonEvent

data class EmploymentChanged(
    override val personId: PersonId,
    val before: Employment,
    val after: Employment,
) : PersonEvent

data class SalaryAdjusted(
    override val personId: PersonId,
    val before: Money,
    val after: Money,
) : PersonEvent
