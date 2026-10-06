package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class MetadataCreationTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun retriesPreserveTheIdentifierAndIdentityConflictsDoNotExposeMetadata() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata

            assertEquals(CreationResult.Existing(created), store.prepareCreation(request))
            assertEquals(CreationResult.Conflict, store.prepareCreation(request.copy(actorId = "dev-thiago")))
            assertEquals(
                CreationResult.Conflict,
                store.prepareCreation(request.copy(fingerprint = "different-request")),
            )
            assertEquals(CreationLookup.IdentityConflict, store.findCreation(request.key, "dev-thiago"))
            assertEquals(CreationLookup.Found(created), store.findCreation(request.key, request.actorId))
            assertEquals(1, count(request.key))
        }

        @Test
        fun concurrentCompatibleRequestsReserveExactlyOneIdentifier() {
            val request = creationRequest()
            val results = race(List(4) { request })
            val created = results.filterIsInstance<CreationResult.Created>().single().metadata

            assertEquals(3, results.count { it == CreationResult.Existing(created) })
            assertEquals(1, count(request.key))
        }

        @Test
        fun concurrentIncompatibleRequestsCannotReplaceTheWinningCreation() {
            val request = creationRequest()
            val requests = List(4) { request.copy(actorId = "actor-$it") }
            val results = race(requests)
            val winner = results.filterIsInstance<CreationResult.Created>().single().metadata

            assertEquals(3, results.count { it == CreationResult.Conflict })
            assertEquals(1, count(request.key))
            val actor =
                jdbc.queryForObject(
                    "SELECT creation_actor_id FROM snippets WHERE creation_key = ?",
                    String::class.java,
                    request.key,
                )
            assertEquals(CreationLookup.Found(winner), store.findCreation(request.key, checkNotNull(actor)))
        }

        private fun race(requests: List<CreationRequest>): List<CreationResult> =
            Executors.newFixedThreadPool(requests.size).use { executor ->
                val ready = CountDownLatch(requests.size)
                val start = CountDownLatch(1)
                val futures =
                    requests.map { request ->
                        executor.submit(
                            Callable {
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS))
                                store.prepareCreation(request)
                            },
                        )
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            }

        private fun count(key: UUID): Int? =
            jdbc.queryForObject("SELECT COUNT(*) FROM snippets WHERE creation_key = ?", Int::class.java, key)
    }
