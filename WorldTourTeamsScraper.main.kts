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
    val slug: String,
    val name: String,
    val nationality: String,
    val age: String,
    val dateOfBirth: String,
    val placeOfBirth: String,
    val height: String,
    val weight: String,
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

/**
 * WorldTour team page URLs from a given listing path.
 * Men's WorldTour and women's WorldTour live at different URLs on PCS,
 * so we pass the full listing path rather than a query-param category.
 */
fun getTeamUrls(listingPath: String): List<String> {
    val doc = fetch(listingPath)
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

/** The extra fields that only exist on an individual rider's page. */
data class RiderDetails(
    val dateOfBirth: String,
    val placeOfBirth: String,
    val height: String,
    val weight: String,
    val topResult: String,
)

val EMPTY_DETAILS = RiderDetails("", "", "", "", "")

/**
 * Read a single value out of the rider info block by its label.
 * The block renders as "... Date of birth: 15 July 1989 (36) Weight: 63 kg ...".
 * We grab the text between the wanted label and the next label (or end).
 */
fun valueAfterLabel(blockText: String, label: String, nextLabels: List<String>): String {
    val start = blockText.indexOf(label)
    if (start < 0) return ""
    val after = start + label.length
    val end = nextLabels
        .map { blockText.indexOf(it, after) }
        .filter { it >= 0 }
        .minOrNull() ?: blockText.length
    return blockText.substring(after, end).trim()
}

/**
 * Fetch one rider page and extract date/place of birth, height, weight,
 * and the single highest (first-listed) top result from the palmares.
 */
fun getRiderDetails(riderSlug: String): RiderDetails {
    val doc = fetch("rider/$riderSlug")

    // The info block holds the labelled personal data.
    val infoText = (doc.findAll("div.rdr-info-cont").firstOrNull()?.text ?: "")
        .replace("\u00A0", " ")

    val labels = listOf(
        "Date of birth:", "Nationality:", "Weight:", "Height:",
        "Place of birth:", "Specialties", "Specialty",
    )

    // Date of birth: keep "15 July 1989", drop the "( 36 )" age and ordinal suffixes.
    val dobRaw = valueAfterLabel(infoText, "Date of birth:", labels - "Date of birth:")
    val dateOfBirth = dobRaw.substringBefore("(")
        .replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")  // "15th" -> "15"
        .trim()

    val weight = valueAfterLabel(infoText, "Weight:", labels - "Weight:")
        .removeSuffix("kg").trim()
    val height = valueAfterLabel(infoText, "Height:", labels - "Height:")
        .removeSuffix("m").trim()
    val placeOfBirth = valueAfterLabel(infoText, "Place of birth:", labels - "Place of birth:").trim()

    // Top results: PCS lists the rider's biggest wins/results, most prestigious first.
    // The list lives in the palmares/results sidebar; take the first entry's text.
    val topResult = doc.findAll("ul.list.rdr-results li").firstOrNull()?.text?.trim()
        ?: doc.findAll("div.mt20 ul.list li").firstOrNull()?.text?.trim()
        ?: ""

    return RiderDetails(
        dateOfBirth = dateOfBirth,
        placeOfBirth = placeOfBirth,
        height = height,
        weight = weight,
        topResult = topResult.replace(Regex("\\s+"), " "),
    )
}

/**
 * Each team page contains several hidden tab tables (div.stab.*). We read
 * the specialty, country, and age tabs and join them on the rider slug.
 */
fun scrapeTeam(teamUrl: String, gender: String): List<RiderRow> {
    val doc = fetch(teamUrl)
    val name = teamName(doc)

    val specialty = LinkedHashMap<String, MutableList<String>>() // slug -> all specialties
    val nationality = LinkedHashMap<String, String>() // slug -> country
    val age = LinkedHashMap<String, String>()          // slug -> age (years)
    val displayName = LinkedHashMap<String, String>()  // slug -> "SURNAME First"

    fun tableRows(stabClass: String): List<DocElement> =
        doc.findAll("div.stab.$stabClass table.teamlist tbody tr")

    // Specialty tab: columns # | rider | specialty.
    // A rider can appear in multiple specialty rows (e.g. Hills AND Oneday);
    // collect them all, keeping order and dropping duplicates.
    for (row in tableRows("specialty")) {
        val slug = row.riderSlug() ?: continue
        val cells = row.findAll("td")
        val spec = cells.lastOrNull()?.text?.trim().orEmpty()
        val list = specialty.getOrPut(slug) { mutableListOf() }
        if (spec.isNotEmpty() && spec !in list) list.add(spec)
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
            slug = slug,
            name = displayName[slug] ?: slug,
            nationality = nationality[slug] ?: "",
            age = age[slug] ?: "",
            dateOfBirth = "",
            placeOfBirth = "",
            height = "",
            weight = "",
            topResult = "",
            specialties = specialty[slug] ?: emptyList(),
        )
    }
}

fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' })
        "\"" + s.replace("\"", "\"\"") + "\"" else s

val season = args.firstOrNull()?.toIntOrNull() ?: 2026
System.err.println("Scraping WorldTour teams for $season ...")

// listing path -> gender label written to the CSV.
// Men's and women's WorldTour are at different URLs on PCS.
val categories = linkedMapOf(
    "teams.php?year=$season&s=worldtour" to "M",  // men's WorldTour
    "teams/women" to "W",                          // women's WorldTour
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

// Second pass: visit each rider's own page for date/place of birth, height,
// weight, and their single highest top result. This is the slow part — one
// request per rider — so the polite delay matters most here.
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
        topResult = details.topResult,
    )
}

val out = File("worldtour_riders_$season.csv")
// Widest rider determines how many specialty columns we need.
val maxSpecialties = (detailed.maxOfOrNull { it.specialties.size } ?: 0).coerceAtLeast(1)
out.bufferedWriter().use { w ->
    val specialtyHeaders = (1..maxSpecialties).joinToString(",") { "Specialty $it" }
    w.write("Gender,Team,Rider,Nationality,Age,Date of birth,Place of birth,Height (m),Weight (kg),Top result,$specialtyHeaders\n")
    detailed.sortedWith(compareBy({ it.gender }, { it.team }, { it.name })).forEach { r ->
        // Pad the specialty list out to maxSpecialties so every row has the same columns.
        val specCells = (0 until maxSpecialties).map { r.specialties.getOrElse(it) { "" } }
        w.write((listOf(
            r.gender, r.team, r.name, r.nationality, r.age,
            r.dateOfBirth, r.placeOfBirth, r.height, r.weight, r.topResult,
        ) + specCells).joinToString(",") { csvCell(it) })
        w.write("\n")
    }
}
System.err.println("Wrote ${detailed.size} riders to ${out.name} (up to $maxSpecialties specialties per rider)")
