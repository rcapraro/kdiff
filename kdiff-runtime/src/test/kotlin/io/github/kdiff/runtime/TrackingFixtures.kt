package io.github.kdiff.runtime

/**
 * A model shaped like one the processor would generate for, with hand-written differs standing in for
 * generated ones. Nesting reaches three property steps so depth has something to exclude, and every
 * collection kind is present so element identity can be shown not to consume depth.
 */
internal data class Country(val code: String)

internal data class Addr(val id: String, val street: String, val city: String, val country: Country)

internal sealed interface Payment {
    val amount: String
}

internal data class Card(override val amount: String, val last4: String) : Payment

internal data class Transfer(override val amount: String, val iban: String) : Payment

internal data class Order(
    val reference: String,
    val status: String,
    val note: String?,
    val billing: Addr,
    val shipping: Addr?,
    val addresses: List<Addr>,
    val tags: List<String>,
    val labels: Set<String>,
    val amounts: Map<String, String>,
    val payment: Payment,
)

internal object CountryDiffer : Differ<Country> by differ({ field(Country::code) })

internal object AddrDiffer : Differ<Addr> {
    override fun diff(before: Addr, after: Addr): Diff = Diff(
        buildList {
            compareValue("id", before.id, after.id)
            compareValue("street", before.street, after.street)
            compareValue("city", before.city, after.city)
            compareNested("country", before.country, after.country, CountryDiffer)
        },
    )
}

internal object CardDiffer : Differ<Card> by differ({
    field(Card::amount)
    field(Card::last4)
})

internal object TransferDiffer : Differ<Transfer> by differ({
    field(Transfer::amount)
    field(Transfer::iban)
})

internal object PaymentDiffer : Differ<Payment> {
    override fun diff(before: Payment, after: Payment): Diff = Diff(
        buildList {
            when {
                before is Card && after is Card -> addAll(CardDiffer.diff(before, after).changes)
                before is Transfer && after is Transfer -> addAll(TransferDiffer.diff(before, after).changes)
                else -> {
                    add(
                        TypeChanged(
                            FieldPath.ROOT,
                            before::class.simpleName.orEmpty(),
                            after::class.simpleName.orEmpty(),
                            before,
                            after,
                        ),
                    )
                    compareValue("amount", before.amount, after.amount)
                }
            }
        },
    )
}

internal object OrderDiffer : Differ<Order> {
    override fun diff(before: Order, after: Order): Diff = Diff(
        buildList {
            compareValue("reference", before.reference, after.reference)
            compareValue("status", before.status, after.status)
            compareValue("note", before.note, after.note)
            compareNested("billing", before.billing, after.billing, AddrDiffer)
            compareNestedNullable("shipping", before.shipping, after.shipping, AddrDiffer)
            compareKeyedList("addresses", "id", before.addresses, after.addresses, AddrDiffer) { it.id }
            comparePositionalList("tags", before.tags, after.tags, null)
            compareSet("labels", before.labels, after.labels)
            compareMap("amounts", before.amounts, after.amounts, null)
            compareNested("payment", before.payment, after.payment, PaymentDiffer)
        },
    )
}

/** Stands in for the differ generated for a `@Trackable` class: the same object carries the scope. */
internal class TrackedOrderDiffer(scope: TrackScope<Order>) :
    Differ<Order> by OrderDiffer,
    Tracked<Order> {
    override val trackScope: TrackScope<Order> = scope
}

internal val ADDR_1 = Addr("A1", "1 Rue X", "Lyon", Country("FR"))
internal val ADDR_2 = Addr("A2", "2 Rue Y", "Lyon", Country("FR"))
internal val ADDR_3 = Addr("A3", "3 Rue Z", "Nice", Country("FR"))

internal val ORDER = Order(
    reference = "R1",
    status = "OPEN",
    note = null,
    billing = ADDR_1,
    shipping = null,
    addresses = listOf(ADDR_1, ADDR_2),
    tags = listOf("a", "b"),
    labels = setOf("x"),
    amounts = mapOf("eur" to "1.0"),
    payment = Card("10", "1234"),
)

/** Paths of the changes a tracker reported, which is what nearly every scenario asserts on. */
internal fun Diff.paths(): List<String> = changes.map { it.path.toString() }
