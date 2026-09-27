package com.jjt.ingsis.snippets

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SnippetsServiceApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<SnippetsServiceApplication>(*args)
}
