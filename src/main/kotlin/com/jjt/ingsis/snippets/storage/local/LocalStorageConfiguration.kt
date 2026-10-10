package com.jjt.ingsis.snippets.storage.local

import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LocalStorageConfiguration {
    @Bean
    fun snippetContentStorage(properties: LocalStorageProperties): SnippetContentStorage =
        LocalSnippetContentStorage(ContentDirectory.prepare(properties.directory))
}
