## Context

See `proposal.md` — *Why*. The correction is mechanical; what needs deciding first is the replacement
wording, because it appears in seven places that a reader compares against each other, and one of them
is ASCII art with a fixed column width.

Two constraints shape the approach.

**The right words already exist in the repository.** Both KDoc blocks describe `kdiff-runtime` as
carrying "none but the Kotlin standard library" in the same sentence that calls `kdiff-annotations`
"no dependencies at all". `docs/architecture.md`'s module diagram already labels the runtime box
`Kotlin stdlib only`. So the accurate phrasing is established and in use — the annotations half simply
never adopted it.

**The diagram has no room to grow.** In `docs/architecture.md` the `published | not published`
divider sits at column 51 on all 25 lines of the diagram, and the right-hand text field is 25
characters wide starting at offset 2 — so at most 23 characters of text. `no dependencies at all` is
22 and fits; any longer replacement would push the divider and break every line's alignment.

The processor-oriented artifact rules for this document — type resolution, incremental processing, a
sample of the generated file, runtime-versus-generated split — have nothing to constrain here. No
Kotlin declaration, no generated code and no processor behaviour is touched; only comment prose.

## Goals / Non-Goals

**Goals**

- One phrasing, used in all seven places, that a reader can compare across them without noticing a
  seam.
- The load-bearing rule stays prominent: no kdiff module depends on another except the processor on
  the annotations, and no published module pulls a third-party library.
- The diagram's alignment survives byte-for-byte outside the one changed cell.

**Non-Goals**

- Rewriting any of the seven passages beyond the inaccurate clause. Each is edited in place, at the
  smallest span that makes it true.
- Explaining what the Kotlin standard library is, or why a Kotlin module carries it. The audience
  already knows; belabouring it is how a correction becomes noise.

## Decisions

### D1 — Say what is true of resolution, not of declaration

The claim broke because "no dependencies" described the build file while reading as a statement about
the artifact. The replacement states the fact that matters to a consumer — what resolves — and keeps
the rule that matters to a contributor as a separate clause.

The phrasing, adapted to each site's voice:

> `kdiff-annotations` — annotations only. **Depends on no kdiff module and no third-party library**;
> like every Kotlin module it carries the Kotlin stdlib.

Alternative considered and rejected: keeping "no dependencies at all" and adding a footnote that the
stdlib does not count. That is how the claim became untrue in the first place — a reader takes the
bold clause and skips the qualification, and the POM still contradicts the bold clause.

Alternative considered and rejected: saying only "depends only on the Kotlin stdlib", matching what
`kdiff-runtime` already says. Accurate, and it is what the diagram cell will use for want of width
(D2) — but in prose it loses the rule that the module depends on no *kdiff* module, which is the part
that is load-bearing and the reason the constant is declared twice.

### D2 — The diagram cell adopts the runtime box's label verbatim

The annotations box becomes:

```
  | kdiff-annotations   |  annotations only,      |
  | @Diffable, @DiffKey |  Kotlin stdlib only     |
```

`Kotlin stdlib only` is 18 characters, fits the 23 available, and is padded to hold the divider at
column 51 — the same string and the same padding the runtime box on line 31 already uses. Two boxes
carrying the identical label is correct rather than repetitive: both modules do carry exactly the
stdlib and nothing else.

The paragraph directly beneath the diagram already states the inter-module rule ("`kdiff-annotations`
and `kdiff-runtime` do not depend on each other, and neither depends on the processor"), so the
diagram does not have to carry it in 23 characters.

### D3 — The `UNLIMITED_DEPTH` KDoc keeps its argument; only the parenthetical moves

Both blocks exist to explain why one constant is declared twice: an annotation default must be a
compile-time constant in the module declaring the annotation, and neither module may depend on the
other. That argument is correct and stays word for word. Only the trailing clause that justifies
"neither module may depend on the other" by listing what each carries is corrected, and it is exactly
where the inaccuracy sits.

This keeps the edit inside one clause of published KDoc, which is the smallest span that fixes what
ships to Central.

### D4 — The build-file comment gains the word that made it true

`kdiff-annotations/build.gradle.kts` is a file containing one comment and no code. `// No
dependencies: …` is accurate about the file and became the seed of the inaccurate prose elsewhere. It
becomes `// No declared dependencies: …`, which is precisely what an empty build file means, and keeps
the reason it gives.

### D5 — `docs/annotations.md` is verified, not edited

Its account of the same duplicated constant speaks only about `kdiff-runtime` ("depends on nothing but
the Kotlin standard library") and is already true. It is read during the change to confirm it does not
contradict the new wording, and left alone. Editing prose that is already correct is how a
seven-line correction turns into a rewrite.

## Risks / Trade-offs

**The correction reads as pedantic and a reviewer reverts it** → The proposal leads with the resolved
classpath and the POM, so the diff has the evidence attached. The claim is not a nuance: a consumer
resolves two jars where the docs promise zero.

**Editing the diagram breaks its alignment** → D2 fixes the replacement to a string already used in
the same diagram at the same width, and the task list checks the divider column rather than trusting
the eye.

**Seven sites drift apart again** → They are edited in one change against one phrasing (D1), so the
diff shows them together. Nothing mechanically prevents a future divergence; that guard was weighed
and deliberately left to its own change (`proposal.md` — *Not in scope*), because it needs a
`build-quality-gates` requirement and a test rather than a wording decision.

**`openspec/config.yaml` is the authoritative project description and feeds planning context** →
Correcting it is the highest-value edit of the seven and the easiest to overlook, since it is not
under `docs/`. It is the first task rather than the last.
