package com.jjt.ingsis.snippets.storage.asset

import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageConfiguration
import com.jjt.ingsis.snippets.storage.StorageProperties
import com.jjt.ingsis.snippets.storage.local.LocalSnippetContentStorage
import com.jjt.ingsis.snippets.storage.local.LocalStorageProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AssetWiringTest {
    @TempDir lateinit var directory: Path

    private val runner =
        ApplicationContextRunner().withUserConfiguration(
            Properties::class.java,
            StorageConfiguration::class.java,
        )

    @Test
    fun `local remains the default and asset does not touch the local directory`() {
        runner.withPropertyValues("storage.local.directory=$directory").run { context ->
            assertThat(context).hasSingleBean(SnippetContentStorage::class.java)
            assertThat(
                context.getBean(SnippetContentStorage::class.java),
            ).isInstanceOf(LocalSnippetContentStorage::class.java)
        }
        val absent = directory.resolve("not-created")
        runner
            .withPropertyValues(
                "storage.provider=asset",
                "storage.asset.base-url=http://localhost:8083",
                "storage.local.directory=$absent",
            ).run { context ->
                assertThat(context).hasSingleBean(SnippetContentStorage::class.java)
                assertThat(
                    context.getBean(SnippetContentStorage::class.java),
                ).isInstanceOf(AssetContentStorage::class.java)
                assertThat(Files.exists(absent)).isFalse()
            }
    }

    @Test
    fun `unknown provider and invalid asset configuration fail startup`() {
        runner.withPropertyValues("storage.provider=typo").run { assertThat(it).hasFailed() }
        listOf(
            "",
            "file:///tmp",
            "http://user:password@localhost",
            "http://localhost?key=secret",
            "http://localhost/path",
            "http://localhost:65536",
            "http://localhost:0",
        ).forEach { url ->
            assertThatThrownBy {
                AssetHttpTransport(
                    AssetStorageProperties(baseUrl = url),
                )
            }.isInstanceOf(Exception::class.java)
        }
        listOf("../secret", "a/b", "a%2fb", "bad?name", "bad--name").forEach { container ->
            assertThatThrownBy {
                AssetHttpTransport(AssetStorageProperties(baseUrl = "http://localhost", container = container))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        runner.withPropertyValues("storage.provider=asset").run { assertThat(it).hasFailed() }
        runner
            .withPropertyValues(
                "storage.provider=asset",
                "storage.asset.base-url=http://localhost",
                "storage.asset.max-content-bytes=0",
            ).run {
                assertThat(it).hasFailed()
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        StorageProperties::class,
        LocalStorageProperties::class,
        AssetStorageProperties::class,
    )
    class Properties

    @Test
    fun `wire size overflow and nonpositive timeouts fail before creating a client`() {
        val valid = AssetStorageProperties(baseUrl = "http://localhost")
        listOf(
            valid.copy(maxContentBytes = Int.MAX_VALUE),
            valid.copy(connectTimeout = Duration.ZERO),
            valid.copy(readTimeout = Duration.ofSeconds(-1)),
        ).forEach { properties ->
            assertThatThrownBy { AssetHttpTransport(properties) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
