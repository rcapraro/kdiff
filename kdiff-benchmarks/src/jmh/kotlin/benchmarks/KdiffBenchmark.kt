package benchmarks

import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.PatchResult
import io.github.kdiff.runtime.route
import io.github.kdiff.runtime.trackScope
import io.github.kdiff.runtime.tracker
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * The nine cases of design D11. Each isolates one decision, so a number that does not move points at
 * a specific one rather than at "the runtime".
 *
 * Open because JMH subclasses the state class to generate its harness.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class KdiffBenchmark {

    /** The floor: per-property work when nothing changed. */
    @Benchmark
    fun compareUnchanged(): Diff = OrderDiffer.diff(Fixtures.unchangedBefore, Fixtures.unchangedAfter)

    /** Single-change cost end to end. */
    @Benchmark
    fun compareOneLeafChanged(): Diff = OrderDiffer.diff(Fixtures.unchangedBefore, Fixtures.oneLeafAfter)

    /** D5, and the two-segment lift of D1. */
    @Benchmark
    fun compareKeyedList(): Diff = OrderDiffer.diff(Fixtures.keyedBefore, Fixtures.keyedAfter)

    /** D4. */
    @Benchmark
    fun compareSetAndMap(): Diff = OrderDiffer.diff(Fixtures.unchangedBefore, Fixtures.setMapAfter)

    /** The lift cost D8 would attack, isolated. */
    @Benchmark
    fun compareSixLevelsDeep(): Diff = L1Differ.diff(Fixtures.deepBefore, Fixtures.deepAfter)

    /** D7 and D9 together: one change against a twelve-property type. */
    @Benchmark
    fun applyOneChange(): PatchResult<Order> = OrderDiffer.apply(Fixtures.unchangedBefore, Fixtures.oneLeafChanges)

    /** The patch path's own key handling. */
    @Benchmark
    fun applyKeyedListRoundTrip(): PatchResult<Order> = OrderDiffer.apply(Fixtures.keyedBefore, Fixtures.keyedChanges)

    /** D6: a narrow scope over fifty changes, of which two are selected. */
    @Benchmark
    fun trackNarrowScope(): Diff {
        val tracked = tracker(OrderDiffer, Fixtures.wideBefore, narrowScope)
        return tracked.update(Fixtures.wideAfter)
    }

    /** D10: six handlers over fifty changes. */
    @Benchmark
    fun routeSixHandlers(blackhole: Blackhole) {
        Fixtures.wideDiff.route<Order> {
            on(Order::reference) { blackhole.consume(it) }
            on(Order::status) { blackhole.consume(it) }
            on(Order::note) { blackhole.consume(it) }
            on(Order::code) { blackhole.consume(it) }
            on(Order::channel) { blackhole.consume(it) }
            on(Order::owner) { blackhole.consume(it) }
            otherwise { blackhole.consume(it) }
        }
    }

    private companion object {
        val narrowScope = trackScope<Order> {
            field(Order::reference)
            field(Order::status)
        }
    }
}
