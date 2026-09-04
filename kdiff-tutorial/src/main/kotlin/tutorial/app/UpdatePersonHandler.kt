package tutorial.app

import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.route
import io.github.kdiff.runtime.trackedDiff
import tutorial.command.TouchPerson
import tutorial.command.UpdatePerson
import tutorial.diff.PersonDiffer
import tutorial.diff.PersonScope
import tutorial.domain.Address
import tutorial.domain.Contact
import tutorial.domain.Person
import tutorial.domain.PersonId
import tutorial.domain.Phone
import tutorial.event.AddressAdded
import tutorial.event.AddressEdited
import tutorial.event.AddressRemoved
import tutorial.event.AddressesReordered
import tutorial.event.EmailChanged
import tutorial.event.EmploymentChanged
import tutorial.event.NicknameChanged
import tutorial.event.PersonEvent
import tutorial.event.PersonRenamed
import tutorial.event.PhoneCountryCorrected
import tutorial.event.PhoneNumberChanged
import tutorial.event.SalaryAdjusted

/**
 * Turns a command into domain events by asking what it actually changes.
 *
 * Load the person, apply the command to get the person the caller wants, and compare the two through
 * the tracking scope: what comes back *is* the command's intent, decomposed into operations. Routing
 * it says what each operation means to the domain.
 */
class UpdatePersonHandler(private val repository: PersonRepository) {

    /** Changes that were tracked but that the domain has no event for, kept for an audit trail. */
    val audited = mutableListOf<Diff>()

    fun handle(command: UpdatePerson): List<PersonEvent> = decide(command.id, command::applyTo)

    fun handle(command: TouchPerson): List<PersonEvent> =
        decide(command.id) { it.copy(lastSeenAt = command.at) }

    private fun decide(id: PersonId, intent: (Person) -> Person): List<PersonEvent> {
        val current = repository.load(id) ?: error("no person $id")
        val desired = intent(current)

        val events = eventsFor(current, desired, PersonDiffer.trackedDiff(current, desired, PersonScope))

        repository.save(desired)
        return events
    }

    /**
     * Every handler names its property by reference, so a property that does not exist does not
     * compile, and each one runs once however many changes it covers — a rename reported at both
     * `name.given` and `name.family` is one rename.
     *
     * `otherwise` is the audit branch: `tags` is tracked because a change to it is worth recording,
     * but the domain has no operation for it.
     *
     * `under` frames a value object so its own properties can be named, and frames nest as deep as
     * the model does — `contact`, then `phone` inside it. A change no handler names at any depth
     * still arrives at the one `otherwise` here, so nothing goes missing by being nested. A frame's
     * body declares routes rather than handling a change, so it holds nothing but route declarations.
     */
    private fun eventsFor(current: Person, desired: Person, changes: Diff): List<PersonEvent> = buildList {
        // Read above the routing, not inside a frame: a frame body declares routes and runs when the
        // routing is declared, so a read that only makes sense for a change belongs in a handler.
        val wasPhone = current.contact.phone
        val nowPhone = desired.contact.phone

        changes.route<Person> {
            on(Person::name) { add(PersonRenamed(desired.id, current.name, desired.name)) }
            on(Person::nickname) { add(NicknameChanged(desired.id, current.nickname, desired.nickname)) }

            under(Person::contact) {
                on(Contact::email) { add(EmailChanged(desired.id, current.contact.email, desired.contact.email)) }

                under(Contact::phone) {
                    on(Phone::number) { add(PhoneNumberChanged(desired.id, wasPhone.number, nowPhone.number)) }
                    on(Phone::country) { add(PhoneCountryCorrected(desired.id, wasPhone.country, nowPhone.country)) }
                }
            }

            onEach(Person::addresses, Address::id) {
                added { add(AddressAdded(desired.id, it)) }
                removed { add(AddressRemoved(desired.id, it)) }
                moved { id, from, to -> add(AddressesReordered(desired.id, id, from, to)) }
                changed { id ->
                    val was = current.addressBy(id)
                    val now = desired.addressBy(id)
                    if (was != null && now != null) add(AddressEdited(desired.id, was, now))
                }
            }

            on(Person::employment) { add(EmploymentChanged(desired.id, current.employment, desired.employment)) }
            on(Person::salary) { add(SalaryAdjusted(desired.id, current.salary, desired.salary)) }

            otherwise { audited += Diff(it) }
        }
    }
}
