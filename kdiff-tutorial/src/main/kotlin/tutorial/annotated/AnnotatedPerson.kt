package tutorial.annotated

import io.github.kdiff.annotations.DiffIgnore
import io.github.kdiff.annotations.DiffKey
import io.github.kdiff.annotations.DiffWith
import io.github.kdiff.annotations.Diffable
import io.github.kdiff.annotations.TrackIgnore
import io.github.kdiff.annotations.Trackable
import tutorial.diff.MoneyDiffer
import tutorial.money.Money

/**
 * The same model, described by annotation instead of by hand.
 *
 * There is no second file: `@Diffable` generates `PersonDiffer`, and `@Trackable` makes it carry the
 * tracking scope that `trackScope { except(Person::lastSeenAt) }` builds on the other route.
 * `AnnotatedParitySpec` holds the two to the same output.
 *
 * The annotations that earn their place here are the ones that say something the property type does
 * not: `@DiffKey` gives a list element its identity, `@DiffWith` reaches a type that is not yours to
 * annotate, `@DiffIgnore` excludes a property from comparison — the counterpart of naming it nowhere
 * in the builder — and `@TrackIgnore` excludes one from tracking, the counterpart of `except`.
 */
@Diffable
@Trackable
data class Person(
    @DiffIgnore val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val contact: Contact,
    val addresses: List<Address>,
    val employment: Employment,
    @DiffWith(MoneyDiffer::class) val salary: Money,
    val tags: Set<String>,
    @TrackIgnore val lastSeenAt: String,
)

@Diffable
data class FullName(val given: String, val family: String) {
    override fun toString(): String = "$given $family"
}

/** Nesting an annotated type inside another needs no annotation for the nesting itself. */
@Diffable
data class Contact(val email: String?, val phone: Phone)

@Diffable
data class Phone(val country: String, val number: String)

/** `@DiffIgnore` on the key for the reason the builder names no key either: matched elements share it. */
@Diffable
data class Address(
    @DiffKey @DiffIgnore val id: AddressId,
    val line1: String,
    val city: String,
    val country: String,
)

@Diffable
sealed interface Employment

@Diffable
data class Employed(val employer: String, val since: String) : Employment

@Diffable
data class Retired(val since: String) : Employment

data class PersonId(val value: String) {
    override fun toString(): String = value
}

data class AddressId(val value: String) {
    override fun toString(): String = value
}
