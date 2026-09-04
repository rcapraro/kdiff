package io.github.kdiff.runtime

/**
 * A hierarchical view of a [Diff], mirroring the shape of the object graph.
 *
 * [changes] are those found exactly at this node's path; [children] hold everything deeper, keyed
 * by the next segment. The tree holds precisely the changes the flat list holds.
 */
public data class DiffNode(
    public val segment: Segment?,
    public val changes: List<Change>,
    public val children: List<DiffNode>,
) {
    public val isEmpty: Boolean
        get() = changes.isEmpty() && children.all { it.isEmpty }

    /** Every change in this subtree, in the order the flat list holds them. */
    public fun allChanges(): List<Change> = changes + children.flatMap { it.allChanges() }
}

internal fun buildTree(changes: List<Change>): DiffNode = buildNode(null, changes, depth = 0)

private fun buildNode(segment: Segment?, changes: List<Change>, depth: Int): DiffNode {
    val (here, deeper) = changes.partition { it.path.segments.size <= depth }

    val children = deeper
        .groupBy { it.path.segments[depth] }
        .map { (childSegment, grouped) -> buildNode(childSegment, grouped, depth + 1) }

    return DiffNode(segment, here, children)
}
