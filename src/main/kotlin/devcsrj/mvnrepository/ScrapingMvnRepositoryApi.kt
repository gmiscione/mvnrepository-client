/**
 * Copyright © 2018 Reijhanniel Jearl Campos (devcsrj@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package devcsrj.mvnrepository

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.funktionale.memoization.memoize
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pl.droidsonroids.retrofit2.JspoonConverterFactory
import retrofit2.Retrofit
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import kotlin.math.ceil

internal class ScrapingMvnRepositoryApi(
	private val baseUrl: HttpUrl,
	private val okHttpClient: OkHttpClient
) : MvnRepositoryApi {

	companion object {
		private val logger: Logger = LoggerFactory.getLogger(MvnRepositoryApi::class.java)

		const val MAX_LIMIT = 10 // Pages are always in 10 entries
		const val MAX_PAGE = 50 // Site throws a 404 when exceeding 50
	}

	private val pageApi: MvnRepositoryPageApi

	private val repositories: () -> List<Repository>

	init {
		val retrofit = Retrofit.Builder()
			.baseUrl(baseUrl)
			.client(okHttpClient)
			.addConverterFactory(JspoonConverterFactory.create())
			.validateEagerly(true)
			.build()
		pageApi = retrofit.create(MvnRepositoryPageApi::class.java)

		// Repositories are unlikely to change, so we memoize them instead of fetching them every time
		repositories = {
			var p = 1
			val repos = mutableListOf<Repository>()
			while (true) { // there are only 2(?) pages, we'll query them all at once now
				val response = pageApi.getRepositoriesPage(p).execute()
				p++ // next page
				if (!response.isSuccessful) {
					logger.warn("Request to $baseUrl failed while fetching repositories, got: ${response.code()}")
					break
				}
				val page = response.body() ?: break
				if (page.entries.isEmpty())
					break // stop when the page no longer shows an entry (we exceeded max page)

				repos.addAll(page.entries
					.filter { it.isPopulated() }
					.map { Repository(it.id!!, it.name!!, it.uri!!) })
			}
			repos.toList()
		}.memoize()
	}

	override fun getRepositories(): List<Repository> = repositories()

	override fun getArtifactVersions(groupId: String, artifactId: String): List<String> {
		val response = pageApi.getArtifactVersionsPage(groupId, artifactId).execute()
		if (!response.isSuccessful) {
			logger.warn(
				"Request to $baseUrl failed while fetching versions for artifact '" +
						"$groupId:$artifactId', got: ${response.code()}"
			)
			return emptyList()
		}

		val body = response.body() ?: return emptyList()
		return body.versions
	}

	override fun getArtifact(groupId: String, artifactId: String, version: String): Optional<Artifact> {
		val response = pageApi.getArtifactPage(groupId, artifactId, version).execute()
		if (!response.isSuccessful) {
			logger.warn(
				"Request to $baseUrl failed while fetching artifact '" +
						"$groupId:$artifactId:$version', got: ${response.code()}"
			)
			return Optional.empty()
		}
		val body = response.body() ?: return Optional.empty()
		val artifact = if (body.tableRows.isEmpty()) {
			val localDate = body.date?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
			Artifact(groupId, artifactId, version, body.license, body.homepage, localDate, body.snippets)
		} else {
			val fieldToValueCell = body.tableRows //
				.associate { it.select("th").text()!!.lowercase() to it.select("td") }
			val licenses = fieldToValueCell["license"]?.select("span")?.map { it.text() } ?: emptyList()
			val homepage = fieldToValueCell["homepage"]?.select("a")?.asSequence() //
				?.map { URI(it.attr("href")) } //
				?.first()
			val date = fieldToValueCell["date"]?.first()?.text()?.let {
				try {
					val value = if (it.startsWith("(")) {
						it.substring(1, it.length - 1)
					} else {
						it
					}
					LocalDate.parse(value, DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH))
				} catch (e: DateTimeParseException) {
					logger.warn("Failed to parse date $it", e)
					null
				}
			}
			Artifact(groupId, artifactId, version, licenses, homepage, date, body.snippets)
		}

		return Optional.of(artifact)
	}

	override fun search(query: String, page: Int): Page<ArtifactEntry> {
		if (page < 1 || page > MAX_PAGE)
			return Page.empty()

		val response = pageApi.search(query, page, "relevance").execute()
		if (!response.isSuccessful) {
			logger.warn("Request to $baseUrl failed while searching for '$query' on page '$page'")
			return Page.empty()
		}

		val body = response.body() ?: return Page.empty()
		val entries = body.entries
			.filter { it.isPopulated() }
			.map { ArtifactEntry(it.groupId!!, it.artifactId!!, it.license, it.description!!, it.releaseDate!!) }

		val totalPages = ceil((body.totalResults / MAX_LIMIT).toDouble()).toInt().coerceAtMost(MAX_PAGE)
		return Page(page, MAX_LIMIT, entries.toList(), totalPages, body.totalResults)
	}
}
