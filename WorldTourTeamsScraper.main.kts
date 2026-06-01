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
    "nz" to "New Zealand", "jp" to "Japan", "ar" to "Argentina", "br" to "Brazil",
    "cr" to "Costa Rica", "mx" to "Mexico", "pe" to "Peru", "ve" to "Venezuela",
    "il" to "Israel", "tr" to "Turkey", "ua" to "Ukraine", "by" to "Belarus",
    "hu" to "Hungary", "ro" to "Romania", "rs" to "Serbia", "hr" to "Croatia",
    "fi" to "Finland", "se" to "Sweden", "lt" to "Lithuania", "nl" to "Netherlands",
)

data class RiderRow(
    val gender: String,
    val team: String,
    val name: String,
    val nationality: String,
    val age: String,
    val specialty: String,
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

/**
 * WorldTour team page URLs for a season and category.
 * category "worldtour" = men's WorldTour, "women" = women's WorldTour (women elite).
 * e.g. team/xds-astana-team-2026
 */
fun getTeamUrls(season: Int, category: String): List<String> {
    val doc = fetch("teams.php?year=$season&s=$category")
    return doc.findAll(".list.fs14.columns2.mob_columns1 a")
        .map { it.attribute("href") }
        .filter { it.startsWith("team/") }
        .distinct()
}

/** slug from <a href="rider/<slug>"> */
fun DocElement.riderSlug(): String? =
    findAll("a").firstOrNull { it.attribute("href").startsWith("rider/") }
        ?.attribute("href")?.removePrefix("rider/")

/** flag code from <span class="flag xx"> */
fun DocElement.flagCode(): String? =
    findAll("span.flag").firstOrNull()?.classNames
        ?.firstOrNull { it != "flag" }

fun teamName(doc: Doc): String =
    doc.findFirst(".page-title h1").text.substringBefore('(').trim()

/**
 * Each team page contains several hidden tab tables (div.stab.*). We read
 * the specialty, country, and age tabs and join them on the rider slug.
 */
fun scrapeTeam(teamUrl: String, gender: String): List<RiderRow> {
    val doc = fetch(teamUrl)
    val name = teamName(doc)

    val specialty = LinkedHashMap<String, String>()  // slug -> specialty (first wins)
    val nationality = LinkedHashMap<String, String>() // slug -> country
    val age = LinkedHashMap<String, String>()          // slug -> age (years)
    val displayName = LinkedHashMap<String, String>()  // slug -> "SURNAME First"

    fun tableRows(stabClass: String): List<DocElement> =
        doc.findAll("div.stab.$stabClass table.teamlist tbody tr")

    // Specialty tab: columns # | rider | specialty
    for (row in tableRows("specialty")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.findAll("td")
        val spec = cells.lastOrNull()?.text?.trim().orEmpty()
        specialty.putIfAbsent(slug, spec)                       // Romele-style dupes: keep first
        displayName.putIfAbsent(slug, row.findFirst("a").text.trim())
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
    }

    // Country tab: columns # | rider | age (whole years)
    for (row in tableRows("country")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.findAll("td")
        cells.lastOrNull()?.text?.trim()?.let { age.putIfAbsent(slug, it) }
        row.flagCode()?.let { nationality.putIfAbsent(slug, FLAG_TO_COUNTRY[it] ?: it) }
        displayName.putIfAbsent(slug, row.findFirst("a").text.trim())
    }

    // Age tab is a fallback for age if the country tab was empty.
    for (row in tableRows("age")) {
        val slug = row.riderSlug() ?: continue
        if (age.containsKey(slug)) continue
        val raw = row.findAll("td").lastOrNull()?.text?.trim().orEmpty()
        age.putIfAbsent(slug, raw.substringBefore('y').trim())  // "28y + 3d" -> "28"
    }

    // Union of all slugs we saw, preserving encounter order.
    val slugs = LinkedHashSet<String>().apply {
        addAll(specialty.keys); addAll(nationality.keys); addAll(age.keys)
    }

    return slugs.map { slug ->
        RiderRow(
            gender = gender,
            team = name,
            name = displayName[slug] ?: slug,
            nationality = nationality[slug] ?: "",
            age = age[slug] ?: "",
            specialty = specialty[slug] ?: "",
        )
    }
}

fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' })
        "\"" + s.replace("\"", "\"\"") + "\"" else s

val season = args.firstOrNull()?.toIntOrNull() ?: 2026
System.err.println("Scraping WorldTour teams for $season ...")

// category code -> gender label written to the CSV
val categories = linkedMapOf(
    "worldtour" to "M",  // men's WorldTour
    "we" to "W",         // women's WorldTour (women elite)
)

val all = mutableListOf<RiderRow>()
for ((category, gender) in categories) {
    val teamUrls = getTeamUrls(season, category)
    System.err.println("[$gender] Found ${teamUrls.size} teams.")
    for ((i, url) in teamUrls.withIndex()) {
        val rows = scrapeTeam(url, gender)
        System.err.println("[$gender ${i + 1}/${teamUrls.size}] ${rows.firstOrNull()?.team ?: url} — ${rows.size} riders")
        all += rows
        Thread.sleep(REQUEST_DELAY_MS)
    }
}

val out = File("worldtour_riders_$season.csv")
out.bufferedWriter().use { w ->
    w.write("Gender,Team,Rider,Nationality,Age,Specialty\n")
    all.sortedWith(compareBy({ it.gender }, { it.team }, { it.name })).forEach { r ->
        w.write(listOf(r.gender, r.team, r.name, r.nationality, r.age, r.specialty)
            .joinToString(",") { csvCell(it) })
        w.write("\n")
    }
}
System.err.println("Wrote ${all.size} riders to ${out.name}")
