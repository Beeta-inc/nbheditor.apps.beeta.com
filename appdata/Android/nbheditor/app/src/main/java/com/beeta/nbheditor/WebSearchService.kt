package com.beeta.nbheditor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object WebSearchService {
    private const val TAG = "WebSearchService"

    /**
     * Searches the web using DuckDuckGo HTML and returns a formatted string of the top results.
     */
    suspend fun searchWeb(query: String, maxResults: Int = 3): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting web search for: $query")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // Using html.duckduckgo.com as it doesn't require JS
            val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encodedQuery")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(10000)
                .get()

            val results = doc.select(".result__body")
            if (results.isEmpty()) {
                Log.w(TAG, "No results found for query: $query")
                return@withContext "No web search results available."
            }

            val formattedResults = StringBuilder()
            var count = 0

            for (result in results) {
                if (count >= maxResults) break

                val titleElement = result.selectFirst(".result__title")
                val snippetElement = result.selectFirst(".result__snippet")
                val urlElement = result.selectFirst(".result__url")

                val title = titleElement?.text()?.trim() ?: "No title"
                val snippet = snippetElement?.text()?.trim() ?: "No snippet"
                val url = urlElement?.attr("href")?.trim() ?: "No URL"

                // Basic filtering to remove empty results
                if (title != "No title" && snippet != "No snippet") {
                    count++
                    formattedResults.append("[$count] Title: $title\n")
                    formattedResults.append("URL: $url\n")
                    formattedResults.append("Snippet: $snippet\n\n")
                }
            }

            val finalContext = formattedResults.toString().trim()
            Log.d(TAG, "Search completed. Found $count results.")
            return@withContext finalContext

        } catch (e: Exception) {
            Log.e(TAG, "Error performing web search: ${e.message}", e)
            return@withContext "Error performing web search: ${e.message}"
        }
    }
}
