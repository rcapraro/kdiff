## ADDED Requirements

### Requirement: A nullable collection property is rebuilt when present and set wholesale when it appears or disappears

Applying to a property whose type is a nullable `List`, `Set` or `Map` SHALL follow the rule a
nullable nested property follows. A value change reported *at* the property SHALL set the property to
the change's new value, whether that value is a collection or `null`. Changes reported *beneath* the
property SHALL be applied to the source collection when it is present, by the rules for that
collection shape. When the source collection is `null` and changes are reported beneath it, each SHALL
be reported as a failure whose reason states that there is nothing beneath a null property, and the
property SHALL stay `null`.

For any two instances of a type holding such a property, applying the diff between them to the first
SHALL produce an instance equal to the second and SHALL report no failures, in all four transitions:
null to null, null to present, present to null, and present to present.

Being nullable SHALL NOT exempt a property from a precondition its shape requires. Where the source
holds a value, that value SHALL be examined on the same terms as a non-null property of the same shape
— whatever is being applied to it, an empty change list and a wholesale replacement included. So a
nullable list matched by key whose source holds two elements sharing a key SHALL be refused exactly as
the non-null declaration of that list is. A `null` source has nothing to examine and SHALL NOT be
refused.

Where a value change at the property replaces it outright, every other change addressed to that
property SHALL be reported as a failure rather than discarded: the property is a value in that moment,
so the last change at it wins and the rest could not be used. This is the treatment a property compared
as a value already receives, and it follows from changes never being dropped in silence.

#### Scenario: A nullable keyed list with a repeated key is refused however little is applied

- **WHEN** the source's `addresses: List<Address>?` holds two elements sharing key `A1`, and the change
  list is empty
- **THEN** applying fails with an error naming the list, the key property and the value `A1`
- **AND** the same holds when the change list addresses another property, and when it replaces
  `addresses` wholesale

#### Scenario: A nullable declaration refuses what the non-null one refuses

- **WHEN** the same repeated-key list is applied to once as a `List<Address>` property and once as a
  `List<Address>?` property
- **THEN** both fail with the same error

#### Scenario: A null source is not refused

- **WHEN** the source's nullable keyed list is `null` and the change list is empty
- **THEN** applying succeeds, leaving the property `null`

#### Scenario: A change beneath a wholesale replacement is reported

- **WHEN** a change list holds a value change at a nullable list property and a change beneath that
  same property
- **THEN** the property takes the value the first change carries
- **AND** the change beneath it is reported as a failure stating it is not applicable to a value
  property

#### Scenario: A set's own element changes are not mistaken for a wholesale replacement

- **WHEN** an addition to a nullable `Set` property is applied, which the comparison reports at the
  property's own path
- **THEN** the element is added to the set
- **AND** no failure is reported

#### Scenario: Round-trip over a nullable list that appears

- **WHEN** `tags: List<String>?` is `null` in the source and `["a", "b"]` in the target
- **THEN** applying their diff produces an instance whose `tags` is `["a", "b"]`
- **AND** no failures are reported

#### Scenario: Round-trip over a nullable list that disappears

- **WHEN** `tags: List<String>?` is `["a"]` in the source and `null` in the target
- **THEN** applying their diff produces an instance whose `tags` is `null`
- **AND** no failures are reported

#### Scenario: Round-trip over two present nullable collections

- **WHEN** `tags: List<String>?` is `["a", "b"]` in the source and `["a", "c"]` in the target, and
  `amounts: Map<String, String>?` gains an entry
- **THEN** applying their diff reproduces the target exactly
- **AND** the list and the map are rebuilt by the rules for their shape, as a non-null property would be

#### Scenario: Round-trip over a nullable keyed list with a move

- **WHEN** `addresses: List<Address>?` is present on both sides and two elements swap positions
- **THEN** applying their diff reproduces the target list, including element order

#### Scenario: A change beneath a null collection is reported

- **WHEN** a change at `tags[0]` is applied to an instance whose `tags` is `null`
- **THEN** the result reports that change as a failure stating that there is nothing beneath a null
  property
- **AND** the result's `tags` is still `null`
- **AND** the rest of the instance is patched

#### Scenario: A nullable collection that no change addresses is carried through

- **WHEN** a diff addresses none of a nullable `Set` property's changes, and the source holds a set
- **THEN** applying produces an instance holding the source's own set instance

### Requirement: A sealed singleton is applied by returning it

When the instance being patched is an `object` subclass of a sealed type and no type change is
reported at the root, applying SHALL return that instance. Every change in the list SHALL be reported
as a failure whose reason states that the type has no compared property at that path, naming the
object's type: a singleton has no property a change could address, so any change addressed beneath it
did not come from comparing it.

A type change reported at the root whose new value is an `object` SHALL be applied by substitution, as
any type change is: the result SHALL be that object.

For any two instances of a sealed type with `object` subclasses, applying the diff between them to the
first SHALL produce an instance equal to the second and SHALL report no failures.

#### Scenario: Round-trip over a swap to an object subclass

- **WHEN** the source holds `Card` and the target holds `Unpaid`
- **THEN** applying their diff produces an instance holding `Unpaid`
- **AND** no failures are reported

#### Scenario: Round-trip over a swap from an object subclass

- **WHEN** the source holds `Unpaid` and the target holds `Transfer(iban = "FR76")`
- **THEN** applying their diff produces an instance holding that `Transfer`
- **AND** no failures are reported

#### Scenario: The same object on both sides applies an empty diff

- **WHEN** the source and the target both hold `Unpaid`
- **THEN** their diff is empty, and applying it returns `Unpaid` with no failures

#### Scenario: A foreign change beneath a singleton is reported

- **WHEN** a value change at `amount` is applied to `Unpaid` with no type change at the root
- **THEN** the result's value is `Unpaid`
- **AND** the result reports that change as a failure stating that `Unpaid` has no compared property at
  that path

#### Scenario: A hand-written patcher and a generated one agree on a singleton

- **WHEN** a hand-written patcher for `Payment` handles `Unpaid` by returning it and reporting every
  change as addressing an unknown property
- **THEN** applying the same changes through it and through the generated patcher produces equal results
  and equal failures
