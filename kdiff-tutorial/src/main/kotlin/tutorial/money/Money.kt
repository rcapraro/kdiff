package tutorial.money

/**
 * Stands in for a type from someone else's library: a plain class, not a data class, so `@Diffable`
 * would reject it outright — and it is not yours to annotate anyway.
 *
 * Amounts are strings so the tutorial does not have to explain decimal arithmetic.
 */
class Money(val amount: String, val currency: String) {
    override fun equals(other: Any?): Boolean =
        other is Money && other.amount == amount && other.currency == currency

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "$amount $currency"
}
