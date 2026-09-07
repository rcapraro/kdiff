package benchmarks

import io.github.kdiff.annotations.DiffKey
import io.github.kdiff.annotations.Diffable
import io.github.kdiff.annotations.TrackDepth
import io.github.kdiff.annotations.Trackable

/**
 * A model shaped like `kdiff-sample`'s `Order`, declared here rather than depended on so the
 * benchmarks stay readable next to the numbers they produce and cannot be perturbed by an edit made
 * to the sample for a documentation reason.
 */

enum class Status { OPEN, CLOSED }

@Diffable
data class Address(@DiffKey val id: String, val street: String, val city: String)

@Diffable
@Trackable(depth = 1)
data class Order(
    val reference: String,
    val status: Status,
    val note: String?,
    @TrackDepth(2) val billing: Address,
    val shipping: Address?,
    val addresses: List<Address>,
    val tags: List<String>,
    val labels: Set<String>,
    val amounts: Map<String, String>,
    val code: String,
    val channel: String,
    val owner: String,
)

/**
 * Six property steps from root to `value`, so the cost of lifting a nested change through every
 * level is separable from everything else the comparison does (design D8).
 */
@Diffable
data class L6(val value: String)

@Diffable
data class L5(val next: L6)

@Diffable
data class L4(val next: L5)

@Diffable
data class L3(val next: L4)

@Diffable
data class L2(val next: L3)

@Diffable
data class L1(val next: L2)
