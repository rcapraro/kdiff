package demo

import io.github.kdiff.annotations.DiffKey
import io.github.kdiff.annotations.Diffable

@Diffable
data class Address(@DiffKey val id: String, val street: String, val city: String)
