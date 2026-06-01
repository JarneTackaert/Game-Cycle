#!/usr/bin/env kotlin

/**
 * WorldTourTeamsScraper.main.kts
 *
 * Scrapes every UCI WorldTour and ProTour team (men + women) for a given
 * season. For each team, extracts every rider's: name, nationality, age,
 * specialty, date/place of birth, height, weight, career wins, top 3
 * results, previous teams, and UCI points scored in the PREVIOUS season
 * (season - 1).
 *
 * Run:
 *   kotlin WorldTourTeamsScraper.main.kts 2026
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

data class TeamEntry(val url: String, val gender: String, val tier: String)

data class RiderRow(
    val gender: String,
    val teamTier: String,
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
    val uciPointsPrevSeason: String,
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

fun Doc.safe(css: String): List<DocElement> =
    try { findAll(css) } catch (_: Exception) { emptyList() }

fun DocElement.safe(css: String): List<DocElement> =
    try { findAll(css) } catch (_: Exception) { emptyList() }

// ────────────────────────────────────────────────────────────────
// Team listing with WT / PT distinction
// ────────────────────────────────────────────────────────────────

/**
 * Parse a team listing page and return team URLs tagged with gender + tier.
 *
 * Both men's and women's pages have the same layout:
 *   <h4>UCI (Women's) WorldTeams</h4>
 *   <ul class="list fs14 columns2 mob_columns1">…team links…</ul>
 *   <ul class="list horizontal">…jersey images (skip)…</ul>
 *   <h4>UCI (Women's) ProTeams</h4>
 *   <ul class="list fs14 columns2 mob_columns1">…team links…</ul>
 *
 * Key difference: men's lists additionally have the `lh18` class, women's
 * don't. We use `ul.columns2` which matches both. The jersey-image lists
 * use `ul.list.horizontal` (no `columns2`) so they're excluded.
 */
fun getTeamEntries(listingPath: String, gender: String): List<TeamEntry> {
    val doc = fetch(listingPath)

    // ul.columns2 targets only the team-name lists, not the jersey-image
    // lists (those use "list horizontal" without columns2).
    val teamLists = doc.safe("ul.columns2")

    if (teamLists.size >= 2) {
        val results = mutableListOf<TeamEntry>()

        // First list: UCI WorldTeams
        val wtUrls = teamLists[0].safe("a")
            .map { it.attribute("href") }
            .filter { it.startsWith("team/") }
            .distinct()
        results += wtUrls.map { TeamEntry(it, gender, "WT") }

        // Second list: UCI ProTeams
        val ptUrls = teamLists[1].safe("a")
            .map { it.attribute("href") }
            .filter { it.startsWith("team/") }
            .distinct()
        results += ptUrls.map { TeamEntry(it, gender, "PT") }

        return results
    }

    // Fallback: single list — treat all as WT
    val urls = (teamLists.firstOrNull()?.safe("a") ?: doc.safe(".list.fs14.columns2.mob_columns1 a"))
        .map { it.attribute("href") }
        .filter { it.startsWith("team/") }
        .distinct()
    return urls.map { TeamEntry(it, gender, "WT") }
}

// ────────────────────────────────────────────────────────────────
// Element helpers
// ────────────────────────────────────────────────────────────────

fun DocElement.riderSlug(): String? =
    safe("a").firstOrNull { it.attribute("href").startsWith("rider/") }
        ?.attribute("href")?.removePrefix("rider/")

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

fun getRiderDetails(riderSlug: String): RiderDetails {
    val doc = fetch("rider/$riderSlug")

    val allLi = doc.safe("ul.list li")

    fun findLiText(label: String): String =
        allLi.firstOrNull { li ->
            li.safe("div").any { div ->
                div.classNames.contains("bold") &&
                    div.text.trim().startsWith(label)
            }
        }?.text ?: ""

    // ── Date of birth ─────────────────────────────────────────────
    val dateOfBirth = findLiText("Date of birth")
        .substringAfter("Date of birth:")
        .substringBefore("(")
        .replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")
        .trim()

    // ── Weight & Height (same <li>) ───────────────────────────────
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
    val topResults = doc.safe("ul.topresults li")
        .take(3)
        .map { it.text.trim().replace(Regex("\\s+"), " ") }

    // ── Wins ──────────────────────────────────────────────────────
    val wins = doc.safe("ul.rider-kpi li")
        .firstOrNull { li ->
            li.safe("div.title").any { div ->
                div.text.trim().contains("Wins", ignoreCase = true)
            }
        }
        ?.let { li -> li.safe("div.kpi").firstOrNull()?.text?.trim() }
        ?: "0"

    // ── Teams (all, deduped, in order) ────────────────────────────
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
// UCI points for a specific season
// ────────────────────────────────────────────────────────────────

/**
 * Fetch the UCI points a rider scored in [season].
 *
 * PCS publishes a per-season points breakdown at:
 *   rider/<slug>/statistics/points-per-season
 *
 * The page renders a table whose rows pair a season (year) with the points
 * scored, split by points category (PCS / UCI / etc.) via column headers.
 * We locate the row for [season] and read the value under the "UCI" column.
 *
 * Layout (typical):
 *   <table class="basic">
 *     <thead><tr><th>Season</th><th>Points</th><th>PCS</th><th>UCI</th>…</tr></thead>
 *     <tbody>
 *       <tr><td><a href="rider/…/2025">2025</a></td><td>…</td><td>…</td><td>1234</td>…</tr>
 *       …
 *     </tbody>
 *   </table>
 *
 * Returns "" if the season row or UCI column can't be found.
 */
fun getUciPointsForSeason(riderSlug: String, season: Int): String {
    val doc = fetch("rider/$riderSlug/statistics/points-per-season")

    val table = doc.safe("table.basic").firstOrNull() ?: return ""

    // Find the index of the "UCI" column from the header row.
    val headerCells = table.safe("thead th")
        .ifEmpty { table.safe("thead tr td") }
    val uciColIdx = headerCells.indexOfFirst {
        it.text.trim().equals("UCI", ignoreCase = true)
    }
    if (uciColIdx < 0) return ""

    // Find the body row whose first cell matches the target season.
    val seasonStr = season.toString()
    val row = table.safe("tbody tr").firstOrNull { tr ->
        val firstCell = tr.safe("td").firstOrNull()?.text?.trim().orEmpty()
        firstCell == seasonStr || firstCell.startsWith(seasonStr)
    } ?: return ""

    val cells = row.safe("td")
    val raw = cells.getOrNull(uciColIdx)?.text?.trim().orEmpty()

    // Normalise: keep digits and a decimal separator, drop stray chars.
    val cleaned = raw.replace(Regex("[^0-9.]"), "")
    return cleaned
}

// ────────────────────────────────────────────────────────────────
// Team-page scraping
// ────────────────────────────────────────────────────────────────

fun scrapeTeam(teamUrl: String, gender: String, tier: String): List<RiderRow> {
    val doc = fetch(teamUrl)
    val name = teamName(doc)

    val specialty = LinkedHashMap<String, MutableList<String>>()
    val nationality = LinkedHashMap<String, String>()
    val age = LinkedHashMap<String, String>()
    val displayName = LinkedHashMap<String, String>()

    fun tableRows(stabClass: String): List<DocElement> =
        doc.safe("div.stab.$stabClass table.teamlist tbody tr")

    for (row in tableRows("specialty")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.safe("td")
        val spec = cells.lastOrNull()?.text?.trim().orEmpty()
        val list = specialty.getOrPut(slug) { mutableListOf() }
        if (spec.isNotEmpty() && spec !in list) list.add(spec)
        displayName.putIfAbsent(slug, try { row.findFirst("a").text.trim() } catch (_: Exception) { slug })
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
    }

    for (row in tableRows("country")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.safe("td")
        cells.lastOrNull()?.text?.trim()?.let { age.putIfAbsent(slug, it) }
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
        displayName.putIfAbsent(slug, try { row.findFirst("a").text.trim() } catch (_: Exception) { slug })
    }

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
            teamTier = tier,
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
            uciPointsPrevSeason = "",
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
val prevSeason = season - 1
System.err.println("Scraping WorldTour & ProTour teams for $season ...")
System.err.println("UCI points will be collected for the previous season ($prevSeason).")

val allEntries = mutableListOf<TeamEntry>()

// ── Men's WorldTour + ProTour ─────────────────────────────────
val menEntries = getTeamEntries("teams.php?year=$season", "M")
allEntries += menEntries
val menWt = menEntries.count { it.tier == "WT" }
val menPt = menEntries.count { it.tier == "PT" }
System.err.println("[M] Found $menWt WorldTour teams and $menPt ProTour teams.")

// ── Women's WorldTour + ProTour ───────────────────────────────
val womenEntries = getTeamEntries("teams.php?s=women&year=$season", "W")
allEntries += womenEntries
val womenWt = womenEntries.count { it.tier == "WT" }
val womenPt = womenEntries.count { it.tier == "PT" }
System.err.println("[W] Found $womenWt WorldTour teams and $womenPt ProTour teams.")

Thread.sleep(REQUEST_DELAY_MS)

// ── Scrape every team ─────────────────────────────────────────
val all = mutableListOf<RiderRow>()
var currentGroup = ""
var groupIndex = 0
var groupTotal = 0

for (entry in allEntries) {
    val group = "${entry.gender}/${entry.tier}"
    if (group != currentGroup) {
        currentGroup = group
        groupIndex = 0
        groupTotal = allEntries.count { "${it.gender}/${it.tier}" == group }
    }
    groupIndex++

    val rows = scrapeTeam(entry.url, entry.gender, entry.tier)
    System.err.println("[$group $groupIndex/$groupTotal] ${rows.firstOrNull()?.team ?: entry.url} — ${rows.size} riders")
    all += rows
    Thread.sleep(REQUEST_DELAY_MS)
}

// ── Second pass: individual rider pages ───────────────────────
System.err.println("Fetching individual rider details for ${all.size} riders ...")
val detailed = all.mapIndexed { idx, r ->
    val details = try {
        getRiderDetails(r.slug)
    } catch (e: Exception) {
        System.err.println("  ! ${r.slug} (details): ${e.message}")
        EMPTY_DETAILS
    }
    Thread.sleep(REQUEST_DELAY_MS)

    val uciPrev = try {
        getUciPointsForSeason(r.slug, prevSeason)
    } catch (e: Exception) {
        System.err.println("  ! ${r.slug} (uci $prevSeason): ${e.message}")
        ""
    }
    if ((idx + 1) % 25 == 0) System.err.println("  ... ${idx + 1}/${all.size}")
    Thread.sleep(REQUEST_DELAY_MS)

    val currentTeamLower = r.team.lowercase()
    val previousTeams = details.allTeams
        .filter { it.lowercase() != currentTeamLower }

    r.copy(
        dateOfBirth = details.dateOfBirth,
        placeOfBirth = details.placeOfBirth,
        height = details.height,
        weight = details.weight,
        wins = details.wins,
        uciPointsPrevSeason = uciPrev,
        topResults = details.topResults,
        previousTeams = previousTeams,
    )
}

// ── Write CSV ─────────────────────────────────────────────────
val out = File("worldtour_riders_$season.csv")

val maxSpecialties = (detailed.maxOfOrNull { it.specialties.size } ?: 0).coerceAtLeast(1)
val maxPrevTeams = (detailed.maxOfOrNull { it.previousTeams.size } ?: 0).coerceAtLeast(1)

out.bufferedWriter().use { w ->
    val specialtyHeaders = (1..maxSpecialties).joinToString(",") { "Specialty $it" }
    val prevTeamHeaders = (1..maxPrevTeams).joinToString(",") { "Previous team $it" }

    w.write("Gender,Team tier,Team,Rider,Nationality,Age,Date of birth,Place of birth," +
            "Height (m),Weight (kg),Wins,UCI points $prevSeason," +
            "Top result 1,Top result 2,Top result 3," +
            "$prevTeamHeaders,$specialtyHeaders\n")

    detailed.sortedWith(compareBy({ it.gender }, { it.teamTier }, { it.team }, { it.name })).forEach { r ->
        val topCells = (0 until 3).map { r.topResults.getOrElse(it) { "" } }
        val prevCells = (0 until maxPrevTeams).map { r.previousTeams.getOrElse(it) { "" } }
        val specCells = (0 until maxSpecialties).map { r.specialties.getOrElse(it) { "" } }

        w.write((listOf(
            r.gender, r.teamTier, r.team, r.name, r.nationality, r.age,
            r.dateOfBirth, r.placeOfBirth, r.height, r.weight, r.wins,
            r.uciPointsPrevSeason,
        ) + topCells + prevCells + specCells).joinToString(",") { csvCell(it) })
        w.write("\n")
    }
}
System.err.println("Wrote ${detailed.size} riders to ${out.name} " +
    "(up to $maxSpecialties specialties, $maxPrevTeams previous teams per rider)")
