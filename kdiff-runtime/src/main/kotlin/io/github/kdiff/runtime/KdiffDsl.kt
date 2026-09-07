package io.github.kdiff.runtime

/**
 * Marks a kdiff builder scope, so a nested block cannot reach the members of the block enclosing it.
 *
 * The blocks nest — a routing frames a property and routes its type, a differ nests another differ —
 * and without this an inner block sees the outer builder's members too. Calling one from there
 * registers a handler for a property of the wrong type against the wrong frame: it compiles, and it
 * silently does something no one asked for.
 *
 * The standard escape applies where the crossing is deliberate: name the outer receiver explicitly.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class KdiffDsl
