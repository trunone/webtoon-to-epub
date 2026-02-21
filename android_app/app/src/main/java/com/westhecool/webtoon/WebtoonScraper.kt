package com.westhecool.webtoon

import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ComicInfo(val title: String, val author: String, val genre: String)
data class Chapter(val title: String, val url: String)

object WebtoonScraper {

    suspend fun getComicInfo(url: String): ComicInfo = withContext(Dispatchers.IO) {
        // Strip page parameter if present
        val cleanUrl = url.replace(Regex("&page=.*"), "")
        val doc = Jsoup.connect(cleanUrl).timeout(10000).get()
        val info = doc.select("div.info").first() ?: throw Exception("Info not found")

        val title = info.select(".subj").text().trim()
        val genre = info.select(".genre").text().trim()
        var author = info.select(".author").text().replace("author info", "").trim()
        if (author.isEmpty()) {
            author = info.select(".author_area").text().replace("author info", "").trim()
        }

        ComicInfo(title, author, genre)
    }

    suspend fun getChapterList(url: String): List<Chapter> = withContext(Dispatchers.IO) {
        val cleanUrl = url.replace(Regex("&page=.*"), "")
        val chapters = mutableListOf<Chapter>()
        var currentPage = 1
        var totalPages = 1 // Start with 1, will increase as we find more links

        while (currentPage <= totalPages) {
             try {
                 val doc = Jsoup.connect("$cleanUrl&page=$currentPage").timeout(10000).get()

                 // Check pagination on current page to update totalPages
                 val paginate = doc.select("div.paginate a")
                 for (link in paginate) {
                     val href = link.attr("href")
                     val pageNumStr = href.substringAfter("&page=", "")
                     // Ensure we are getting the page number correctly
                     if (pageNumStr.isNotEmpty() && pageNumStr != "#") {
                          val pageNum = pageNumStr.toIntOrNull() ?: 0
                          if (pageNum > totalPages) totalPages = pageNum
                     }
                 }

                 chapters.addAll(parseChaptersFromDoc(doc))
             } catch (e: Exception) {
                 e.printStackTrace()
                 // If a page fails, maybe stop or retry? For now, just continue to next logic or break
                 // Ideally we should retry
             }
             currentPage++
        }

        // Remove duplicates if any and reverse to have oldest first
        chapters.distinct().reversed()
    }

    private fun parseChaptersFromDoc(doc: org.jsoup.nodes.Document): List<Chapter> {
        val list = mutableListOf<Chapter>()
        val items = doc.select("li._episodeItem")
        for (item in items) {
            var title = item.select("span.subj").text()
            if (title.endsWith("BGM")) {
                title = title.substringBeforeLast("BGM").trim()
            }
            val href = item.select("a").attr("href")
            if (href.isNotEmpty()) {
                list.add(Chapter(title, href))
            }
        }
        return list
    }

    suspend fun getChapterImages(url: String): List<String> = withContext(Dispatchers.IO) {
        val doc = Jsoup.connect(url).timeout(10000).get()
        val imgs = doc.select("#_imageList img")
        imgs.mapNotNull { it.attr("data-url").ifEmpty { null } }
    }
}
