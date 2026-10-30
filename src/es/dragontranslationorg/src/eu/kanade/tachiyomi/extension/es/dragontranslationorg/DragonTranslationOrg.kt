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
    override fun archiveSelector() = "a:has(img.ac-cover)"
    override val archiveUrlSelector = "a"

    override val mangaDetailsSelectorTitle = "div.hero h1"
    override val mangaDetailsSelectorStatus = "div.htags > span.htag"
    override val mangaDetailsSelectorDescription = "div.syn > p"
    override val mangaDetailsSelectorThumbnail = "div.hposter img"
    override val mangaDetailsSelectorGenre = "div.hchips a.chip"

    override val pageListParseSelector = "div.reading-content img.wp-manga-chapter-img"

    override fun archiveManga(element: Element, id: String) = super.archiveManga(element, id)?.apply {
        element.attr("title").takeIf(String::isNotBlank)?.let { title = it }
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

    // Populares y Recientes por GET (el sitio lista en /manga/?m_orderby=views|latest)
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
        val mangas = parseArchive(document)

        if (mangas.isEmpty() && page == 1) {
            error(
                "DEBUG lista: HTTP ${response.code} | ${response.request.url} | " +
                    "titulo: ${document.title()} | texto: ${document.body().text().take(200)}",
            )
        }

        val hasNextPage = document.selectFirst("a.nextpostslink, a.next, link[rel=next]") != null ||
            document.selectFirst("a[href*=/page/${page + 1}/]") != null

        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }
}
