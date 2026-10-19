package com.jjt.ingsis.snippets.storage.asset

import com.jjt.ingsis.snippets.creation.CreateSnippet
import com.jjt.ingsis.snippets.creation.CreationConfirmation
import com.jjt.ingsis.snippets.creation.CreationContent
import com.jjt.ingsis.snippets.creation.CreationFingerprint
import com.jjt.ingsis.snippets.creation.CreationOutcome
import com.jjt.ingsis.snippets.creation.CreationOwner
import com.jjt.ingsis.snippets.creation.PrepareCreation
import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.creation.identified
import com.jjt.ingsis.snippets.creation.language
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.CreationResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.permissions.Ownership
import com.jjt.ingsis.snippets.permissions.OwnershipRegistrar
import com.jjt.ingsis.snippets.permissions.OwnershipRegistration
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.storage.local.ContentDirectory
import com.jjt.ingsis.snippets.storage.local.LocalSnippetContentStorage
import com.jjt.ingsis.snippets.update.SnippetReplacement
import com.jjt.ingsis.snippets.update.UpdateCandidate
import com.jjt.ingsis.snippets.update.UpdateFailure
import com.jjt.ingsis.snippets.update.UpdateOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class, AssetRealTestConfiguration::class)
class AssetIntegrationTest
    @Autowired
    constructor(
        private val storage: SnippetContentStorage,
        private val metadata: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val runtime: AssetTestRuntime,
    ) {
        @TempDir lateinit var localDirectory: Path

        @Test
        fun `copying a local UUID to Asset preserves the reference database and local rollback source`() {
            val local = LocalSnippetContentStorage(ContentDirectory.prepare(localDirectory.toString()))
            val code = "  println(1);\r\n🚀 "
            val reference = (local.store(code) as StoreResult.Stored).reference
            val pending = (metadata.prepareCreation(creationRequest()) as CreationResult.Created).metadata
            links.linkContent(pending.id, reference)
            val source = (local.read(reference) as ReadResult.Found).code
            val transport =
                AssetHttpTransport(AssetStorageProperties(baseUrl = runtime.url, container = "snippets-test"))
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Missing)
            val copied =
                transport.exchange(
                    HttpMethod.PUT,
                    reference.value,
                    checkNotNull(AssetEnvelope.encode(source, 1_048_576)),
                ) as AssetExchange.Received
            assertThat(copied.status).isEqualTo(201)
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Found(code))
            assertThat(local.read(reference)).isEqualTo(ReadResult.Found(code))
            assertThat(links.findContent(pending.id)).isEqualTo(reference)
            assertThat(metadata.findMetadata(pending.id)).isEqualTo(pending)
        }

        @Test
        fun `real assets preserve text exactly and deleting twice is safe`() {
            assertThat(storage).isInstanceOf(AssetContentStorage::class.java)
            listOf("  \r\ná🚀\n ", "x".repeat(1_048_576), "\u0000".repeat(1_048_576)).forEach { code ->
                val reference = (storage.store(code) as StoreResult.Stored).reference
                assertThat(storage.read(reference)).isEqualTo(ReadResult.Found(code))
                assertThat(storage.delete(reference)).isEqualTo(DeleteResult.Deleted)
                assertThat(storage.read(reference)).isEqualTo(ReadResult.Missing)
                assertThat(storage.delete(reference)).isEqualTo(DeleteResult.Deleted)
            }
        }

        @Test
        fun `real assets persist empty code using the approved versioned envelope`() {
            val result = storage.store("")
            assertThat(result).isInstanceOf(StoreResult.Stored::class.java)
            val reference = (result as StoreResult.Stored).reference
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Found(""))
        }

        @Test
        fun `creation retries keep the UUID content and single ownership registration`() {
            val calls = AtomicInteger()
            val creator = creator(calls)
            val key = UUID.randomUUID().toString()
            val created = creator.create(draft(), key, ActorContext()) as CreationOutcome.Created
            val reference = checkNotNull(links.findContent(created.snippet.id))
            assertThat(
                creator.create(draft(), key, ActorContext()),
            ).isEqualTo(CreationOutcome.AlreadyCreated(created.snippet))
            assertThat(calls.get()).isEqualTo(1)
            assertThat(links.findContent(created.snippet.id)).isEqualTo(reference)
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Found(draft().code))
            assertThat(metadata.findConfirmed(created.snippet.id)).isEqualTo(created.snippet)
        }

        @Test
        fun `failed metadata commit preserves the prior remote content and details`() {
            val original =
                creator().create(
                    draft(),
                    UUID.randomUUID().toString(),
                    ActorContext(),
                ) as CreationOutcome.Created
            val before = checkNotNull(links.findContent(original.snippet.id))
            val failing =
                object : SnippetContentLinks by links {
                    override fun replaceContent(
                        id: UUID,
                        details: SnippetDetails,
                        reference: ContentReference,
                    ): ContentReplacementResult =
                        throw MetadataPersistenceException(IllegalStateException("Injected commit failure"))
                }
            val result =
                SnippetReplacement(
                    storage,
                    failing,
                ).apply(UpdateCandidate(original.snippet.id, draft().copy(code = "new")))
            assertThat(result).isEqualTo(UpdateOutcome.Failed(UpdateFailure.METADATA_UNAVAILABLE))
            assertThat(metadata.findConfirmed(original.snippet.id)).isEqualTo(original.snippet)
            assertThat(links.findContent(original.snippet.id)).isEqualTo(before)
            assertThat(storage.read(before)).isEqualTo(ReadResult.Found(draft().code))
        }

        @Test
        fun `an uncertain commit never deletes the now referenced remote content`() {
            val original =
                creator().create(
                    draft(),
                    UUID.randomUUID().toString(),
                    ActorContext(),
                ) as CreationOutcome.Created
            val before = checkNotNull(links.findContent(original.snippet.id))
            val uncertain =
                object : SnippetContentLinks by links {
                    override fun replaceContent(
                        id: UUID,
                        details: SnippetDetails,
                        reference: ContentReference,
                    ): ContentReplacementResult {
                        links.replaceContent(id, details, reference)
                        throw MetadataPersistenceException(
                            IllegalStateException("Injected lost commit acknowledgement"),
                        )
                    }
                }
            val candidate = UpdateCandidate(original.snippet.id, draft().copy(name = "new", code = "new"))
            assertThat(
                SnippetReplacement(storage, uncertain).apply(candidate),
            ).isEqualTo(UpdateOutcome.Failed(UpdateFailure.METADATA_UNAVAILABLE))
            val current = checkNotNull(links.findContent(original.snippet.id))
            assertThat(current).isNotEqualTo(before)
            assertThat(storage.read(current)).isEqualTo(ReadResult.Found("new"))
            assertThat(storage.read(before)).isEqualTo(ReadResult.Found(draft().code))
        }

        @Test
        fun `concurrent real replacements leave the code and metadata from one winner`() {
            val original =
                creator().create(
                    draft(),
                    UUID.randomUUID().toString(),
                    ActorContext(),
                ) as CreationOutcome.Created
            val replacement = SnippetReplacement(storage, links)
            val results =
                Executors.newFixedThreadPool(4).use { executor ->
                    val futures =
                        (1..4).map { number ->
                            executor.submit(
                                Callable {
                                    replacement.apply(
                                        UpdateCandidate(
                                            original.snippet.id,
                                            draft().copy(name = "v$number", code = "code$number"),
                                        ),
                                    )
                                },
                            )
                        }
                    futures.map { it.get(30, TimeUnit.SECONDS) }
                }
            assertThat(results).allMatch { it is UpdateOutcome.Updated }
            val winner = checkNotNull(metadata.findConfirmed(original.snippet.id)).name.removePrefix("v")
            val current = checkNotNull(links.findContent(original.snippet.id))
            assertThat(storage.read(current)).isEqualTo(ReadResult.Found("code$winner"))
        }

        private fun creator(calls: AtomicInteger = AtomicInteger()): CreateSnippet =
            CreateSnippet(
                preparation =
                    PrepareCreation(
                        identityResolver = identified,
                        language = language(ValidationResult.Valid),
                        metadata = metadata,
                        fingerprint = CreationFingerprint(),
                    ),
                content = CreationContent(storage, links),
                owner =
                    CreationOwner(
                        OwnershipRegistrar { id, owner ->
                            calls.incrementAndGet()
                            OwnershipRegistration.Registered(Ownership(id, owner), created = true)
                        },
                    ),
                confirmation = CreationConfirmation(metadata),
            )
    }
