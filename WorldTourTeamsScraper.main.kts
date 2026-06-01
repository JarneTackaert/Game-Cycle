#!/usr/bin/env kotlin

/**
 * WorldTourTeamsScraper.main.kts
 *
 * A trimmed, self-contained version of the patxibocos/pcs-scraper logic.
 * It loops through every WorldTour team for a given season and, for each
 * team, extracts every rider's: name, nationality, age, and specialty —
 * all from the single team page (the hidden country/age/specialty tabs),
 * so NO per-rider page fetches are needed (~18 requests total, not 500+).
 *
 * A second pass visits each rider page to collect date/place of birth,
 * height, weight, career wins, and top result.
 *
 * Run:
 *   kotlin WorldTourTeamsScraper.main.kts 2026
 *
 * (Requires a Kotlin command-line install: `sdk install kotlin`.)
 * Dependencies are pulled automatically via @file:DependsOn.
 *
 * Output: worldtour_riders_<season>.csv
 */

@file:DependsOn("it.skrape:skrapeit:1.2.2")

import it.skrape.core.htmlDocument
import it.skrape.selects.Doc
import it.skrape.selects.DocElement
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

val PCS_URL = "https://www.procyclingstats.com/"

val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
val REQUEST_DELAY_MS = 1500L

val FLAG_TO_COUNTRY = mapOf(
    "it" to "Italy", "fr" to "France", "es" to "Spain", "de" to "Germany",
    "nl" to "Netherlands", "be" to "Belgium", "co" to "Colombia", "ec" to "Ecuador",
    "uy" to "Uruguay", "er" to "Eritrea", "kz" to "Kazakhstan", "nz" to "New Zealand",
    "cn" to "China", "ru" to "Russia", "at" to "Austria", "gb" to "Great Britain",
    "us" to "United States", "au" to "Australia", "dk" to "Denmark", "no" to "Norway",
    "ch" to "Switzerland", "pt" to "Portugal", "si" to "Slovenia", "pl" to "Poland",
    "ie" to "Ireland", "ca" to "Canada", "lu" to "Luxembourg", "sk" to "Slovakia",
    "cz" to "Czechia", "ee" to "Estonia", "lv" to "Latvia", "za" to "South Africa",
    "jp" to "Japan", "ar" to "Argentina", "br" to "Brazil",
    "cr" to "Costa Rica", "mx" to "Mexico", "pe" to "Peru", "ve" to "Venezuela",
    "il" to "Israel", "tr" to "Turkey", "ua" to "Ukraine", "by" to "Belarus",
    "hu" to "Hungary", "ro" to "Romania", "rs" to "Serbia", "hr" to "Croatia",
    "fi" to "Finland", "se" to "Sweden", "lt" to "Lithuania",
)

data class RiderRow(
    val gender: String,
    val team: String,
    val slug: String,
    val name: String,
    val nationality: String,
    val age: String,
    val dateOfBirth: String,
    val placeOfBirth: String,
    val height: String,
    val weight: String,
    val wins: String,
    val topResult: String,
    val specialties: List<String>,
)

val client: HttpClient = HttpClient.newBuilder().build()

fun fetch(path: String): Doc {
    val url = PCS_URL.trimEnd('/') + "/" + path.trimStart('/')
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .GET()
        .build()
    val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    return htmlDocument(body)
}

fun getTeamUrls(listingPath: String): List<String> {
    val doc = fetch(listingPath)
    return doc.findAll(".list.fs14.columns2.mob_columns1 a")
        .map { it.attribute("href") }
        .filter { it.startsWith("team/") }
        .distinct()
}

fun DocElement.riderSlug(): String? =
    findAll("a").firstOrNull { it.attribute("href").startsWith("rider/") }
        ?.attribute("href")?.removePrefix("rider/")

fun DocElement.flagCode(): String? =
    findAll("span.flag").firstOrNull()?.classNames
        ?.firstOrNull { it != "flag" }

fun teamName(doc: Doc): String =
    doc.findFirst(".page-title h1").text.substringBefore('(').trim()

// ────────────────────────────────────────────────────────────────
// Rider-page detail extraction
// ────────────────────────────────────────────────────────────────

data class RiderDetails(
    val dateOfBirth: String,
    val placeOfBirth: String,
    val height: String,
    val weight: String,
    val wins: String,
    val topResult: String,
)

val EMPTY_DETAILS = RiderDetails("", "", "", "", "0", "")

/**
 * Fetch one rider page and extract personal info, career wins, and top result.
 *
 * The rider info box lives inside `div.borderbox.left.w65`.  Each field is
 * rendered as a `<ul class="list"><li>` with a `<div class="bold">Label:</div>`
 * followed by value divs.  We find the right `<li>` by scanning for the label
 * text and then parse the combined `.text` of that `<li>`.
 *
 * Wins come from the `<ul class="rider-kpi">` block (the "Key statistics"
 * section) — specifically the `<li>` whose title link says "Wins".
 *
 * Top result comes from the first entry in `<ul class="list topresults">`.
 */
fun getRiderDetails(riderSlug: String): RiderDetails {
    val doc = fetch("rider/$riderSlug")

    // ── Personal info fields ──────────────────────────────────────
    // Collect every <li> inside a <ul class="list"> within the info box.
    // We scope to div.borderbox.left.w65 to avoid noise from other lists
    // on the page, but fall back to all ul.list li if the selector misses.
    val infoLi = doc.findAll("div.borderbox.left.w65 ul.list li")
        .ifEmpty { doc.findAll("ul.list li") }

    /** Return the full text of the first <li> whose bold label starts with [label]. */
    fun findLiText(label: String): String =
        infoLi.firstOrNull { li ->
            li.findAll("div.bold").any { it.text.trim().startsWith(label) }
        }?.text ?: ""

    // Date of birth
    // Text looks like: "Date of birth: 27th November 2003 ( 22 )"
    val dateOfBirth = findLiText("Date of birth")
        .substringAfter("Date of birth:")
        .substringBefore("(")                                   // drop "( 22 )"
        .replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")            // "27th" → "27"
        .trim()

    // Weight & Height share the same <li>:
    // "Weight: 64 kg Height: 1.80 m"
    val whText = findLiText("Weight")
    val weight = whText
        .substringAfter("Weight:")
        .substringBefore("kg")
        .trim()
    val height = whText
        .substringAfter("Height:")
        .substringBefore("m")
        .trim()

    // Place of birth
    val placeOfBirth = findLiText("Place of birth")
        .substringAfter("Place of birth:")
        .trim()

    // ── Top result ────────────────────────────────────────────────
    // The "Top results" sidebar lists the rider's biggest results,
    // most prestigious first.  We take the first <li>'s text.
    val topResult = doc.findAll("ul.topresults li")
        .firstOrNull()?.text?.trim()?.replace(Regex("\\s+"), " ")
        ?: ""

    // ── Wins ──────────────────────────────────────────────────────
    // "Key statistics" block: <ul class="rider-kpi">
    //   <li><div class="kpi">26</div>
    //        <div class="title"><a href="…/wins">Wins</a></div>…</li>
    val wins = doc.findAll("ul.rider-kpi li")
        .firstOrNull { li ->
            li.findAll("div.title a").any {
                it.text.trim().equals("Wins", ignoreCase = true)
            }
        }
        ?.findAll("div.kpi")?.firstOrNull()?.text?.trim()
        ?: "0"

    return RiderDetails(
        dateOfBirth = dateOfBirth,
        placeOfBirth = placeOfBirth,
        height = height,
        weight = weight,
        wins = wins,
        topResult = topResult,
    )
}

// ────────────────────────────────────────────────────────────────
// Team-page scraping (unchanged logic)
// ────────────────────────────────────────────────────────────────

fun scrapeTeam(teamUrl: String, gender: String): List<RiderRow> {
    val doc = fetch(teamUrl)
    val name = teamName(doc)

    val specialty = LinkedHashMap<String, MutableList<String>>()
    val nationality = LinkedHashMap<String, String>()
    val age = LinkedHashMap<String, String>()
    val displayName = LinkedHashMap<String, String>()

    fun tableRows(stabClass: String): List<DocElement> =
        doc.findAll("div.stab.$stabClass table.teamlist tbody tr")

    for (row in tableRows("specialty")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.findAll("td")
        val spec = cells.lastOrNull()?.text?.trim().orEmpty()
        val list = specialty.getOrPut(slug) { mutableListOf() }
        if (spec.isNotEmpty() && spec !in list) list.add(spec)
        displayName.putIfAbsent(slug, row.findFirst("a").text.trim())
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
    }

    for (row in tableRows("country")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.findAll("td")
        cells.lastOrNull()?.text?.trim()?.let { age.putIfAbsent(slug, it) }
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
        displayName.putIfAbsent(slug, row.findFirst("a").text.trim())
    }

    for (row in tableRows("age")) {
        val slug = row.riderSlug() ?: continue
        if (age.containsKey(slug)) continue
        val raw = row.findAll("td").lastOrNull()?.text?.trim().orEmpty()
        age.putIfAbsent(slug, raw.substringBefore('y').trim())
    }

    val slugs = LinkedHashSet<String>().apply {
        addAll(specialty.keys); addAll(nationality.keys); addAll(age.keys)
    }

    return slugs.map { slug ->
        RiderRow(
            gender = gender,
            team = name,
            slug = slug,
            name = displayName[slug] ?: slug,
            nationality = nationality[slug] ?: "",
            age = age[slug] ?: "",
            dateOfBirth = "",
            placeOfBirth = "",
            height = "",
            weight = "",
            wins = "",
            topResult = "",
            specialties = specialty[slug] ?: emptyList(),
        )
    }
}

fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' })
        "\"" + s.replace("\"", "\"\"") + "\"" else s

// ────────────────────────────────────────────────────────────────
// Main
// ────────────────────────────────────────────────────────────────

val season = args.firstOrNull()?.toIntOrNull() ?: 2026
System.err.println("Scraping WorldTour teams for $season ...")

val categories = linkedMapOf(
    "teams.php?year=$season&s=worldtour" to "M",
    "teams/women" to "W",
)

val all = mutableListOf<RiderRow>()
for ((listingPath, gender) in categories) {
    val teamUrls = getTeamUrls(listingPath)
    System.err.println("[$gender] Found ${teamUrls.size} teams.")
    for ((i, url) in teamUrls.withIndex()) {
        val rows = scrapeTeam(url, gender)
        System.err.println("[$gender ${i + 1}/${teamUrls.size}] ${rows.firstOrNull()?.team ?: url} — ${rows.size} riders")
        all += rows
        Thread.sleep(REQUEST_DELAY_MS)
    }
}

// Second pass: visit each rider's own page for date/place of birth,
// height, weight, career wins, and top result.
System.err.println("Fetching individual rider details for ${all.size} riders ...")
val detailed = all.mapIndexed { idx, r ->
    val details = try {
        getRiderDetails(r.slug)
    } catch (e: Exception) {
        System.err.println("  ! ${r.slug}: ${e.message}")
        EMPTY_DETAILS
    }
    if ((idx + 1) % 25 == 0) System.err.println("  ... ${idx + 1}/${all.size}")
    Thread.sleep(REQUEST_DELAY_MS)
    r.copy(
        dateOfBirth = details.dateOfBirth,
        placeOfBirth = details.placeOfBirth,
        height = details.height,
        weight = details.weight,
        wins = details.wins,
        topResult = details.topResult,
    )
}

val out = File("worldtour_riders_$season.csv")
val maxSpecialties = (detailed.maxOfOrNull { it.specialties.size } ?: 0).coerceAtLeast(1)
out.bufferedWriter().use { w ->
    val specialtyHeaders = (1..maxSpecialties).joinToString(",") { "Specialty $it" }
    w.write("Gender,Team,Rider,Nationality,Age,Date of birth,Place of birth,Height (m),Weight (kg),Wins,Top result,$specialtyHeaders\n")
    detailed.sortedWith(compareBy({ it.gender }, { it.team }, { it.name })).forEach { r ->
        val specCells = (0 until maxSpecialties).map { r.specialties.getOrElse(it) { "" } }
        w.write((listOf(
            r.gender, r.team, r.name, r.nationality, r.age,
            r.dateOfBirth, r.placeOfBirth, r.height, r.weight, r.wins, r.topResult,
        ) + specCells).joinToString(",") { csvCell(it) })
        w.write("\n")
    }
}
System.err.println("Wrote ${detailed.size} riders to ${out.name} (up to $maxSpecialties specialties per rider)")
