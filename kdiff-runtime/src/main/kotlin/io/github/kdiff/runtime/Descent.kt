package io.github.kdiff.runtime

/*
 * The bound on how deep comparing and applying will descend, and the identity recording that tells a
 * cycle apart from a structure that is merely deep.
 *
 * `Differ.diff` takes two instances and nothing else, and adding a depth parameter would change the
 * signature generated code is written against — so the counter lives in a thread-local instead. It is
 * touched once per *nested delegation*, never once per property or per change: the descent points are
 * the handful of runtime helpers that hand a value to another differ or patcher.
 *
 * Reading the thread-local once per *element* cost 4–10% throughput on the comparison benchmarks. So
 * the state is fetched once per helper call — `Descent.current()` — and stepped with `State.into` for
 * each element, which is a field write and nothing more. `into` is inline so a descent costs no
 * lambda, and everything it touches is `internal` for that reason rather than because anything
 * outside this file should read it.
 *
 * Below the watch window no identity is recorded, because an `IdentityHashMap` insert per nested
 * value would land on the paths the 0.3.1 allocation work cleared. Inside the window the value and
 * its segment are kept, so at the bound the refusal can say both where the descent stopped and
 * whether an instance was re-entered.
 */
internal object Descent {
    /**
     * Where identity recording begins. A cycle whose period fits in this window is recognised as one;
     * a longer cycle is still refused, and the message then says no repeat was observed rather than
     * claiming there is none.
     */
    internal const val WATCH_WINDOW: Int = 64
    internal const val WATCH: Int = MAX_DESCENT - WATCH_WINDOW

    internal class State {
        @JvmField
        var depth: Int = 0

        @JvmField
        val trail: ArrayList<Any?> = ArrayList(WATCH_WINDOW)

        @JvmField
        val segments: ArrayList<Segment> = ArrayList(WATCH_WINDOW)
    }

    private val state: ThreadLocal<State> = ThreadLocal.withInitial { State() }

    /** This thread's descent state. Fetch once per helper call, then step with [State.into]. */
    internal fun current(): State = state.get()

    /** One descent, for a helper that makes exactly one and so has no state to reuse. */
    internal inline fun <R> into(segment: Segment, value: Any?, body: () -> R): R = current().into(segment, value, body)
}

/**
 * Runs [body] one level deeper on an already-fetched state, refusing the structure past
 * [MAX_DESCENT].
 *
 * [segment] and [value] are read only once the depth reaches the watch window, so an ordinary
 * descent pays two field writes and nothing else.
 */
internal inline fun <R> Descent.State.into(segment: Segment, value: Any?, body: () -> R): R {
    val next = depth + 1
    if (next > MAX_DESCENT) refuse(segment, value)

    depth = next
    val watching = next > Descent.WATCH
    if (watching) {
        trail.add(value)
        segments.add(segment)
    }

    try {
        return body()
    } finally {
        depth = next - 1
        if (watching) {
            trail.removeAt(trail.size - 1)
            segments.removeAt(segments.size - 1)
        }
        // A thread that finished a comparison keeps two empty lists, not a graph.
        if (depth == 0 && trail.isNotEmpty()) {
            trail.clear()
            segments.clear()
        }
    }
}

/** Reference equality: a cycle is the same instance reached again, not an equal one. */
internal fun Descent.State.refuse(segment: Segment, value: Any?): Nothing {
    val path = FieldPath(
        ArrayList<Segment>(segments.size + 1).also {
            it += segments
            it += segment
        },
    )
    throw CyclicStructureException(path, value != null && trail.any { it === value })
}
