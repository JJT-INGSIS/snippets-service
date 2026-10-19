package com.jjt.ingsis.snippets.storage.asset

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

internal const val ASSET_IMAGE =
    "ghcr.io/austral-ingsis/snippet-asset-service@" +
        "sha256:eacad54bf95717c5483619ad1fe45a9b4a0012a37b451c37baf13e6f43200656"
internal const val AZURITE_IMAGE =
    "mcr.microsoft.com/azure-storage/azurite:3.35.0@" +
        "sha256:647c63a91102a9d8e8000aab803436e1fc85fbb285e7ce830a82ee5d6661cf37"

internal class AssetContainer(
    image: String,
) : GenericContainer<AssetContainer>(image)

class AssetTestRuntime : AutoCloseable {
    private val network = Network.newNetwork()
    private val blobs =
        AssetContainer(AZURITE_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("azurite")
            .withExposedPorts(10000)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--location", "/data", "--skipApiVersionCheck")
    private val service =
        AssetContainer(ASSET_IMAGE)
            .withNetwork(network)
            .withExposedPorts(8080, 9090)
            .withEnv("AZURE_HOST", "http://azurite")
            .withEnv("NEW_RELIC_AGENT_ENABLED", "false")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(9090))
            .withStartupTimeout(Duration.ofSeconds(120))

    val url: String get() = "http://${service.host}:${service.getMappedPort(8080)}"

    fun start() {
        try {
            blobs.start()
            service.start()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    override fun close() {
        service.stop()
        blobs.stop()
        network.close()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AssetRealTestConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun assetRuntime(): AssetTestRuntime = AssetTestRuntime()

    @Bean
    fun assetProperties(runtime: AssetTestRuntime): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("storage.provider") { "asset" }
            registry.add("storage.asset.base-url", runtime::url)
            registry.add("storage.asset.container") { "snippets-test" }
        }
}
