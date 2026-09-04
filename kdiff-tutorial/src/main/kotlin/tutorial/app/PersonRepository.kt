package tutorial.app

import tutorial.domain.Person
import tutorial.domain.PersonId

interface PersonRepository {
    fun load(id: PersonId): Person?

    fun save(person: Person)
}

/** A map. A real store would add nothing to what the tutorial teaches. */
class InMemoryPersonRepository(seed: List<Person> = emptyList()) : PersonRepository {
    private val people = seed.associateBy { it.id }.toMutableMap()

    override fun load(id: PersonId): Person? = people[id]

    override fun save(person: Person) {
        people[person.id] = person
    }
}
