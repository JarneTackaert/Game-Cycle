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
 * height, weight, career wins, top 3 results, and previous teams.
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

// Be polite: PCS does basic bot detection. A real UA + a delay between
// requests keeps you under the radar and is the neighbourly thing to do.
val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
val REQUEST_DELAY_MS = 1500L

// Flag code -> country name. Extend as needed; unknown codes fall back to
// the raw 2-letter code so nothing is silently lost.
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
    val topResults: List<String>,
    val previousTeams: List<String>,
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

// ────────────────────────────────────────────────────────────────
// skrape{it} safe wrappers
// ────────────────────────────────────────────────────────────────
// skrape{it}'s findAll() throws when zero elements match instead of
// returning an empty list. These extension functions catch that and
// return emptyList() so callers can safely use .firstOrNull(), .any(), etc.

fun Doc.safe(css: String): List<DocElement> =
    try { findAll(css) } catch (_: Exception) { emptyList() }

fun DocElement.safe(css: String): List<DocElement> =
    try { findAll(css) } catch (_: Exception) { emptyList() }

// ────────────────────────────────────────────────────────────────
// Team listing
// ────────────────────────────────────────────────────────────────

/**
 * WorldTour team page URLs from a given listing path.
 * Men's WorldTour and women's WorldTour live at different URLs on PCS,
 * so we pass the full listing path rather than a query-param category.
 */
fun getTeamUrls(listingPath: String): List<String> {
    val doc = fetch(listingPath)
    return doc.safe(".list.fs14.columns2.mob_columns1 a")
        .map { it.attribute("href") }
        .filter { it.startsWith("team/") }
        .distinct()
}

// ────────────────────────────────────────────────────────────────
// Element helpers
// ────────────────────────────────────────────────────────────────

/** slug from rider/<slug> */
fun DocElement.riderSlug(): String? =
    safe("a").firstOrNull { it.attribute("href").startsWith("rider/") }
        ?.attribute("href")?.removePrefix("rider/")

/** flag code from <span class="flag xx"> */
fun DocElement.flagCode(): String? =
    safe("span.flag").firstOrNull()?.classNames
        ?.firstOrNull { it != "flag" }

fun teamName(doc: Doc): String =
    try { doc.findFirst(".page-title h1").text.substringBefore('(').trim() }
    catch (_: Exception) { "" }

// ────────────────────────────────────────────────────────────────
// Rider-page detail extraction
// ────────────────────────────────────────────────────────────────

data class RiderDetails(
    val dateOfBirth: String,
    val placeOfBirth: String,
    val height: String,
    val weight: String,
    val wins: String,
    val topResults: List<String>,
    val allTeams: List<String>,
)

val EMPTY_DETAILS = RiderDetails("", "", "", "", "0", emptyList(), emptyList())

/**
 * Fetch one rider page and extract personal info, career wins, top 3
 * results, and all team names (deduped, preserving order).
 *
 * Labels are rendered as <div class="bold mr5">Label:</div> inside
 * <ul class="list"><li>. Not every <li> on the page has such a label,
 * so every inner findAll is wrapped in the safe() helper to avoid the
 * zero-match exception.
 */
fun getRiderDetails(riderSlug: String): RiderDetails {
    val doc = fetch("rider/$riderSlug")

    // Every <li> inside any <ul class="list"> on the page.
    val allLi = doc.safe("ul.list li")

    /**
     * Find the first <li> whose child <div class="bold …"> text starts
     * with [label], and return that <li>'s full .text.
     * Returns "" when no matching <li> exists.
     */
    fun findLiText(label: String): String =
        allLi.firstOrNull { li ->
            li.safe("div").any { div ->
                div.classNames.contains("bold") &&
                    div.text.trim().startsWith(label)
            }
        }?.text ?: ""

    // ── Date of birth ─────────────────────────────────────────────
    // Text: "Date of birth: 28th June 2004 ( 21 )"
    val dateOfBirth = findLiText("Date of birth")
        .substringAfter("Date of birth:")
        .substringBefore("(")
        .replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")
        .trim()

    // ── Weight & Height (same <li>) ───────────────────────────────
    // Text: "Weight: 56 kg Height: 1.73 m"
    val whText = findLiText("Weight")
    val weight = whText
        .substringAfter("Weight:")
        .substringBefore("kg")
        .trim()
    val height = whText
        .substringAfter("Height:")
        .substringBefore("m")
        .trim()

    // ── Place of birth (may be absent) ────────────────────────────
    val placeOfBirth = findLiText("Place of birth")
        .substringAfter("Place of birth:")
        .trim()

    // ── Top 3 results ─────────────────────────────────────────────
    // The "Top results" sidebar lists the rider's biggest results,
    // most prestigious first. We take the first 3 entries.
    val topResults = doc.safe("ul.topresults li")
        .take(3)
        .map { it.text.trim().replace(Regex("\\s+"), " ") }

    // ── Wins ──────────────────────────────────────────────────────
    // "Key statistics" block: <ul class="rider-kpi">
    val wins = doc.safe("ul.rider-kpi li")
        .firstOrNull { li ->
            li.safe("div.title").any { div ->
                div.text.trim().contains("Wins", ignoreCase = true)
            }
        }
        ?.let { li -> li.safe("div.kpi").firstOrNull()?.text?.trim() }
        ?: "0"

    // ── Teams (all, deduped, in order) ────────────────────────────
    // Structure: <ul class="rdr-teams2">
    //   <li class="main …"><div class="name"><a …>Team Name</a> (WT)</div></li>
    //   <li class="showifmob combiLine"><div class="name2">…</div></li>  ← mobile duplicate, skip
    // We target only li.main entries to avoid the combiLine duplicates,
    // and extract the team name from the <a> inside div.name.
    val allTeams = doc.safe("ul.rdr-teams2 li.main")
        .mapNotNull { li ->
            li.safe("div.name a").firstOrNull()?.text?.trim()
        }
        .filter { it.isNotEmpty() }
        .distinct()

    return RiderDetails(
        dateOfBirth = dateOfBirth,
        placeOfBirth = placeOfBirth,
        height = height,
        weight = weight,
        wins = wins,
        topResults = topResults,
        allTeams = allTeams,
    )
}

// ────────────────────────────────────────────────────────────────
// Team-page scraping
// ────────────────────────────────────────────────────────────────

/**
 * Each team page contains several hidden tab tables (div.stab.*). We read
 * the specialty, country, and age tabs and join them on the rider slug.
 */
fun scrapeTeam(teamUrl: String, gender: String): List<RiderRow> {
    val doc = fetch(teamUrl)
    val name = teamName(doc)

    val specialty = LinkedHashMap<String, MutableList<String>>()
    val nationality = LinkedHashMap<String, String>()
    val age = LinkedHashMap<String, String>()
    val displayName = LinkedHashMap<String, String>()

    fun tableRows(stabClass: String): List<DocElement> =
        doc.safe("div.stab.$stabClass table.teamlist tbody tr")

    // Specialty tab
    for (row in tableRows("specialty")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.safe("td")
        val spec = cells.lastOrNull()?.text?.trim().orEmpty()
        val list = specialty.getOrPut(slug) { mutableListOf() }
        if (spec.isNotEmpty() && spec !in list) list.add(spec)
        displayName.putIfAbsent(slug, try { row.findFirst("a").text.trim() } catch (_: Exception) { slug })
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
    }

    // Country tab
    for (row in tableRows("country")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.safe("td")
        cells.lastOrNull()?.text?.trim()?.let { age.putIfAbsent(slug, it) }
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
        displayName.putIfAbsent(slug, try { row.findFirst("a").text.trim() } catch (_: Exception) { slug })
    }

    // Age tab (fallback)
    for (row in tableRows("age")) {
        val slug = row.riderSlug() ?: continue
        if (age.containsKey(slug)) continue
        val raw = row.safe("td").lastOrNull()?.text?.trim().orEmpty()
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
            topResults = emptyList(),
            previousTeams = emptyList(),
            specialties = specialty[slug] ?: emptyList(),
        )
    }
}

// ────────────────────────────────────────────────────────────────
// CSV helper
// ────────────────────────────────────────────────────────────────

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
// height, weight, career wins, top 3 results, and previous teams.
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

    // Previous teams = all teams from the rider page, minus the current team,
    // compared case-insensitively to handle minor formatting differences.
    val currentTeamLower = r.team.lowercase()
    val previousTeams = details.allTeams
        .filter { it.lowercase() != currentTeamLower }

    r.copy(
        dateOfBirth = details.dateOfBirth,
        placeOfBirth = details.placeOfBirth,
        height = details.height,
        weight = details.weight,
        wins = details.wins,
        topResults = details.topResults,
        previousTeams = previousTeams,
    )
}

val out = File("worldtour_riders_$season.csv")

// Dynamic column counts based on widest data across all riders.
val maxSpecialties = (detailed.maxOfOrNull { it.specialties.size } ?: 0).coerceAtLeast(1)
val maxPrevTeams = (detailed.maxOfOrNull { it.previousTeams.size } ?: 0).coerceAtLeast(1)

out.bufferedWriter().use { w ->
    val specialtyHeaders = (1..maxSpecialties).joinToString(",") { "Specialty $it" }
    val prevTeamHeaders = (1..maxPrevTeams).joinToString(",") { "Previous team $it" }

    w.write("Gender,Team,Rider,Nationality,Age,Date of birth,Place of birth," +
            "Height (m),Weight (kg),Wins," +
            "Top result 1,Top result 2,Top result 3," +
            "$prevTeamHeaders,$specialtyHeaders\n")

    detailed.sortedWith(compareBy({ it.gender }, { it.team }, { it.name })).forEach { r ->
        // Pad top results to exactly 3.
        val topCells = (0 until 3).map { r.topResults.getOrElse(it) { "" } }
        // Pad previous teams to maxPrevTeams.
        val prevCells = (0 until maxPrevTeams).map { r.previousTeams.getOrElse(it) { "" } }
        // Pad specialties to maxSpecialties.
        val specCells = (0 until maxSpecialties).map { r.specialties.getOrElse(it) { "" } }

        w.write((listOf(
            r.gender, r.team, r.name, r.nationality, r.age,
            r.dateOfBirth, r.placeOfBirth, r.height, r.weight, r.wins,
        ) + topCells + prevCells + specCells).joinToString(",") { csvCell(it) })
        w.write("\n")
    }
}
System.err.println("Wrote ${detailed.size} riders to ${out.name} " +
    "(up to $maxSpecialties specialties, $maxPrevTeams previous teams per rider)")
