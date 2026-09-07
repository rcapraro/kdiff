package io.github.kdiff.runtime

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Dispatches the changes of this diff to handlers, each naming the property it handles.
 *
 * The way a caller decides what a diff *means*: which domain operation a change asks for, which event
 * it becomes, which of them are merely audited. A property is named by reference, so a name that does
 * not exist does not compile — and routing reads only the diff, so it behaves the same whether the
 * differ was generated from annotations or written by hand.
 *
 * ```
 * val events = buildList {
 *     diff.route<Person> {
 *         on(Person::name) { add(PersonRenamed(id, current.name, desired.name)) }
 *
 *         onEach(Person::addresses, Address::id) {
 *             added { add(AddressAdded(id, it)) }
 *             moved { key, from, to -> add(AddressesReordered(id, key, from, to)) }
 *             changed { key -> add(AddressEdited(id, desired.addressBy(key)!!)) }
 *         }
 *
 *         otherwise { audited += Diff(it) }
 *     }
 * }
 * ```
 *
 * A handler runs at most once, and only when the property it names has at least one change: a rename
 * reported at both `name.given` and `name.family` is one rename, not two. Changes no handler names
 * reach [ChangeRoutes.otherwise], as does a change at the root of [T], which belongs to no property.
 */
public fun <T> Diff.route(block: ChangeRoutes<T>.() -> Unit) {
    ChangeRoutes<T>().apply(block).dispatch(changes)
}

public class ChangeRoutes<T> internal constructor() {
    private val handlers = linkedMapOf<String, (List<Change>) -> List<Change>>()

    private var fallback: ((List<Change>) -> Unit)? = null

    /** Handles the changes sitting under [property], once, when there is at least one. */
    public fun on(property: KProperty1<T, *>, handler: (List<Change>) -> Unit) {
        register(property.name) { changes ->
            handler(changes)
            emptyList()
        }
    }

    /**
     * Handles the elements of the keyed collection [property], reached at their own types.
     *
     * [key] is the property the elements are identified by, and the one a path segment carries — the
     * same reference the differ was told to compare by.
     */
    public inline fun <reified E : Any, reified K : Any> onEach(
        property: KProperty1<T, Collection<E>>,
        key: KProperty1<E, K>,
        block: KeyedElementRoutes<E, K>.() -> Unit,
    ) {
        val routes = KeyedElementRoutes<E, K>(E::class, K::class).apply(block)
        register(property.name) { routes.dispatch(it) }
    }

    /**
     * Handles the elements of the unkeyed collection [property].
     *
     * Only additions and removals: without a key there is nothing to recognise a moved element by, and
     * nothing to identify an element changed in place. Those changes reach [otherwise] instead of
     * disappearing — a routing that cannot express something says so.
     */
    public inline fun <reified E : Any> onEach(
        property: KProperty1<T, Collection<E>>,
        block: ElementRoutes<E>.() -> Unit,
    ) {
        val routes = ElementRoutes(E::class).apply(block)
        register(property.name) { routes.dispatch(it) }
    }

    /**
     * Routes the changes beneath [property] against that property's own type.
     *
     * A frame offers every route a routing offers, one level down, and frames nest as deep as the
     * model does. Dispatching through a frame is the same as comparing the nested value itself and
     * routing that diff: the same handlers run, receiving the same changes at paths rooted at the
     * nested type.
     *
     * ```
     * diff.route<Order> {
     *     under(Order::billing) {
     *         on(Addr::city) { .. }
     *         under(Addr::country) { on(Country::code) { .. } }
     *     }
     * }
     * ```
     *
     * A change reported *at* [property] rather than beneath it — the value change a nullable nested
     * value reports when it appears or disappears — has nothing left to dispatch, and is treated as
     * unhandled the way a change at the root of [T] already is.
     *
     * A change no handler in the frame names goes to the frame's own [otherwise] when it declares
     * one, and is otherwise handed back to this routing at the path it arrived with. So one
     * [otherwise] at the outermost routing sees every change unnamed at any depth.
     *
     * One call serves a nullable nested value and a non-null one, as in `DifferBuilder.nested`,
     * because a property reference is covariant in the value it reads.
     *
     * [block] declares the frame's routes and runs **once, when the routing is declared** — unlike an
     * [on] handler, which runs only when its property has a change. So a statement in a frame body
     * that is not a route declaration runs whether or not anything changed, and a read that is only
     * safe when the nested value changed belongs inside a handler rather than beside one.
     */
    public fun <V : Any> under(property: KProperty1<T, V?>, block: ChangeRoutes<V>.() -> Unit) {
        val frame = ChangeRoutes<V>().apply(block)
        register(property.name) { changes ->
            val (beneath, atProperty) = changes.partition { it.path.segments.size > 1 }
            val descended = beneath.map { it.withoutRoot() }

            // dispatch returns an order-preserving subsequence of what it was given, and every
            // re-rooted change is a fresh instance, so one walk in step restores each change to the
            // instance it came from. An IndexOutOfBounds here would mean dispatch had started copying.
            var cursor = 0
            atProperty + frame.dispatch(descended).map { returned ->
                while (descended[cursor] !== returned) cursor++
                beneath[cursor++]
            }
        }
    }

    /** Handles every change no other handler names, once, when there is at least one. */
    public fun otherwise(handler: (List<Change>) -> Unit) {
        require(fallback == null) { "a routing declares one otherwise handler; this is the second" }
        fallback = handler
    }

    // Two handlers for one property is a mistake with no sensible resolution: running both duplicates
    // whatever they do, running the first drops the second in silence.
    @PublishedApi
    internal fun register(property: String, handler: (List<Change>) -> List<Change>) {
        require(property !in handlers) { "$property is named by more than one handler" }
        handlers[property] = handler
    }

    /** Dispatches [changes], returning those neither a handler nor [otherwise] accounted for. */
    internal fun dispatch(changes: List<Change>): List<Change> {
        // Grouped once rather than filtered once per handler; appending in change order is what gives
        // each handler its changes in the order the diff reports them, so the map's own iteration
        // order is never read. Only what a handler named is grouped: everything else is unhandled by
        // the test below, and gathering it here would build a list for changes no handler is shown.
        val byProperty = HashMap<String, MutableList<Change>>()
        changes.forEach { change ->
            val root = change.path.rootName() ?: return@forEach
            if (root in handlers) byProperty.getOrPut(root) { mutableListOf() } += change
        }

        // Identity, not equality: a handler hands back the very changes it declined, and `under`
        // already matches returned changes to given ones by reference, so this agrees with it. Built
        // only once something is actually declined, which for a routing whose handlers accept
        // everything is never.
        var unroutable: MutableSet<Change>? = null
        handlers.forEach { (property, handler) ->
            val forProperty = byProperty[property] ?: return@forEach
            val declined = handler(forProperty)
            if (declined.isEmpty()) return@forEach
            val seen = unroutable ?: Collections.newSetFromMap(IdentityHashMap<Change, Boolean>())
                .also { unroutable = it }
            seen += declined
        }

        val declined = unroutable
        val unhandled = changes.filter {
            it.path.rootName() !in handlers || (declined != null && it in declined)
        }
        if (unhandled.isEmpty()) return emptyList()

        // A fallback consumes what it is given; without one the caller decides, which is how a frame
        // hands its leftovers back to the routing that framed it.
        val fallback = fallback ?: return unhandled
        fallback(unhandled)
        return emptyList()
    }
}

/**
 * What to do about the elements of one collection property.
 *
 * A kind of change left undeclared here is ignored: the property was handled, and declining one of its
 * kinds is a decision. A change this routing has no *shape* for is different — it goes back to the
 * caller, which sends it to `otherwise`.
 */
public open class ElementRoutes<E : Any> @PublishedApi internal constructor(
    private val element: KClass<E>,
) {
    private var added: ((E) -> Unit)? = null
    private var removed: ((E) -> Unit)? = null

    /** Handles each element present only in the new instance. */
    public fun added(handler: (E) -> Unit) {
        added = handler
    }

    /** Handles each element present only in the old instance. */
    public fun removed(handler: (E) -> Unit) {
        removed = handler
    }

    /** Dispatches [changes], returning those this routing has no shape for. */
    @PublishedApi
    internal open fun dispatch(changes: List<Change>): List<Change> = buildList {
        changes.forEach { change ->
            val delivered = when (change) {
                is Added -> deliver(change.value, added)
                is Removed -> deliver(change.value, removed)
                else -> false
            }
            if (!delivered) add(change)
        }
    }

    /** True when [handler] ran, and true when there is none — declining a kind is a decision. */
    private fun deliver(value: Any?, handler: ((E) -> Unit)?): Boolean {
        if (handler == null) return true
        val element = element.safeCast(value) ?: return false
        handler(element)
        return true
    }
}

/** The same, for a collection whose elements carry a key — which is what a move and an edit need. */
public class KeyedElementRoutes<E : Any, K : Any> @PublishedApi internal constructor(
    element: KClass<E>,
    private val key: KClass<K>,
) : ElementRoutes<E>(element) {
    private var moved: ((K, Int, Int) -> Unit)? = null
    private var changed: ((K) -> Unit)? = null

    /** Handles each element that kept its identity and changed position, with both positions. */
    public fun moved(handler: (key: K, from: Int, to: Int) -> Unit) {
        moved = handler
    }

    /**
     * Handles each element that changed in place, once — however many of its properties changed.
     *
     * An element edited in three places is one edit to the domain, so the handler receives the key and
     * reads the element it needs from the instance it already holds.
     */
    public fun changed(handler: (key: K) -> Unit) {
        changed = handler
    }

    @PublishedApi
    override fun dispatch(changes: List<Change>): List<Change> {
        val edited = linkedSetOf<K>()
        val unroutable = mutableListOf<Change>()

        super.dispatch(changes).forEach { change ->
            val elementKey = keyOf(change)
            when {
                elementKey == null -> unroutable += change
                change is Moved -> moved?.invoke(elementKey, change.from, change.to)
                else -> edited += elementKey
            }
        }

        changed?.let { handler -> edited.forEach(handler) }
        return unroutable
    }

    private fun keyOf(change: Change): K? =
        key.safeCast((change.path.segments.getOrNull(1) as? Segment.Key)?.value)
}

/**
 * This change with the first segment of its path dropped, so a routing frame can dispatch it against
 * the type it reached.
 *
 * The descent reconstruction also performs, and the same one: both go through `Change.withPath`, which
 * holds the single exhaustive `when` a sixth [Change] variant would have to be added to. Spelling it
 * out again here would mean a variant could be added to one copy and forgotten in the other.
 */
internal fun Change.withoutRoot(): Change = withPath(path.withoutFirst())

/** `KClass.safeCast` without `kotlin-reflect`: the cast `isInstance` has already made safe. */
@Suppress("UNCHECKED_CAST")
private fun <V : Any> KClass<V>.safeCast(value: Any?): V? = if (isInstance(value)) value as V else null
