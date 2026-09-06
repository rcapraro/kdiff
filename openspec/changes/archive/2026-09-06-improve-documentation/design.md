## Context

See proposal.md — Why. What matters for the approach is the shape of the existing harness, because
this change extends it rather than replacing it.

`DocumentationSamplesSpec` in `kdiff-sample` reads `README.md` and `docs/*.md` through a
`kdiff.repoRoot` system property, finds every fenced ` ```kotlin ` block, and splits them three ways:

```
   fenced ```kotlin block on README.md or docs/*.md
                     |
      +--------------+---------------+---------------+
      |                              |               |
  <!-- from: path -->      <!-- illustrative -->   neither
      |                              |               |
  named file must exist,         checked for      BUILD FAILS
  and every "distinctive           NOTHING        (escape hatch
  line" (>12 chars, not                            stays visible)
  a comment) must appear
  in it verbatim
```

Two consequences drive this design. First, the checked half is the half a reader copies *last* — the
install coordinates and the whole quickstart are `illustrative`, since no file in this repository
declares an external Maven coordinate or builds an `Address` for a README. Second, the harness reads
only ` ```kotlin ` blocks, so a diagram in a plain fence or a ` ```text ` fence is invisible to it —
which is what makes diagrams free to add.

`kdiff-sample/build.gradle.kts` already declares the documentation and every cited source tree as
task inputs, with a comment explaining that omitting them lets `check` pass on a stale result. Any new
input this change needs goes in the same block.

The project version lives at `build.gradle.kts:12`, set on `subprojects`, so `project.version` inside
`kdiff-sample/build.gradle.kts` is the published version.

## Goals / Non-Goals

**Goals**

- Every factual statement in `README.md` and `docs/` is true of the code at this commit.
- The version cannot drift again without failing `./gradlew check`.
- A reader can move between the seven pages without returning to the README each time.
- The five concepts currently carried by prose alone each have a picture.
- The set states what kdiff costs and what it does not do, so adoption does not require inference.

**Non-Goals**

- No new documentation file, and no documentation file removed. The page set is the right one; it is
  its contents and its wiring that are lacking.
- No behaviour change anywhere. Where prose and code disagree, the prose is wrong by definition for
  the purposes of this change.
- No rewrite for its own sake. A page that is accurate and well-shaped is left alone. This is a
  correction-and-extension pass, and a diff that touches a paragraph carrying neither an error nor a
  new fact is out of scope.
- No new tooling, plugin, dependency or workflow. The only build edit is one `systemProperty` line.

## Decisions

### D1. The version appears once, and a test pins it to the build

The README states the version twice today — in the three install coordinates, and in "Status:
`0.1.0`, released". Both are stale. The fix is two-part:

1. **Status stops naming a version.** It links to `CHANGELOG.md` and to the releases page, which are
   the two places that already carry version history and are already generated from each other. A
   README that names the current release has to be edited on every release; one that links to it does
   not.
2. **The install coordinates become the single source, and are asserted.**
   `kdiff-sample/build.gradle.kts` passes `systemProperty("kdiff.version", project.version.toString())`
   next to the existing `kdiff.repoRoot`, and `DocumentationSamplesSpec` gains a test that scans every
   documentation page for `io.github.kdiff:<module>:<version>` and asserts each match's version equals
   that property.

The assertion is over *every* page, not just the README, so a coordinate added to a guide later is
covered without anyone remembering to extend the test. It scans the raw text rather than only fenced
blocks, so a coordinate in prose or in a shell snippet is caught too.

**Alternatives considered.**

- *A `{{version}}` placeholder rendered at build time.* Rejected: the README stops being
  copy-pasteable, which is its whole job in an install section, and it introduces a render step this
  project has no other need for.
- *A release checklist item.* This is what exists — `add-docs-and-publishing` task 4.3 was exactly
  that instruction — and it drifted at the first release. Repeating it would be choosing the
  instrument already shown not to work.
- *Deleting the version from the snippet entirely* (`kdiff-annotations:latest.release`). Rejected: it
  is not valid for a plain Gradle coordinate against GitHub Packages, and a reader cannot tell what
  they are getting.

### D2. Diagrams are plain ASCII in unfenced or `text` fences — not images, not Mermaid

Every diagram this change adds uses `+ - |` for borders, `-> <- ^ v` for arrows, `*` for markers, and
nothing outside 7-bit ASCII.

- *Images* need an asset pipeline, cannot be diffed, and go stale invisibly — nothing in the harness
  could ever check one.
- *Mermaid* renders on github.com and nowhere else: not in an editor, not in `less`, not in `cat`, not
  in the IDE preview a contributor is most likely to be reading the page in.
- *Unicode box-drawing* is what `docs/architecture.md` uses today (`▲ │ ▼`) and it drifts in width
  across terminals, fonts and locales, which is exactly the failure that misaligns a padded diagram.
  That diagram is converted to ASCII as part of this change rather than left as a second convention.

Because a diagram is not a ` ```kotlin ` block, `DocumentationSamplesSpec` ignores it. That is correct
and deliberate: a diagram carries no API surface, so there is nothing for the harness to anchor to,
and forcing one into the checked path would mean inventing a source file for it to mirror.

### D3. Six diagrams, each on the page that owns its concept

| Diagram | Page | Replaces |
|---|---|---|
| The three capabilities, and the "does it construct?" axis | `docs/architecture.md` | a table plus three paragraphs |
| The corrected module graph, in ASCII, with five modules | `docs/architecture.md` | the four-module Unicode diagram |
| A change: path segments, and what each variant carries | `docs/diffing.md` | two tables the reader must join mentally |
| `under` frame dispatch: where an unnamed change goes | `docs/diffing.md` | the two "edges worth knowing" paragraphs |
| Depth counting: which segments consume a step | `docs/tracking.md` | the path/steps table |
| Command to events | `docs/tutorial.md` | nothing — the thesis is currently undrawn |

Each diagram *supplements* the prose it sits beside; none replaces a paragraph carrying a reason. The
tables above are kept where they are precise (the change vocabulary, the change-to-sides mapping) —
a diagram is for a relationship, a table for an enumeration, and the current pages get that wrong only
where a relationship has been flattened into rows.

The `under` diagram earns its place most: the fallback rule — a frame's own `otherwise` if it declares
one, else back out to the enclosing routing *at the path it arrived with* — is currently three
sentences that a reader has to hold in their head simultaneously, and it is the rule most likely to
surprise.

### D4. Navigation is a footer on every page, not an index page

`docs/tutorial.md` ends with "Where to go next" listing the other six pages. The other six end mid-air.
Each gains the same footer, listing the pages a reader of *that* page plausibly wants next — not the
same six every time, which would be a table of contents pasted seven times.

**Alternative considered:** a `docs/index.md` or `docs/README.md`. Rejected: the README's table is
already the index, and a second one means two lists to keep in step, with GitHub rendering
`docs/README.md` as the directory's landing page and quietly making the README's table the *second*
thing a reader meets.

`docs/diffing.md`, `docs/patching.md` and `docs/tracking.md` also gain a single opening line saying who
the page is for, because all three currently open on an interface declaration. `docs/tutorial.md`,
`docs/architecture.md`, `docs/hand-written.md` and `docs/annotations.md` already do this and are left
alone.

### D5. "What kdiff does not do" goes in `docs/architecture.md`, not the README

`docs/architecture.md` opens "for anyone deciding whether to use it", which is precisely the reader
who needs the limitations. The README's job is the tour, and a limitations block above the quickstart
reads as a disclaimer and gets skipped by the reader it is for.

The section states, each verified against the code rather than asserted from memory:

- An unkeyed list is compared index by index (`comparePositionalList`), so an element inserted at the
  head reports every following position as changed. `@DiffKey` is the answer, and there is no
  edit-distance matching to fall back on.
- Custom comparison is per property (`@DiffWith`), not per type. Comparing every `BigDecimal` by
  `compareTo` means an annotation at each site; there is no global registration.
- A comparison walks a tree rooted at one instance. There is no cross-graph object identity and no
  cycle detection — **the task must establish what a self-referential `@Diffable` actually does before
  this sentence is written**, and state that, rather than predicting it.
- Kotlin/JVM only. No multiplatform, no Java consumers of the annotations.
- Pre-`1.0.0`, with no compatibility guarantee, which the README's Status already says and this
  section cross-references rather than repeats.

### D6. "What a comparison costs" states the actual shape, next to the algorithm decision

`docs/architecture.md` explains why the algorithms live in the runtime rather than being generated,
and says nothing about what they cost. Reading `Compare.kt` gives a genuinely good answer worth
stating: `compareKeyedList` builds two maps with `associateBy` and walks each once, and
`comparePositionalList` walks to `minOf(before.size, after.size)` and then the tail — so comparison is
linear in the size of each collection, with no quadratic matching and no edit-distance search
anywhere. That is the honest counterpart to D5's first bullet: the positional behaviour is the price of
the linear bound, not an oversight.

The section is short and states only what the code shows. Set and map costs are to be read off
`compareSet` and `compareMap` rather than assumed from the list cases, and no wall-clock number or
benchmark is quoted — this change adds no benchmark, so it may not claim one.

### D7. A general rule lives on the reference page, not only in the tutorial

Two rules are currently documented *only* in `docs/tutorial.md`, where a reader looking them up will
not find them:

- **A swap reports two moves**, one per element, because both changed position. This belongs in
  `docs/diffing.md` under Lists, beside the keyed-list rules it follows from.
- **A duplicate key in a keyed list.** `compareKeyedList` uses `associateBy`, which keeps the last
  entry for a repeated key — so elements silently collapse. This is documented nowhere at all. The
  task must confirm the behaviour by test before describing it, and if it turns out to be surprising
  enough to warrant a diagnostic, that is a separate change and this one only documents what happens.

The tutorial keeps its sentence about the swap, since it is describing a specific output there, but
stops being the only home for the rule.

**Alternative considered:** a `docs/faq.md` collecting these. Rejected: every one of them has an
obvious owning page, and a FAQ is where a fact goes to be duplicated and then to disagree with the
page that owns it.

### D8. The tutorial keeps its order and gains a map

The obvious restructuring — moving "The same model, annotated" to the front for annotation-first
readers — is rejected. The page's thesis is that the domain needs no annotation at all, and opening on
annotations argues against it. Instead:

- The flow diagram goes near the top, right after the thesis paragraph it illustrates, so the shape of
  the whole example is visible before any code.
- The opening states, in one line, that the annotated mirror is at the end and links to it, so a
  reader who came for annotations can jump rather than skim 300 lines.

The console output block stays where it is, and stays unverified — it is not a Kotlin block, so the
harness cannot reach it. Checking it would mean running `:kdiff-tutorial:run` from a test and matching
its stdout, which is a new harness for one block. **The task verifies it by hand against a real run
instead**, and the result of that run is reported in the task.

### D9. The README's tracking example is corrected in prose, not in code

The snippet is correct Kotlin: `tracker(AddressDiffer, before) { onFieldChange { … } }` compiles and
fires, because a tracker naming no property tracks every compared property. Only the sentence
introducing it is wrong — "with `@Trackable`, a lambda fires" — since the `Address` shown carries no
`@Trackable`.

So the fix is the sentence. Adding `@Trackable` to `demo.Address` to make the prose true would change
a `src/main` file, change generated output, and drag the sample's specs along with it — a behaviour
change to justify a sentence. The sentence says instead what is actually true: a tracker fires as a
value evolves, and `@Trackable` is how a *type* declares which properties it wants tracked, which
`docs/tracking.md` then covers.

### Schema design rules recorded as inapplicable

`openspec/config.yaml` requires a design to cover type resolution, incremental processing, a sample of
the generated file, and the runtime-versus-generated split. All four are rules for a change to the
processor. This change adds no annotation, resolves no type, generates no file and moves no logic
between runtime and generated code — `kdiff-processor` is not touched, and no `src/main` file in any
module is. There is nothing to cover, and inventing coverage would put false statements in the record.

## Risks / Trade-offs

**The version assertion could fail the build for the wrong reason** — a page legitimately citing an
*older* version, say in a migration note. → No page does today, and none is added by this change. If
one is ever needed, the test is a few lines and can gain an exemption then; guessing at the shape of
that exemption now would be designing for a case that does not exist.

**A diagram can go stale as silently as prose, and the harness cannot check it.** → Accepted, and
bounded by D3: each diagram sits beside the prose it illustrates, so the two are edited together, and
none of them encodes an API signature — the thing most likely to move. A diagram that would need to
name a method signature to be useful is a sign the prose should carry it instead.

**A correction pass can quietly become a rewrite**, producing a large diff in which the real fixes are
invisible to a reviewer. → The Non-Goals name this, and the task list separates the accuracy tasks
(group 2) from the extension tasks (groups 4-6) so the corrections can be read on their own.

**Writing about cost or about limitations invites overclaiming.** → D5, D6 and D7 each require the
behaviour to be established from the code or from a run before it is described, and the tasks say so
individually. Where the code does not settle a question, the page says nothing rather than guessing.

**`CLAUDE.md` and `docs/architecture.md` can be corrected into disagreement.** `CLAUDE.md` already
documents five modules correctly; `docs/architecture.md` is the one that is wrong. → The verification
group re-reads both after the change and confirms they agree, with `CLAUDE.md` stating and
`docs/architecture.md` explaining, as its own closing section says.

## Migration Plan

None. Documentation has no deployment step and no consumer to migrate. The one build edit —
`systemProperty("kdiff.version", …)` — affects only `kdiff-sample`'s test task; reverting is deleting
the line and the test that reads it.

The change is safe to land in any order relative to a release, with one caveat worth stating: it
corrects the coordinates to the *currently published* `0.2.0`, so if a release is cut between writing
and merging, the assertion added by D1 will fail the build and name the mismatch — which is the guard
rail working, not a conflict.
