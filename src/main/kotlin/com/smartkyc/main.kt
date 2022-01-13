package com.smartkyc

import devcsrj.mvnrepository.MvnRepositoryApi
import okhttp3.OkHttpClient
import java.io.File
import java.util.*

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ENGLISH)

    val httpClient = OkHttpClient()
    try {
        val api = MvnRepositoryApi.create(okHttpClient = httpClient)
        val artifactLines = File(args[0]).useLines { it //
            .filter { line -> line.isNotBlank() } //
            .filter { line -> !line.startsWith("#") } //
            .toList() }
        for (artifactLine in artifactLines) {
            val parts = artifactLine.split(",")
            val data:Pair<String, String?>? = api.getArtifact(parts[0], parts[1], parts[2]) //
                .map { Pair( //
                    it.license.joinToString(","), //
                    it.homepage?.toASCIIString()) } //
                .orElse(null)
            if (data != null) {
                fun String?.sanitize() = this?.replace("'", "\\'") ?: ""

                println("UPDATE libraries SET licenses = '${data.first.sanitize()}', link = '${data.second.sanitize()}' " + //
                        "WHERE groupid = '${parts[0].sanitize()}' AND artifactid = '${parts[1].sanitize()}' AND version = '${parts[2].sanitize()}';")
            }

            if (args.size >= 2) {
                Thread.sleep(args[1].toLong())
            }
        }

    } finally {
        httpClient.connectionPool().evictAll();
    }

}
