package benchmarks

import io.github.kdiff.runtime.Diff

/**
 * The pairs every benchmark compares, built once so that fixture construction is never inside a
 * measured method.
 */
internal object Fixtures {

    fun address(i: Int): Address = Address("A$i", "street $i", "city $i")

    private fun order(
        addresses: List<Address>,
        reference: String = "R-1",
        status: Status = Status.OPEN,
        note: String? = "note",
        billing: Address = address(0),
        labels: Set<String> = setOf("x", "y", "z"),
        amounts: Map<String, String> = mapOf("net" to "10", "vat" to "2"),
        code: String = "C-1",
        channel: String = "web",
        owner: String = "owner",
    ) = Order(
        reference = reference,
        status = status,
        note = note,
        billing = billing,
        shipping = address(1),
        addresses = addresses,
        tags = listOf("a", "b", "c"),
        labels = labels,
        amounts = amounts,
        code = code,
        channel = channel,
        owner = owner,
    )

    private val smallAddresses = (0 until 5).map(::address)

    /** Case 1: two instances that are equal, so nothing is reported and only the walk is measured. */
    val unchangedBefore: Order = order(smallAddresses)
    val unchangedAfter: Order = order(smallAddresses)

    /** Case 2: exactly one change, at a leaf value property. */
    val oneLeafAfter: Order = order(smallAddresses, reference = "R-2")

    /** Case 3: a keyed list of 100 with one element edited, one added and one moved. */
    private val hundred = (0 until 100).map(::address)
    private val hundredChanged = buildList {
        add(hundred[1])
        add(hundred[0])
        add(hundred[2].copy(city = "edited"))
        addAll(hundred.drop(3))
        add(address(100))
    }
    val keyedBefore: Order = order(hundred)
    val keyedAfter: Order = order(hundredChanged)

    /** Case 4: a set property and a map property, both changed. */
    val setMapAfter: Order = order(
        smallAddresses,
        labels = setOf("x", "z", "w"),
        amounts = mapOf("net" to "11", "gross" to "13"),
    )

    /** Case 5: one change six property steps down. */
    val deepBefore: L1 = L1(L2(L3(L4(L5(L6("a"))))))
    val deepAfter: L1 = L1(L2(L3(L4(L5(L6("b"))))))

    /** Cases 8 and 9: fifty changes — six value properties, and both fields of 22 keyed elements. */
    private val wideAddresses = (0 until 22).map(::address)
    val wideBefore: Order = order(wideAddresses)
    val wideAfter: Order = order(
        wideAddresses.map { it.copy(street = "${it.street}!", city = "${it.city}!") },
        reference = "R-2",
        status = Status.CLOSED,
        note = "other",
        code = "C-2",
        channel = "app",
        owner = "someone",
    )

    /** Precomputed so the tracking and routing benchmarks measure dispatch, not comparison. */
    val wideDiff: Diff = OrderDiffer.diff(wideBefore, wideAfter)
    val oneLeafChanges = OrderDiffer.diff(unchangedBefore, oneLeafAfter).changes
    val keyedChanges = OrderDiffer.diff(keyedBefore, keyedAfter).changes
}
