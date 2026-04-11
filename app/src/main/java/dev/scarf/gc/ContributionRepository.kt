package dev.scarf.gc

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object ContributionRepository {
    fun fetch(handle: String): Result<ContributionStats> =
        runCatching {
            val normalizedHandle = normalizeHandle(handle)
            require(normalizedHandle.isNotBlank()) { "Type a handle" }

            val encodedHandle = URLEncoder.encode(normalizedHandle, StandardCharsets.UTF_8.toString())
            val url = URL("https://github.com/users/$encodedHandle/contributions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ContributionWidget/1.0")
                setRequestProperty("Accept", "text/html")
            }

            try {
                val http = connection
                val body = runCatching {
                    http.inputStream.bufferedReader().use { reader -> reader.readText() }
                }.getOrElse {
                    http.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }

                if (http.responseCode !in 200..299) {
                    error(if (http.responseCode == 404) "Handle not found" else "Unable to load")
                }

                parseContributionStats(body) ?: error("Unable to parse contributions")
            } finally {
                connection.disconnect()
            }
        }
}
