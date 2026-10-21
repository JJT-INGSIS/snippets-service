package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.Ownership
import com.jjt.ingsis.snippets.permissions.OwnershipRegistrar
import com.jjt.ingsis.snippets.permissions.OwnershipRegistration
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.storage.local.ContentDirectory
import com.jjt.ingsis.snippets.storage.local.LocalSnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val ACTOR = "dev-juan"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class CreationFlowTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val links: SnippetContentLinks,
    ) {
        private val directory: Path = Files.createTempDirectory("creation-flow")
        private val storage: SnippetContentStorage =
            LocalSnippetContentStorage(ContentDirectory.prepare(directory.toString()))
        private val owners: MutableList<Ownership> = CopyOnWriteArrayList()
        private val registered =
            OwnershipRegistrar { snippetId, ownerId ->
                val ownership = Ownership(snippetId, ownerId)
                owners.add(ownership)
                OwnershipRegistration.Registered(ownership, created = true)
            }
        private val context = ActorContext()
        private val connectionLost = MetadataPersistenceException(IllegalStateException("Connection lost"))

        @Test
        fun `a valid request leaves content, confirmed metadata and ownership under one UUID`() {
            val created = create(creation(), newKey())

            val snippet = (created as CreationOutcome.Created).snippet
            assertThat(snippet.state).isEqualTo(SnippetState.CONFIRMED)
            assertThat(store.findConfirmed(snippet.id)).isEqualTo(snippet)
            assertThat(storedCode(snippet.id)).isEqualTo(draft().code)
            assertThat(owners).containsExactly(Ownership(snippet.id, ACTOR))
        }

        @Test
        fun `a compatible retry answers the same snippet without storing or registering again`() {
            val key = newKey()
            val created = create(creation(), key) as CreationOutcome.Created

            val retried = create(creation(), key)

            assertThat(retried).isEqualTo(CreationOutcome.AlreadyCreated(created.snippet))
            assertThat(storedFiles()).isEqualTo(1)
            assertThat(owners).hasSize(1)
        }

        @Test
        fun `the same key with another request or another actor is a conflict`() {
            val key = newKey()
            create(creation(), key)

            val otherCode = creation().create(draft().copy(code = "println(2);"), key, context)
            val otherActor = create(creation(preparation = preparation(actor = "dev-thiago")), key)

            assertThat(otherCode).isEqualTo(CreationOutcome.Conflict)
            assertThat(otherActor).isEqualTo(CreationOutcome.Conflict)
            assertThat(storedFiles()).isEqualTo(1)
            assertThat(owners).hasSize(1)
        }

        @Test
        fun `invalid code is rejected with its diagnostics and reserves nothing`() {
            val key = newKey()
            val diagnostic = Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1)
            val invalid = preparation(validation = ValidationResult.Invalid(listOf(diagnostic)))

            val rejected = create(creation(preparation = invalid), key)

            assertThat(rejected)
                .isEqualTo(CreationOutcome.Rejected(PreparationRejection.InvalidCode(listOf(diagnostic))))
            assertThat(store.findCreation(UUID.fromString(key), ACTOR)).isEqualTo(CreationLookup.Missing)
            assertThat(storedFiles()).isZero()
            assertThat(owners).isEmpty()
        }

        @Test
        fun `a storage failure confirms nothing and a retry completes the same snippet`() {
            val key = newKey()
            val unavailable =
                object : SnippetContentStorage by storage {
                    override fun store(code: String): StoreResult = StorageUnavailable
                }

            val failed = create(creation(storage = unavailable), key)

            assertThat(failed).isEqualTo(CreationOutcome.Incomplete(CreationFailure.STORAGE_UNAVAILABLE))
            val pending = pending(key)
            assertThat(store.findConfirmed(pending.id)).isNull()
            assertThat(owners).isEmpty()
            assertThat(create(creation(), key)).isEqualTo(CreationOutcome.Created(pending.confirmed()))
        }

        @Test
        fun `an uncertain content link keeps the stored code and a retry completes`() {
            val key = newKey()
            val committedThenLost =
                object : SnippetContentLinks by links {
                    override fun linkContent(
                        id: UUID,
                        reference: ContentReference,
                    ): ContentLinkResult {
                        links.linkContent(id, reference)
                        throw connectionLost
                    }
                }

            val failed = create(creation(links = committedThenLost), key)

            assertThat(failed).isEqualTo(CreationOutcome.Incomplete(CreationFailure.METADATA_UNAVAILABLE))
            val pending = pending(key)
            assertThat(storedCode(pending.id)).isEqualTo(draft().code)
            assertThat(owners).isEmpty()
            assertThat(create(creation(), key)).isEqualTo(CreationOutcome.Created(pending.confirmed()))
            assertThat(storedFiles()).isEqualTo(1)
        }

        @Test
        fun `an uncertain ownership registration confirms nothing and a retry completes`() {
            val key = newKey()
            val timedOut =
                OwnershipRegistrar { _, _ ->
                    OwnershipRegistration.Failed(PermissionsFailure.UNAVAILABLE, outcomeUncertain = true)
                }

            val failed = create(creation(registrar = timedOut), key)

            assertThat(failed).isEqualTo(CreationOutcome.Incomplete(CreationFailure.PERMISSIONS_UNAVAILABLE))
            val pending = pending(key)
            val stored = links.findContent(pending.id)
            assertThat(store.findConfirmed(pending.id)).isNull()
            assertThat(storedCode(pending.id)).isEqualTo(draft().code)
            assertThat(create(creation(), key)).isEqualTo(CreationOutcome.Created(pending.confirmed()))
            assertThat(owners).containsExactly(Ownership(pending.id, ACTOR))
            assertThat(links.findContent(pending.id)).isEqualTo(stored)
        }

        @Test
        fun `a snippet owned by someone else is never confirmed`() {
            val key = newKey()
            val taken = OwnershipRegistrar { _, _ -> OwnershipRegistration.Conflict }

            val failed = create(creation(registrar = taken), key)

            assertThat(failed).isEqualTo(CreationOutcome.Incomplete(CreationFailure.OWNER_CONFLICT))
            assertThat(store.findConfirmed(pending(key).id)).isNull()
        }

        @Test
        fun `an uncertain confirmation is not a success and its retry finds the snippet created`() {
            val key = newKey()
            val committedThenLost =
                object : SnippetMetadataStore by store {
                    override fun confirmCreation(id: UUID): SnippetMetadata? {
                        store.confirmCreation(id)
                        throw connectionLost
                    }
                }

            val failed = create(creation(store = committedThenLost), key)
            val retried = create(creation(), key)

            assertThat(failed).isEqualTo(CreationOutcome.Incomplete(CreationFailure.METADATA_UNAVAILABLE))
            val snippet = (retried as CreationOutcome.AlreadyCreated).snippet
            assertThat(snippet.state).isEqualTo(SnippetState.CONFIRMED)
            assertThat(storedCode(snippet.id)).isEqualTo(draft().code)
            assertThat(owners).containsExactly(Ownership(snippet.id, ACTOR))
        }

        @Test
        fun `concurrent compatible requests create one snippet with one content`() {
            val key = newKey()
            val requests = 6
            val ready = CountDownLatch(requests)
            val start = CountDownLatch(1)
            val creation = creation()

            val outcomes =
                Executors.newFixedThreadPool(requests).use { executor ->
                    val futures =
                        List(requests) {
                            executor.submit(
                                Callable {
                                    ready.countDown()
                                    check(start.await(10, TimeUnit.SECONDS))
                                    create(creation, key)
                                },
                            )
                        }
                    check(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    futures.map { it.get(30, TimeUnit.SECONDS) }
                }

            val snippets = outcomes.map { outcome -> snippetOf(outcome) }
            assertThat(snippets.distinct()).hasSize(1)
            assertThat(snippets.first().state).isEqualTo(SnippetState.CONFIRMED)
            assertThat(storedCode(snippets.first().id)).isEqualTo(draft().code)
            assertThat(storedFiles()).isEqualTo(1)
            assertThat(owners.distinct()).containsExactly(Ownership(snippets.first().id, ACTOR))
        }

        private fun creation(
            storage: SnippetContentStorage = this.storage,
            links: SnippetContentLinks = this.links,
            registrar: OwnershipRegistrar = registered,
            store: SnippetMetadataStore = this.store,
            preparation: PrepareCreation = preparation(),
        ): CreateSnippet =
            CreateSnippet(
                preparation = preparation,
                content = CreationContent(storage, links),
                owner = CreationOwner(registrar),
                confirmation = CreationConfirmation(store),
            )

        private fun preparation(
            validation: ValidationResult = ValidationResult.Valid,
            actor: String = ACTOR,
        ): PrepareCreation =
            PrepareCreation(
                identityResolver = ActorIdentityResolver { ActorIdentity.Identified(actor) },
                language = language(validation),
                metadata = store,
                fingerprint = CreationFingerprint(),
            )

        private fun create(
            creation: CreateSnippet,
            key: String,
        ): CreationOutcome = creation.create(draft(), key, context)

        private fun newKey(): String = UUID.randomUUID().toString()

        private fun pending(key: String): SnippetMetadata {
            val found = store.findCreation(UUID.fromString(key), ACTOR) as CreationLookup.Found
            assertThat(found.metadata.state).isEqualTo(SnippetState.PENDING)

            return found.metadata
        }

        private fun SnippetMetadata.confirmed(): SnippetMetadata = copy(state = SnippetState.CONFIRMED)

        private fun snippetOf(outcome: CreationOutcome): SnippetMetadata =
            when (outcome) {
                is CreationOutcome.Created -> {
                    outcome.snippet
                }

                is CreationOutcome.AlreadyCreated -> {
                    outcome.snippet
                }

                is CreationOutcome.Rejected, CreationOutcome.Conflict, is CreationOutcome.Incomplete -> {
                    error("The creation did not succeed: $outcome")
                }
            }

        private fun storedCode(snippetId: UUID): String? {
            val reference = links.findContent(snippetId) ?: return null

            return (storage.read(reference) as? ReadResult.Found)?.code
        }

        private fun storedFiles(): Int = Files.list(directory).use { files -> files.count().toInt() }
    }
