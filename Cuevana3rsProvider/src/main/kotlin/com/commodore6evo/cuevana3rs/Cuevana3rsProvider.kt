package com.commodore6evo.cuevana3rs

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import android.util.Base64

@Suppress("DEPRECATION")
class Cuevana3rsProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://cuevana3.rs"
    override var name = "Cuevana3rs"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "es"

    // Enable this when your provider has a main page
    override val hasMainPage = true

    // This function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val response = app.get(url).text
        val document = Jsoup.parse(response)

        return document.select(".Posters-link").mapNotNull {
            val title = it.selectFirst("p")?.text() ?: return@mapNotNull null
            val href = it.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            val typeText = it.selectFirst("div.centrado")?.text() ?: ""
            
            val isMovie = typeText.contains("Película", ignoreCase = true) || href.contains("/pelicula/")
            
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).text
        val document = Jsoup.parse(response)

        val title = document.selectFirst("h1.Title")?.text() ?: ""
        val poster = document.selectFirst(".Poster img")?.attr("src")
        val synopsis = document.selectFirst(".Description p")?.text()
            ?: document.selectFirst("div.col-md-9 p")?.text()

        val badges = document.select("span.badge-custom").map { it.text().trim() }
        val year = badges.find { it.matches(Regex("\\d{4}")) }?.toIntOrNull()
        val score = badges.find { it.contains("/10") }?.replace("/10", "")?.toDoubleOrNull()?.times(10)?.toInt()

        val isMovie = url.contains("/pelicula/")
        
        // Extract the embed URL from the videoUrls script
        val embedUrl = document.select("script").map { it.data() }
            .find { it.contains("var videoUrls =") }
            ?.let { 
                // Regex handles both single and double quotes
                Regex("var videoUrls\\s*=\\s*\\[[\"'](.*?)[\"']\\];").find(it)?.groupValues?.get(1)
            }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, embedUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.score = Score.from(score, 100)
            }
        } else {
            // Basic series implementation
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.score = Score.from(score, 100)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false

        val response = app.get(data, referer = mainUrl).text
        val dataLinkJson = Regex("let\\s+dataLink\\s*=\\s*(.*?);").find(response)?.groupValues?.get(1) ?: return false
        
        val dataLink = parseJson<List<DataLink>>(dataLinkJson)
        
        dataLink.forEach { lang ->
            lang.sortedEmbeds.forEach { embed ->
                val jwt = embed.link.split(".")
                if (jwt.size == 3) {
                    try {
                        val payload = String(Base64.decode(jwt[1], Base64.DEFAULT))
                        // Clean backticks and potential escaped characters
                        val finalUrl = parseJson<Payload>(payload).link.replace("`", "").trim()
                        
                        if (finalUrl.startsWith("http")) {
                            loadExtractor(finalUrl, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        // Log or ignore individual link failures
                    }
                }
            }
        }

        return true
    }

    data class DataLink(
        val file_id: Int? = null,
        val video_language: String,
        val sortedEmbeds: List<Embed>
    )
    
    data class Embed(
        val servername: String? = null,
        val link: String,
        val type: String? = null
    )
    
    data class Payload(
        val link: String
    )
}