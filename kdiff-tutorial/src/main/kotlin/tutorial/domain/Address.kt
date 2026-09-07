package tutorial.domain

/**
 * An element with an identity of its own, which is what lets a list of them be compared by identity
 * rather than by position — so a reordered address reports as moved, and an edited one at its key.
 */
data class Address(val id: AddressId, val line1: String, val city: String, val country: String)
