package demo

import io.github.kdiff.annotations.DiffAsValue
import io.github.kdiff.annotations.DiffIgnore
import io.github.kdiff.annotations.DiffWith
import io.github.kdiff.annotations.Diffable
import io.github.kdiff.annotations.TrackDepth
import io.github.kdiff.annotations.TrackIgnore
import io.github.kdiff.annotations.Trackable
import io.github.kdiff.runtime.Change
import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.PatchResult
import io.github.kdiff.runtime.Patcher
import io.github.kdiff.runtime.differ
import io.github.kdiff.runtime.groupByProperty
import io.github.kdiff.runtime.patchValue
import io.github.kdiff.runtime.unmatchedFailures
import java.math.BigDecimal
import java.time.Instant

enum class Status { OPEN, CLOSED }

/** A `value class`, and so a value without saying so: its equality is its single property's. */
@JvmInline
value class Sku(val code: String)

/** Declared a value, because a coordinate pair only means anything whole. */
@DiffAsValue
data class Coordinates(val lat: Double, val lon: Double)

/** Stands in for a third-party type kdiff cannot be told about by annotating it. */
class Money(val amount: String, val currency: String)

/** Hand-written for a type that cannot be annotated, and taught to patch as well as compare. */
object MoneyDiffer :
    Differ<Money> by differ({
        field(Money::amount)
        field(Money::currency)
    }),
    Patcher<Money> {
    override fun apply(before: Money, changes: List<Change>): PatchResult<Money> {
        val grouped = groupByProperty(changes, setOf("amount", "currency"))
        val amount = patchValue(before.amount, grouped.forProperty("amount"))
        val currency = patchValue(before.currency, grouped.forProperty("currency"))
        return PatchResult(
            Money(amount.value, currency.value),
            grouped.unmatchedFailures("Money") + amount.failures + currency.failures,
        )
    }
}

/** Deliberately compare-only, so the unpatchable-property path has something to exercise it. */
class Weight(val grams: String)

object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })

@Diffable
sealed interface Payment {
    val amount: String
}

@Diffable
data class Card(override val amount: String, val last4: String) : Payment

@Diffable
data class Transfer(override val amount: String, val iban: String) : Payment

/** A payload-free case, which needs no annotation of its own: a singleton has nothing to compare. */
data object Unpaid : Payment {
    override val amount: String get() = "0"
}

@Diffable
@Trackable(depth = 1)
data class Order(
    val reference: String,
    val status: Status,
    @TrackIgnore val note: String?,
    @DiffIgnore val lastTouched: String,
    @TrackDepth(2) val billing: Address,
    val shipping: Address?,
    val addresses: List<Address>,
    val tags: List<String>,
    /** Nullable, so the sample covers a collection that can appear and disappear as well as change. */
    val couponCodes: List<String>?,
    val labels: Set<String>,
    val amounts: Map<String, String>,
    val payment: Payment,
    @DiffWith(MoneyDiffer::class) val total: Money,
    @DiffWith(WeightDiffer::class) val weight: Weight,
    /** A standard-library value: compared by equality, so `10` and `10.00` differ. */
    val discount: BigDecimal,
    val placedAt: Instant,
    val sku: Sku,
    val location: Coordinates,
)

/**
 * The property-level route to a value: `Address` is `@Diffable`, and this property alone is one value.
 *
 * A change inside it reports at `at` and nothing beneath it — what `field(Site::at)` would report.
 */
@Diffable
data class Site(val name: String, @DiffAsValue val at: Address)

/**
 * The inherited route to a value: declared on a sealed parent's property, honoured by every subclass
 * that overrides it.
 *
 * Kotlin puts none of an overridden declaration's annotations on the `override`, so a subclass's
 * differ reads them off the declaration it overrides. Without that, `@DiffAsValue` here would hold
 * across a subclass swap — where the parent's own properties are compared — and be ignored for two
 * instances of one subclass, which is the common case.
 */
@Diffable
sealed interface Shipment {
    @DiffAsValue val origin: Address
}

@Diffable
data class Parcel(override val origin: Address, val tracking: String) : Shipment

@Diffable
data class Pallet(override val origin: Address, val height: String) : Shipment
