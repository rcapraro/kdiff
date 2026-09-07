package io.github.kdiff.runtime

private const val NO_CHANGES = "no changes"

internal fun renderChanges(changes: List<Change>): String {
    if (changes.isEmpty()) return NO_CHANGES

    val paths = changes.map { it.path.toString() }
    val width = paths.maxOf { it.length }
    return changes.mapIndexed { index, change ->
        "${paths[index].padEnd(width)}  ${change.describe()}"
    }.joinToString("\n")
}

private fun Change.describe(): String = when (this) {
    is ValueChanged -> "${before.quoted()} -> ${after.quoted()}"
    is Added -> "ADDED ${value.quoted()}"
    is Removed -> "REMOVED ${value.quoted()}"
    is TypeChanged -> "TYPE $beforeType -> $afterType"
    is Moved -> "MOVED $from -> $to"
}

private fun Any?.quoted(): String = when (this) {
    null -> "null"
    is String -> "\"$this\""
    else -> toString()
}
