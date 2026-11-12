package eu.kanade.tachiyomi.extension.es.templescanesp

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TempleScanEsp : Madara() {
    override val supportsFilterFetching get() = false

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds) { it.host == baseUrl.toHttpUrl().host }

    override fun getHomeUrl() = "$baseUrl/comics"

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    override suspend fun fetchFilterData(): JsonElement = JsonArray(emptyList())

    // ---------- Listados ----------

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val text = client.get("$baseUrl/api/backend/mangas/ranking?timeframe=all&limit=30").use { it.body.string() }
        val root = Json.parseToJsonElement(text)
        val array = root as? JsonArray
            ?: (root as? JsonObject)?.get("data") as? JsonArray
            ?: error("DEBUG ranking: formato inesperado | ${text.take(200)}")
        val mangas = array.mapNotNull { (it as? JsonObject)?.let(::comicFromJson) }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchLibrary(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchLibrary(page, query)

    private suspend fun fetchLibrary(page: Int, query: String = ""): MangasPage {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                // No sé cuál de estos nombres usa el sitio; los demás se ignoran
                addQueryParameter("search", query)
                addQueryParameter("q", query)
                addQueryParameter("title", query)
            }
        }.build()

        val rsc = client.get(url).asJsoup().flightData()
        val json = listOf("initialComics", "comics", "series", "mangas", "results")
            .firstNotNullOfOrNull { extractJson(rsc, "\"$it\":") }
            ?: error(
                "DEBUG biblioteca: no encontre la lista | claves: " +
                    Regex("\"([A-Za-z_]+)\":\\[").findAll(rsc).map { it.groupValues[1] }.distinct().take(15).toList(),
            )
        val array = Json.parseToJsonElement(json) as? JsonArray ?: error("DEBUG biblioteca: formato inesperado")

        var mangas = array.mapNotNull { (it as? JsonObject)?.let(::comicFromJson) }
        val hasNextPage = mangas.size >= 20

        if (query.isNotBlank()) {
            val wanted = query.normalized()
            mangas = mangas.filter { it.title.normalized().contains(wanted) }
        }
        return MangasPage(mangas, hasNextPage)
    }

    private fun comicFromJson(obj: JsonObject): SManga? {
        val slug = obj.str("series_slug") ?: obj.str("slug") ?: return null
        val name = obj.str("title") ?: obj.str("nombre") ?: return null
        return SManga.create().apply {
            url = "/comic/$slug/"
            title = name
            thumbnail_url = coverUrl(obj.str("thumbnail") ?: obj.str("portada_url"))
        }
    }

    private fun coverUrl(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/assets/$raw"
    }

    // ---------- Ficha y capítulos ----------

    private fun slugOf(manga: SManga) = manga.url.trimEnd('/').substringAfterLast('/')

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${slugOf(manga)}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "comic") return null
        val slug = segments[1]
        val document = client.get("$baseUrl/comic/$slug").asJsoup()
        return parseAedexDetails(document, slug).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = slugOf(manga)
        val document = client.get("$baseUrl/comic/$slug").asJsoup()
        val details = if (fetchDetails) parseAedexDetails(document, slug) else manga
        val chapterList = if (fetchChapters) parseAedexChapters(document, slug) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseAedexDetails(document: Document, slug: String): SManga = SManga.create().apply {
        url = "/comic/$slug/"
        title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: slug
        description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.replace("\r\n", "\n")
            ?.takeIf { it.isNotBlank() }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        genre = document.select("a[href^='/comics?genre=']").eachText().distinct().joinToString().ifBlank { null }
        status = document.select("article span")
            .filter { it.ownText().length < 20 }
            .map { statusFrom(it.ownText()) }
            .firstOrNull { it != SManga.UNKNOWN } ?: SManga.UNKNOWN
    }

    private fun parseAedexChapters(document: Document, slug: String): List<SChapter> {
        val rsc = document.flightData()
        val json = extractJson(rsc, "\"capitulos\":")
            ?: error("DEBUG capitulos: no encontre la lista | titulo: ${document.title()}")
        val array = Json.parseToJsonElement(json) as? JsonArray ?: error("DEBUG capitulos: formato inesperado")
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val published = obj.str("estado")
            if (published != null && published != "publicado") return@mapNotNull null
            val chapterSlug = obj.str("chapter_slug") ?: obj.str("numero") ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/comic/$slug/$chapterSlug"
                name = obj.str("chapter_name") ?: obj.str("titulo") ?: "Capítulo $chapterSlug"
                date_upload = parseIsoDate(obj.str("date_created"))
            }
        }
    }

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    // ---------- Lectura ----------

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // El lector del sitio solo funciona en un navegador. Devolvemos una única página cuya
    // "imagen" es la dirección del capítulo: falla al cargar y Mihon ofrece "Abrir en WebView".
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        return listOf(Page(0, chapterUrl, chapterUrl))
    }

    // ---------- Utilidades ----------

    // Next.js guarda los datos de la página en scripts __next_f.push([1,"..."])
    private fun Document.flightData(): String = buildString {
        for (script in select("script")) {
            val data = script.data()
            if (!data.contains("__next_f.push")) continue
            val argument = data.substringAfter("__next_f.push(").substringBeforeLast(")")
            val array = runCatching { Json.parseToJsonElement(argument) }.getOrNull() as? JsonArray ?: continue
            if (array.size < 2) continue
            (array[1] as? JsonPrimitive)?.contentOrNull?.let { append(it) }
        }
    }

    private fun extractJson(text: String, key: String): String? {
        val keyIndex = text.indexOf(key)
        if (keyIndex < 0) return null
        var start = keyIndex + key.length
        while (start < text.length && text[start].isWhitespace()) start++
        val open = text.getOrNull(start) ?: return null
        if (open != '[' && open != '{') return null
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else if (c == '"') {
                inString = true
            } else if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun String.normalized(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()

    private fun parseIsoDate(value: String?): Long = value
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: 0L

    private fun statusFrom(text: String?): Int {
        val value = text?.lowercase().orEmpty()
        return when {
            "emisi" in value || "curso" in value || "ongoing" in value -> SManga.ONGOING
            "finaliz" in value || "complet" in value -> SManga.COMPLETED
            "paus" in value || "hiato" in value || "hiatus" in value -> SManga.ON_HIATUS
            "cancel" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}
