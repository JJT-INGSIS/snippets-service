package com.jjt.ingsis.snippets

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SnippetsServiceApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    if (args.firstOrNull() == "parser-admin") {
        com.jjt.ingsis.snippets.revalidation.ParserAdminCommand
            .run(args.drop(1))
    } else {
        runApplication<SnippetsServiceApplication>(*args)
    }
}
