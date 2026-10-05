package com.jjt.ingsis.snippets.language.printscript

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

fun printScriptHttpClient(properties: PrintScriptProperties): RestClient {
    val connection = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    val requestFactory = JdkClientHttpRequestFactory(connection)
    requestFactory.setReadTimeout(properties.readTimeout)

    return RestClient
        .builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(requestFactory)
        .build()
}
