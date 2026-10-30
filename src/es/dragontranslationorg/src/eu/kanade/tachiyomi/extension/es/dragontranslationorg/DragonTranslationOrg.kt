package eu.kanade.tachiyomi.extension.es.dragontranslationorg

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class DragonTranslationOrg : Madara() {
    override val supportsPostId = false
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("es"))

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    override val filterGenresSelector = ".filters"

    // Enlaces a fichas de manga: /manga/slug/ con atributo title, sin depender de <img>
    override fun archiveSelector() =
        "a[href*=/manga/][title]:not([href*=?]):not([href*=/page/])"
    override val archiveUrlSelector = "a"

    override val mangaDetailsSelectorTitle = "div.hero h1"
    override val mangaDetailsSelectorStatus = "div.htags > span.htag"
    override val mangaDetailsSelectorDescription = "div.syn > p"
    override val mangaDetailsSelectorThumbnail = "div.hposter img"
    override val mangaDetailsSelectorGenre = "div.hchips a.chip"

    override val pageListParseSelector = "div.reading-content img.wp-manga-chapter-img"

    override fun archiveManga(element: Element, id: String) = super.archiveManga(element, id)?.apply {
        element.attr("title").takeIf(String::isNotBlank)?.let { title = it }
        if (thumbnail_url.isNullOrBlank()) {
            thumbnail_url = element.findCoverUrl()
        }
    }

    private fun Element.findCoverUrl(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-bg", "data-background", "data-cover", "data-image", "data-original")
        for (el in select(attrs.joinToString(",") { "[$it]" })) {
            for (attr in attrs) {
                val value = el.absUrl(attr)
                if (value.isNotBlank()) return value
            }
        }
        val style = selectFirst("[style*=url]")?.attr("style") ?: return null
        val raw = Regex("""url\(['"]?([^'")]+)""").find(style)?.groupValues?.get(1) ?: return null
        return baseUrl.toHttpUrl().resolve(raw)?.toString()
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override suspend fun fetchChapters(
        mangaPath: String,
        id: String,
        mangaPage: Document?,
    ): List<SChapter> {
        val json = mangaPage?.selectFirst("#mkChapters script[type=application/json]")?.data()
            ?: error(
                "DEBUG capitulos: no se encontro #mkChapters | titulo: ${mangaPage?.title()} | " +
                    "texto: ${mangaPage?.body()?.text()?.take(150)}",
            )
        return json.parseAs<ChapterListDto>().items.map { chapterDto ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapterDto.url)
                name = chapterDto.name
                date_upload = parseChapterDate(chapterDto.ago)
            }
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(page, "views")

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(page, "latest")

    private suspend fun fetchListing(page: Int, orderBy: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(mangaSubString)
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addPathSegment("")
            addQueryParameter("m_orderby", orderBy)
        }.build()

        val response = client.get(url)
        val document = response.asJsoup()
        val mangas = parseArchive(document).distinctBy { it.url }

        if (mangas.isEmpty() && page == 1) {
            val links = document.select("a[href*=/manga/]").take(3).joinToString(" || ") { it.outerHtml().take(160) }
            error("DEBUG lista vacia | enlaces: $links")
        }

        val hasNextPage = document.selectFirst("a.nextpostslink, a.next, link[rel=next]") != null ||
            document.selectFirst("a[href*=/page/${page + 1}/]") != null

        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }
}
