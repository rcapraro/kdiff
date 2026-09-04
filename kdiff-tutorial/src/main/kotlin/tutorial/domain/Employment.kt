package tutorial.domain

/** A sealed hierarchy: a change of subclass is a change of kind, not of value. */
sealed interface Employment

data class Employed(val employer: String, val since: String) : Employment

data class Retired(val since: String) : Employment
