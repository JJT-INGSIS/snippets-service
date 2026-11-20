package com.jjt.ingsis.snippets.background

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class OutboxRedis : GenericContainer<OutboxRedis>(DockerImageName.parse("redis:7.4.7-alpine")) {
    init {
        withExposedPorts(6379)
        // Bounded fault-injection filesystem: tests can fill AOF without filling the host disk.
        withTmpFs(mapOf("/data" to "rw,size=8m"))
        withCommand(
            "redis-server",
            "--dir",
            "/data",
            "--appendonly",
            "yes",
            "--appendfsync",
            "everysec",
            "--maxmemory-policy",
            "noeviction",
        )
    }
}

@TestConfiguration(proxyBeanMethods = false)
class OutboxTestConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun outboxRedis(): OutboxRedis = OutboxRedis()

    @Bean
    fun redisProperties(redis: OutboxRedis): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
}
