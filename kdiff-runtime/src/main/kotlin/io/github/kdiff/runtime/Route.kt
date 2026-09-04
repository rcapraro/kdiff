package io.github.kdiff.runtime

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
public fun <T> Diff.route(block: ChangeRoutes<T>.() -> Unit): Unit =
    ChangeRoutes<T>().apply(block).dispatch(changes)

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

    internal fun dispatch(changes: List<Change>) {
        val unroutable = handlers.flatMap { (property, handler) ->
            changes.filter { it.path.rootName() == property }.takeIf { it.isNotEmpty() }?.let(handler).orEmpty()
        }

        val unhandled = changes.filter { it.path.rootName() !in handlers || it in unroutable }
        if (unhandled.isNotEmpty()) fallback?.invoke(unhandled)
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

/** `KClass.safeCast` without `kotlin-reflect`: the cast `isInstance` has already made safe. */
@Suppress("UNCHECKED_CAST")
private fun <V : Any> KClass<V>.safeCast(value: Any?): V? = if (isInstance(value)) value as V else null
