package com.smartkyc

import devcsrj.mvnrepository.MvnRepositoryApi
import okhttp3.OkHttpClient
import java.util.*

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ENGLISH)

    val httpClient = OkHttpClient()
    try {
        val api = MvnRepositoryApi.create(okHttpClient = httpClient)
        val artifact = api.getArtifact("org.springframework", "spring-context", "5.3.14").orElseThrow()
        println(artifact)
    } finally {
        httpClient.connectionPool().evictAll();
    }

}
