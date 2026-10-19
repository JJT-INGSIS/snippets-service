package com.jjt.ingsis.snippets.storage

import com.jjt.ingsis.snippets.storage.asset.AssetContentStorage
import com.jjt.ingsis.snippets.storage.asset.AssetHttpTransport
import com.jjt.ingsis.snippets.storage.asset.AssetStorageProperties
import com.jjt.ingsis.snippets.storage.local.ContentDirectory
import com.jjt.ingsis.snippets.storage.local.LocalSnippetContentStorage
import com.jjt.ingsis.snippets.storage.local.LocalStorageProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("storage")
data class StorageProperties(
    val provider: String = "local",
)

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {
    @Bean
    fun snippetContentStorage(
        selection: StorageProperties,
        local: LocalStorageProperties,
        asset: AssetStorageProperties,
    ): SnippetContentStorage =
        when (selection.provider) {
            "local" -> LocalSnippetContentStorage(ContentDirectory.prepare(local.directory))
            "asset" -> AssetContentStorage(AssetHttpTransport(asset), asset.maxContentBytes)
            else -> error("storage.provider must be local or asset")
        }
}
