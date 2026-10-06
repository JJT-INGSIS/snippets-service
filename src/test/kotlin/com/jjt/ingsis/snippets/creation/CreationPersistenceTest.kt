package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class CreationPersistenceTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
    ) {
        @Test
        fun `compatible retries preserve UUID and incompatible requests conflict`() {
            val key = UUID.randomUUID().toString()
            val prepare = preparation()
            val first =
                assertInstanceOf(PreparationResult.Prepared::class.java, prepare.prepare(draft(), key, ActorContext()))
            assertEquals(SnippetState.PENDING, first.metadata.state)
            assertEquals(first.copy(replayed = true), prepare.prepare(draft(), key, ActorContext()))
            assertEquals(
                PreparationResult.Conflict,
                prepare.prepare(draft().copy(code = "println(2);"), key, ActorContext()),
            )
            assertEquals(PreparationResult.Conflict, preparation("another-actor").prepare(draft(), key, ActorContext()))
            assertNull(store.findConfirmed(first.metadata.id))
            assertEquals(
                PreparedCreationLookup.Found(first.metadata),
                FindPreparedCreation(identified, store).find(key, ActorContext()),
            )
        }

        @Test
        fun `null and empty descriptions cannot reuse a creation key`() {
            val key = UUID.randomUUID().toString()
            assertInstanceOf(
                PreparationResult.Prepared::class.java,
                preparation().prepare(draft(), key, ActorContext()),
            )
            assertEquals(
                PreparationResult.Conflict,
                preparation().prepare(draft().copy(description = ""), key, ActorContext()),
            )
        }

        @Test
        fun `concurrent preparation reserves one UUID and remains pending`() {
            val key = UUID.randomUUID().toString()
            val ready = CountDownLatch(4)
            val start = CountDownLatch(1)
            val prepare = preparation()
            val results =
                Executors.newFixedThreadPool(4).use { executor ->
                    val futures =
                        List(4) {
                            executor.submit(
                                Callable {
                                    ready.countDown()
                                    check(start.await(10, TimeUnit.SECONDS))
                                    prepare.prepare(draft(), key, ActorContext())
                                },
                            )
                        }
                    check(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    futures.map {
                        assertInstanceOf(
                            PreparationResult.Prepared::class.java,
                            it.get(30, TimeUnit.SECONDS),
                        )
                    }
                }
            assertEquals(1, results.map { it.metadata.id }.distinct().size)
            assertEquals(1, results.count { !it.replayed })
            assertEquals(3, results.count { it.replayed })
            assertNull(store.findConfirmed(results.first().metadata.id))
        }

        private fun preparation(actor: String = "dev-thiago"): PrepareCreation =
            PrepareCreation(
                identityResolver = ActorIdentityResolver { ActorIdentity.Identified(actor) },
                language = language(ValidationResult.Valid),
                metadata = store,
                fingerprint = CreationFingerprint(),
            )
    }
