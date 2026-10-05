package com.jjt.ingsis.snippets

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SnippetsServiceApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<SnippetsServiceApplication>(*args)
}
