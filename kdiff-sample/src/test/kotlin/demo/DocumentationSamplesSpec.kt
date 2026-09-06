package demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Checks that the documentation's Kotlin samples still describe the real API.
 *
 * A fenced Kotlin block preceded by `<!-- from: <path> -->` claims to mirror compiled code. This spec
 * asserts the named file exists and that the block's distinctive lines are still in it, so a sample
 * cannot drift silently — the API it documents changed twice while it was being written.
 *
 * Matching is on distinctive lines rather than whole blocks: a sample legitimately elides properties
 * with `...` and reflows for width, and a whole-block comparison would fail on formatting while
 * catching nothing real. What must not drift is the API surface, which is what those lines carry.
 */
private val repoRoot = File(
    System.getProperty("kdiff.repoRoot")
        ?: error("kdiff.repoRoot is not set; the test must be run through Gradle"),
)

private const val MARKER = "<!-- from:"

/**
 * Opts a block out of source checking, for consumer-side code this repository has no file for — an
 * install snippet, or a usage example written for the reader rather than lifted from a test.
 *
 * Per block rather than per page: every guide legitimately mixes both kinds, so a page-level opt-out
 * would end up covering every page and checking nothing.
 */
private const val ILLUSTRATIVE = "<!-- illustrative -->"

private data class Block(val page: String, val source: String?, val illustrative: Boolean, val lines: List<String>)

private fun documentationPages(): List<File> =
    (listOf(repoRoot.resolve("README.md")) + repoRoot.resolve("docs").listFiles().orEmpty().sorted())
        .filter { it.isFile && it.extension == "md" }

/**
 * The pages the coordinate check reads: the sample-bearing pages plus the ones that only ever mention
 * a coordinate in prose.
 *
 * `CONTRIBUTING.md` is where this rule is written down and is already a declared input of this task,
 * so leaving it unchecked would let the page describing the guard rail be the one that goes stale.
 */
private fun coordinateBearingPages(): List<File> =
    (documentationPages() + repoRoot.resolve("CONTRIBUTING.md")).filter { it.isFile }

private fun File.relativePage(): String = relativeTo(repoRoot).path

/**
 * The version every published coordinate on these pages must name.
 *
 * A coordinate is the first thing a reader copies and the one thing the marker check cannot reach:
 * no file in this repository declares an external Maven coordinate, so an install snippet is
 * necessarily `illustrative` and was checked against nothing. It went stale at the first release, on
 * a page that also told the reader which release it was describing. Pinning it to the build is what
 * a release checklist item failed to do.
 */
private val publishedVersion = System.getProperty("kdiff.version")
    ?: error("kdiff.version is not set; the test must be run through Gradle")

/**
 * Every `io.github.kdiff:<module>:<version>` on a page, wherever it sits.
 *
 * Deliberately over the whole page rather than over fenced Kotlin blocks: a coordinate is just as
 * wrong in prose or in a shell snippet, and scanning the text needs no opinion about which fence it
 * was written in.
 *
 * A version held in a variable — `kdiff-runtime:$kdiffVersion`, or `${'$'}{libs.versions.kdiff}` — is
 * skipped rather than compared. It cannot go stale, since whatever it resolves to is not written here.
 */
private val COORDINATE = Regex("""io\.github\.kdiff:[\w-]+:([^"'\s)]+)""")

private fun String.isLiteralVersion(): Boolean = none { it == '$' || it == '{' }

private fun File.staleCoordinates(): List<String> = COORDINATE.findAll(readText())
    .map { it to it.groupValues[1] }
    .filter { (_, version) -> version.isLiteralVersion() }
    .filterNot { (_, version) -> version == publishedVersion }
    .map { (match, _) -> "${relativePage()}: ${match.value}" }
    .toList()

/** Every fenced Kotlin block on a page, with the source it claims to mirror when it declares one. */
private fun File.kotlinBlocks(): List<Block> {
    val lines = readLines()
    val blocks = mutableListOf<Block>()
    var index = 0

    while (index < lines.size) {
        if (lines[index].trimStart().startsWith("```kotlin")) {
            val marker = lines.take(index).lastOrNull { it.isNotBlank() }?.trim()
            val source = marker?.takeIf { it.startsWith(MARKER) }
                ?.removePrefix(MARKER)?.removeSuffix("-->")?.trim()

            val body = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                body += lines[index]
                index++
            }
            blocks += Block(relativePage(), source, marker == ILLUSTRATIVE, body)
        }
        index++
    }

    return blocks
}

/**
 * The lines worth checking: those naming the API surface a reader would copy.
 *
 * Elisions, comments, blank lines and bare punctuation are skipped — they carry no API and would only
 * make the check brittle.
 */
private fun Block.distinctiveLines(): List<String> = lines
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    .filterNot { it == "..." || it.all { character -> character in "(){}[],:=" } }
    .filter { it.length > 12 }

class DocumentationSamplesSpec : FunSpec({

    val pages = documentationPages()

    test("the documentation pages exist to be checked") {
        pages.map { it.relativePage() }.shouldNotBeEmptyList()
    }

    test("every published coordinate in the documentation names version $publishedVersion") {
        coordinateBearingPages().flatMap { it.staleCoordinates() }.shouldBeEmpty()
    }

    pages.forEach { page ->
        val name = page.relativePage()
        val blocks = page.kotlinBlocks()

        blocks.filter { it.source != null }.forEachIndexed { ordinal, block ->
            val source = block.source!!

            test("$name block ${ordinal + 1} cites a source file that exists: $source") {
                repoRoot.resolve(source).isFile shouldBe true
            }

            test("$name block ${ordinal + 1} still matches $source") {
                val actual = repoRoot.resolve(source).readText()
                val drifted = block.distinctiveLines().filterNot { actual.contains(it) }

                drifted.shouldBeEmpty()
            }
        }

        test("$name marks every Kotlin sample either with its source or as illustrative") {
            blocks.filter { it.source == null && !it.illustrative }
                .map { it.lines.firstOrNull()?.trim() }
                .shouldBeEmpty()
        }
    }
})

private fun List<String>.shouldNotBeEmptyList() {
    check(isNotEmpty()) { "no documentation pages were found under $repoRoot" }
}
