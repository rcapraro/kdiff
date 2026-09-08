# Tutorial: commands, diffs and domain events

The reference guides explain kdiff feature by feature. This one builds something: a small
domain-driven application where an incoming command is *diffed* against current state to work out what
it actually asks to change, and each change becomes the domain event that says what it means.

That is the idea worth taking away. Given the state you hold and the state a caller wants, the diff
between them **is** the command's intent, decomposed into operations.

Which makes the whole application one line of reasoning, from a command to the events it meant:

```
   UpdatePerson                 the caller's request: what may be set,
        |                       no identity, no bookkeeping
        | applyTo(current)      copy() carries the rest across untouched
        v
    desired  ---------+
                      |
    current  ---------+---> PersonDiffer.trackedDiff(current, desired, PersonScope)
        ^                              |
        |                              |  PersonScope = except(lastSeenAt)
   repository                          |  "is this difference MEANINGFUL?"
                                       v
                                    Diff  -- a flat, ordered list of changes
                                       |
                                       | route<Person> { }
                                       |  "what does this change MEAN?"
                       +---------------+----------------+
                       |               |                |
                on(Person::name)  under(::contact)  onEach(::addresses)
                       |               |                |
                       v               v                v
                  PersonRenamed   EmailChanged     AddressAdded
                                  PhoneNumber...   AddressesReordered
                                                   AddressEdited
                       |               |                |
                       +---------------+----------------+
                                       |
                                       |  named by no handler at any depth
                                       v
                                  otherwise  -->  audited
```

Three questions, asked in order and answered in three different places: the differ says *what
differs*, the scope says *what counts*, and the routing says *what it means*. None of them knows about
the others.

The domain here carries no kdiff annotation at all — it is ordinary Kotlin a domain expert could read,
and everything kdiff knows about it lives in one file elsewhere. If you came for the annotations, the
[same model annotated](#the-same-model-annotated) is at the end and reaches the identical result —
jump there and read back. A test in the module holds the two routes to the same output.

Everything here is real, compiled code in the `kdiff-tutorial` module. Run it:

```
./gradlew :kdiff-tutorial:run
```

## The domain, which knows nothing about kdiff

One immutable data class, and the only type compared:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/domain/Person.kt -->
```kotlin
data class Person(
    val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val contact: Contact,
    val addresses: List<Address>,
    val employment: Employment,
    val salary: Money,
    val tags: Set<String>,
    val lastSeenAt: String,
) {
    fun addressBy(id: AddressId): Address? = addresses.firstOrNull { it.id == id }
}
```

Identifiers are single-property data classes, not inline value classes: a `value class` cannot be a
data class, and equality is what a diff compares by. They override `toString`, which matters more than
it looks — a key is retained in a path as its own value and rendered with `toString`, so without it a
path reads `addresses[id=AddressId(value=A1)].city`.

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/domain/Person.kt -->
```kotlin
data class AddressId(val value: String) {
    override fun toString(): String = value
}
```

An address has an identity of its own, which is what will let a list of them be compared by identity
rather than by position:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/domain/Address.kt -->
```kotlin
data class Address(val id: AddressId, val line1: String, val city: String, val country: String)
```

A contact holds a phone, so the model is three levels deep at one property — a changed number is
reported at `contact.phone.number`:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/domain/Contact.kt -->
```kotlin
data class Contact(val email: String?, val phone: Phone)

data class Phone(val country: String, val number: String)
```

Employment is sealed, so a change of subclass is a change of kind rather than of value:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/domain/Employment.kt -->
```kotlin
sealed interface Employment

data class Employed(val employer: String, val since: String) : Employment

data class Retired(val since: String) : Employment
```

And `Money` stands in for a type from someone else's library — a plain class, not a data class, and not
yours to annotate:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/money/Money.kt -->
```kotlin
class Money(val amount: String, val currency: String) {
    override fun equals(other: Any?): Boolean =
        other is Money && other.amount == amount && other.currency == currency
```

## Describing it, in one file

Everything kdiff needs is a list of properties and how to compare each. One call per shape:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val PersonDiffer: Differ<Person> = differ {
    nested(Person::name, FullNameDiffer)
    field(Person::nickname)
    nested(Person::contact, ContactDiffer)
    keyedList(Person::addresses, Address::id, AddressDiffer)
    nested(Person::employment, EmploymentDiffer)
    nested(Person::salary, MoneyDiffer)
    set(Person::tags)
    field(Person::lastSeenAt)
}
```

`keyedList` is the one worth pausing on. Naming `Address::id` as the key makes a reordered address
report as **moved** rather than as a removal plus an addition, and an edited one report at its key:
`addresses[id=A1].city`. The key reference supplies both halves the comparison needs — the name a path
segment carries and the value elements are matched by — so they cannot get out of step.

The element differ names no `id`, and that is deliberate: two elements matched *by* their key are equal
in it by construction, so comparing it could only ever report nothing.

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val AddressDiffer: Differ<Address> = differ {
    field(Address::line1)
    field(Address::city)
    field(Address::country)
}
```

`subtype` handles the sealed hierarchy: two instances of one subtype are compared by that subtype's
differ, and a swap reports a type change at the property.

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val EmploymentDiffer: Differ<Employment> = differ {
    subtype(
        Employed::class,
        differ {
            field(Employed::employer)
            field(Employed::since)
        },
    )
    subtype(Retired::class, differ { field(Retired::since) })
}
```

`Money` gets an `object` rather than a `val`, so the annotated mirror can point `@DiffWith` at it — a
`val` would do for this route:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
object MoneyDiffer : Differ<Money> by differ({
    field(Money::amount)
    field(Money::currency)
})
```

## What counts as a change

`PersonDiffer` reports everything that differs, `lastSeenAt` included. Whether a difference is a
*meaningful* change is a domain decision, and it lives in the scope:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val PersonScope = trackScope<Person> { except(Person::lastSeenAt) }
```

One line, and it disposes of the trap that used to cost readers of this page an hour. A scope naming
what it *tracks* also has to say how deep to follow each property, counted in property steps — and
since most of a DDD model is value objects, the natural-looking depth of 1 reports nothing for a
rename, because a rename is reported at `name.family`, two steps down. A scope naming only what it
excludes reaches as deep as the model goes, so nobody has to count.

## The command, and why it must be projected

A command carries what a caller may set — no identity, no bookkeeping:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/command/UpdatePerson.kt -->
```kotlin
data class UpdatePerson(
    val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val contact: Contact,
    val addresses: List<Address>,
    val employment: Employment,
    val salary: Money,
    val tags: Set<String>,
)
```

Here is the step that is easy to miss, and without it nothing else works. **A differ compares two
instances of one type.** An `UpdatePerson` is not a `Person`, so there is nothing to diff it against —
you will not get a confusing error, you will simply have no differ to call. So the command is applied
to the current person to produce the person the caller is asking for:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/command/UpdatePerson.kt -->
```kotlin
    fun applyTo(current: Person): Person = current.copy(
        name = name,
        nickname = nickname,
        contact = contact,
        addresses = addresses,
        employment = employment,
        salary = salary,
        tags = tags,
    )
```

`copy` carries identity and `lastSeenAt` across untouched, so a command can never change what it has no
field for. Now there are two `Person`s, and the difference between them is the command's intent.

## The handler is four lines

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
    private fun decide(id: PersonId, intent: (Person) -> Person): List<PersonEvent> {
        val current = repository.load(id) ?: error("no person $id")
        val desired = intent(current)

        val events = eventsFor(current, desired, PersonDiffer.trackedDiff(current, desired, PersonScope))

        repository.save(desired)
        return events
    }
```

`trackedDiff` applies the scope to one comparison and holds nothing — a `Tracker` is for an instance
that keeps evolving, and a handler holds two instances that do not.

And the state after the command is simply `desired`. There is no mutable aggregate applying the changes
one at a time, which removes three things this tutorial used to have to teach: no-op guards (the diff
already proves every change is real), index clamping when a reorder follows a removal (nothing is
applied incrementally), and a `rehydrate`/`snapshot` pair around a state class that existed only to have
something to diff.

## From a change to an event

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
    private fun eventsFor(current: Person, desired: Person, changes: Diff): List<PersonEvent> = buildList {
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
```

Five things are happening there that are worth naming.

**A property is named by reference, so a typo does not compile.** `Person::nickanme` is a compile
error; `"nickanme"` would have been a perfectly good `String` matching nothing, for ever.

**A handler runs once per property, not once per change.** A rename is reported at `name.given` *and*
`name.family`, and one rename is one event. Same for an address edited in three places: `changed` fires
once for that element and hands over its key.

**Elements and keys arrive at their own types.** `added` receives an `Address`, `moved` receives an
`AddressId` — no casts, no `as?`, no strings.

**A value object is framed with `under`, and frames nest as deep as the model does.** Inside a frame
every route means what it means at the top level, one level down, so `on(Contact::email)` names a
property of `Contact` and the frame inside it names properties of `Phone`. Three events come out of one
value object because the domain distinguishes them: reaching a new number is not correcting a dialling
code. A change no handler names at any depth still arrives at the single `otherwise` below.

The frame bodies hold nothing but route declarations, which is deliberate: `under` runs its body when
the routing is *declared*, not when a change arrives, so `wasPhone` and `nowPhone` are read above the
routing rather than inside the frame that uses them.

**`otherwise` is the audit branch, and it is visible.** `tags` is tracked because a change to it is
worth recording, but the domain has no operation for it, so it lands there rather than in an `else`
nobody reads. What is *not* here is the compile-time guarantee: add a property to `Person` and its
changes will quietly reach `otherwise` until you write a handler. `otherwise` is where you notice.

## Events speak domain language

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/event/PersonEvent.kt -->
```kotlin
sealed interface PersonEvent {
    val personId: PersonId
}

data class AddressesReordered(
    override val personId: PersonId,
    val addressId: AddressId,
    val from: Int,
    val to: Int,
) : PersonEvent
```

No event exposes a `Change` or a `FieldPath`, and that is the architectural point of the whole example.
The diff decides *which* event to emit and stops there; kdiff stays at the boundary. A subscriber should
not have to know it exists.

One consequence to expect: **a swap reports two moves**, one per element, because both elements changed
position. Two events, not one, and they describe the transition rather than a sequence of operations to
replay.

## Putting it together

One command that renames, adds a nickname, fills in an email, corrects a dialling code, reorders one
address, edits another, adds a third, retires the person and adds a tag:

```
$ ./gradlew :kdiff-tutorial:run

> UpdatePerson(id=P1)
  PersonRenamed(personId=P1, before=Ada Byron, after=Ada Lovelace)
  NicknameChanged(personId=P1, before=null, after=Countess)
  EmailChanged(personId=P1, before=null, after=ada@analyticalengine.co)
  PhoneCountryCorrected(personId=P1, before=44, after=33)
  AddressAdded(personId=P1, address=Address(id=A3, line1=9 Rue Lovelace, city=Lyon, country=FR))
  AddressesReordered(personId=P1, addressId=A1, from=0, to=1)
  AddressesReordered(personId=P1, addressId=A2, from=1, to=0)
  AddressEdited(personId=P1, before=Address(id=A1, line1=12 Bishopsgate, city=London, country=GB), after=Address(id=A1, line1=12 Bishopsgate, city=Ockham, country=GB))
  EmploymentChanged(personId=P1, before=Employed(employer=Analytical Engine Co, since=1843), after=Retired(since=1852))
> TouchPerson(id=P1)
  (no events)

audited transitions: 1
  tags  ADDED "programmer"
```

Nine events for one command, one audited change that the domain has no operation for, and a
`TouchPerson` that changed only an excluded property and so reported nothing at all.

Two of those nine come out of a single value object, because the domain distinguishes them: reaching a
new address is not correcting a dialling code.

The order is worth reading carefully, because two different rules produce it. *Between* properties it
is the order the differ found the changes — declaration order on `Person` — so everything from
`contact` precedes everything from `addresses`, whatever order the handler declares its routes in.
*Within* `onEach`, it is not the diff's order at all: additions and removals fire first, then moves,
then `changed` once per edited element. That is why `AddressAdded` arrives before the two
`AddressesReordered` even though the diff lists the moves first, and why `AddressEdited` comes last.

## The same model, annotated

Everything above works because a differ and a scope can be described in ordinary Kotlin. The other
route puts the same description on the model itself, and generates the differ:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/annotated/AnnotatedPerson.kt -->
```kotlin
@Diffable
@Trackable
data class Person(
    @DiffIgnore val id: PersonId,
    val name: FullName,
    val nickname: String?,
    val contact: Contact,
    val addresses: List<Address>,
    val employment: Employment,
    @DiffWith(MoneyDiffer::class) val salary: Money,
    val tags: Set<String>,
    @TrackIgnore val lastSeenAt: String,
)
```

There is no second file: `@Diffable` generates `PersonDiffer`, and `@Trackable` makes it carry the
scope. The handler, the events and the routing above are unchanged — routing reads the diff, not the
model, so it cannot tell the two routes apart.

The annotations map one to one onto builder calls:

| Annotation | Builder |
|---|---|
| `@Diffable` on the type | the `differ { }` describing it |
| `@DiffKey` on an element property | the key reference in `keyedList` |
| `@DiffWith(MoneyDiffer::class)` | `nested(Person::salary, MoneyDiffer)` |
| `@DiffIgnore` | naming the property nowhere |
| `@Trackable` | `trackScope { }` |
| `@TrackIgnore` | `except(Person::lastSeenAt)` |

Which to pick: annotations when the model is yours and you want one place to look; the builder when it
is not yours, when the domain must stay free of framework imports, or when one model is compared
several ways. The two compose — the annotated mirror above delegates `salary` to a hand-written differ.

What the annotated route gives you that the builder cannot is **diagnostics**. Describe a
`List<Address>` with `field` instead of `keyedList` and it compiles, comparing the whole list as one
value; the processor would have rejected the equivalent mistake. The answer is a parity test —
`AnnotatedParitySpec` in the tutorial module describes this model both ways and asserts both report the
same changes, at the same paths, in the same order.

## Where to go next

- [How do I…](how-to.md) — the pieces of this application as standalone recipes
- [Diffing](diffing.md) — the change vocabulary, paths, routing and every comparison rule
- [Patching](patching.md) — applying a diff back, and what makes a change unpatchable
- [Tracking](tracking.md) — scopes, depth and `trackedDiff` in full
- [Hand-written differs and scopes](hand-written.md) — the whole `differ { }` vocabulary
- [Annotation reference](annotations.md) — all seven annotations and what they reject
- [Architecture](architecture.md) — why the library is shaped this way
