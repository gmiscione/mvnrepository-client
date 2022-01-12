package devcsrj.mvnrepository

import okhttp3.OkHttpClient
import java.util.*

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ENGLISH)

    val httpClient = OkHttpClient()
    try {
        val api = MvnRepositoryApi.create(okHttpClient = httpClient)
        val artifact = api.getArtifact("ch.qos.logback", "logback-classic", "1.2.10").orElseThrow()
        println(artifact.license)
    } finally {
        httpClient.connectionPool().evictAll();
    }

}
