package demo

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Checks that every message `docs/errors.md` quotes is one the code still emits.
 *
 * The page quotes diagnostics, construction-time refusals and failure sentences verbatim so that a
 * reader holding an error can search for its text and land on the explanation. That is only true while
 * the two agree, and until this spec existed nothing checked that they did — `CONTRIBUTING.md` said so
 * in as many words, and a reworded message left `check` green and the page wrong.
 *
 * `DocumentationSamplesSpec` cannot reach these: a message in a Markdown blockquote is not a fenced
 * Kotlin block.
 *
 * **The source is the ground truth, not the page.** A message is checked by cutting it into the runs
 * the code states literally and asking whether the page's quotation contains them, in order. The
 * reverse — asking the source to contain what the page quotes — cannot work, because a page expands
 * more than the `<…>` placeholders it marks: `$part` becomes *elements* or *values*, `$MAX_DESCENT`
 * becomes *512*, and a helper's return value becomes prose. Comparing only what both sides state
 * literally is what makes the check sound in both directions.
 *
 * **What it does not catch, stated so nobody has to rediscover it.** A quotation is checked against
 * every source message, not against the one it documents, so a message whose literal runs another
 * message also states is covered by that other message if it drifts. Rewording either
 * `@DiffAsValue on … has no effect` diagnostic leaves the page green, because the two differ only in
 * what their interpolations produce.
 *
 * That is not fixable here. Requiring each quotation to claim a source message of its own — the obvious
 * repair — breaks on `kdiff cannot compare <prop>: its $part are nullable`, where **one** message is
 * correctly documented as two quotations. One message documented twice and two messages of which one
 * drifted are the same data to anything reading literal runs. Closing it means the page naming the
 * message each quotation documents, the way a sample names its source file with `<!-- from: -->`.
 */
private val repoRoot = File(
    System.getProperty("kdiff.repoRoot")
        ?: error("kdiff.repoRoot is not set; the test must be run through Gradle"),
)

/**
 * The sources that emit a message the page quotes, named in `CONTRIBUTING.md` beside the rule.
 *
 * Read as text rather than depended on: nothing here calls into the processor, and a compile dependency
 * on it would put a code generator on this module's classpath for the sake of a string search.
 */
private val MESSAGE_SOURCES = listOf(
    "kdiff-processor/src/main/kotlin/io/github/kdiff/processor/DiffProcessor.kt",
    "kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Dsl.kt",
    "kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/TrackScope.kt",
    "kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Route.kt",
    "kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Patcher.kt",
    "kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Errors.kt",
)

/** A message the page quotes: `> ` followed by the text in backticks, one per line. */
private val QUOTED = Regex("""^> `(.+)`$""", RegexOption.MULTILINE)

/**
 * A `" + "` join between two adjacent string literals.
 *
 * Every message longer than a line is concatenated to fit the column limit, so collapsing these is what
 * makes one message one literal again. The constraint it creates is recorded in `CONTRIBUTING.md`: a
 * diagnostic is one string expression of adjacent literals and interpolations. One assembled with
 * `buildString` or a `when` returning halves would arrive here in pieces and be checked as pieces.
 */
private val CONCATENATION = Regex(""""\s*\+\s*"""")

/** A single-quoted Kotlin string literal, escapes included. */
private val LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")

/** `${…}` and `$name` alike: where the code puts a value rather than stating text. */
private val INTERPOLATION = Regex("""\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*""")

/**
 * A run shorter than this carries no message — `: its `, `; `, ` of type ` — and requiring one would
 * make the check brittle while catching nothing.
 */
private const val SUBSTANTIAL = 12

/**
 * Every source message, as the runs of text the code states literally.
 *
 * A message with no substantial run is dropped: it cannot identify anything, and keeping it would let
 * any quotation match it.
 */
private val emitted: List<List<String>> by lazy {
    MESSAGE_SOURCES.flatMap { path ->
        val file = repoRoot.resolve(path)
        check(file.isFile) { "$path does not exist; MESSAGE_SOURCES is stale" }

        LITERAL.findAll(file.readText().replace(CONCATENATION, ""))
            .map { literal -> INTERPOLATION.split(literal.groupValues[1]).filter { it.length >= SUBSTANTIAL } }
            .filter { it.isNotEmpty() }
            .toList()
    }
}

/** How many of [runs] appear in this quotation, in order, before one does not. */
private fun String.matching(runs: List<String>): Int {
    var cursor = 0
    runs.forEachIndexed { index, run ->
        val found = indexOf(run, cursor)
        if (found < 0) return index
        cursor = found + run.length
    }
    return runs.size
}

/** The source message this quotation comes closest to stating, for a failure that can be acted on. */
private fun String.closest(): Pair<List<String>, Int>? =
    emitted.map { it to matching(it) }.maxByOrNull { (runs, matched) -> matched * 1000 - runs.size }

private fun quotedMessages(): List<String> =
    QUOTED.findAll(repoRoot.resolve("docs/errors.md").readText()).map { it.groupValues[1] }.toList()

class DocumentationMessagesSpec :
    FunSpec({

        val messages = quotedMessages()

        test("docs/errors.md quotes messages to check, and the sources state messages to check them against") {
            check(messages.isNotEmpty()) { "no quoted messages were found in docs/errors.md" }
            check(emitted.isNotEmpty()) { "no messages were found in ${MESSAGE_SOURCES.size} sources" }
        }

        messages.forEachIndexed { ordinal, message ->
            // The ordinal is part of the name because two messages can share their first sixty
            // characters — the two `@Diffable is only supported on…` diagnostics do — and a report that
            // cannot say which of them drifted is a report nobody can act on.
            val abbreviated = message.take(60) + if (message.length > 60) "…" else ""

            test("docs/errors.md quote ${ordinal + 1} is a message the code emits: $abbreviated") {
                val (runs, matched) = message.closest()
                    ?: error("no source message to check \"$message\" against")

                withClue(
                    "docs/errors.md quotes:\n  $message\nbut no source states this part of it:\n  " +
                        "${runs.getOrNull(matched)}\nReword the page, or the message, so the two agree.",
                ) {
                    matched shouldBe runs.size
                }
            }
        }

        // A quotation short enough to match nothing in particular would pass while checking nothing, so
        // the page cannot quietly acquire one.
        test("every quoted message is long enough to identify the message it quotes") {
            messages.filter { it.length < SUBSTANTIAL }.shouldBeEmpty()
        }
    })
