package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.CreateSnippet
import com.jjt.ingsis.snippets.creation.CreationConfirmation
import com.jjt.ingsis.snippets.creation.CreationContent
import com.jjt.ingsis.snippets.creation.CreationFingerprint
import com.jjt.ingsis.snippets.creation.CreationOutcome
import com.jjt.ingsis.snippets.creation.CreationOwner
import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.creation.PrepareCreation
import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.creation.language
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.CreationResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.permissions.ModificationChecker
import com.jjt.ingsis.snippets.permissions.ModificationPermission
import com.jjt.ingsis.snippets.permissions.Ownership
import com.jjt.ingsis.snippets.permissions.OwnershipRegistrar
import com.jjt.ingsis.snippets.permissions.OwnershipRegistration
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val OWNER = "dev-juan"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class UpdateFlowTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val links: SnippetContentLinks,
    ) {
        private val directory: Path = Files.createTempDirectory("update-flow")
        private val storage: SnippetContentStorage =
            LocalSnippetContentStorage(ContentDirectory.prepare(directory.toString()))
        private val context = ActorContext()
        private val owner = ActorIdentityResolver { ActorIdentity.Identified(OWNER) }
        private val allowed = ModificationChecker { _, _ -> ModificationPermission.Allowed }
        private val connectionLost = MetadataPersistenceException(IllegalStateException("Connection lost"))
        private val replacement =
            draft().copy(name = "Renamed", description = "Second version", version = "1.1", code = "println(2);")

        @Test
        fun `the owner replaces the code and every detail together`() {
            val original = created()

            val outcome = update(updating(), original, replacement)

            val expected = original.copy(name = "Renamed", description = "Second version", version = "1.1")
            assertThat(outcome).isEqualTo(UpdateOutcome.Updated(expected))
            assertThat(store.findConfirmed(original.id)).isEqualTo(expected)
            assertThat(storedCode(original.id)).isEqualTo("println(2);")
            assertThat(storedFiles()).isEqualTo(1)
        }

        @Test
        fun `a null description removes it and an empty one is kept`() {
            val original = created()

            update(updating(), original, replacement)
            update(updating(), original, replacement.copy(description = null))
            val removed = store.findConfirmed(original.id)?.description
            update(updating(), original, replacement.copy(description = ""))

            assertThat(removed).isNull()
            assertThat(store.findConfirmed(original.id)?.description).isEmpty()
        }

        @Test
        fun `an actor who is not the owner changes nothing`() {
            val original = created()
            val denied = ModificationChecker { _, _ -> ModificationPermission.Denied }

            val outcome = update(updating(permissions = denied), original, replacement)

            assertThat(outcome).isEqualTo(UpdateOutcome.Denied)
            assertUnchanged(original)
        }

        @Test
        fun `a Permissions failure is not a denial and changes nothing`() {
            val original = created()
            val unavailable =
                ModificationChecker { _, _ -> ModificationPermission.Failed(PermissionsFailure.UNAVAILABLE) }

            val outcome = update(updating(permissions = unavailable), original, replacement)

            assertThat(outcome)
                .isEqualTo(UpdateOutcome.Rejected(PreparationRejection.TechnicalFailure("permissions")))
            assertUnchanged(original)
        }

        @Test
        fun `an invalid replacement is rejected with its diagnostics and changes nothing`() {
            val original = created()
            val diagnostic = Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1)
            val invalid = ValidationResult.Invalid(listOf(diagnostic))

            val outcome = update(updating(validation = invalid), original, replacement)

            assertThat(outcome)
                .isEqualTo(UpdateOutcome.Rejected(PreparationRejection.InvalidCode(listOf(diagnostic))))
            assertUnchanged(original)
        }

        @Test
        fun `a missing snippet and a pending one are told apart`() {
            val reserved = store.prepareCreation(creationRequest()) as CreationResult.Created

            val missing = updating().update(replacement, UUID.randomUUID().toString(), context)
            val pending = update(updating(), reserved.metadata, replacement)

            assertThat(missing).isEqualTo(UpdateOutcome.Missing)
            assertThat(pending).isEqualTo(UpdateOutcome.Pending)
            assertThat(store.findMetadata(reserved.metadata.id)).isEqualTo(reserved.metadata)
            assertThat(storedFiles()).isZero()
        }

        @Test
        fun `a storage failure changes nothing and the same request works afterwards`() {
            val original = created()
            val unavailable =
                object : SnippetContentStorage by storage {
                    override fun store(code: String): StoreResult = StorageUnavailable
                }

            val failed = update(updating(storage = unavailable), original, replacement)

            assertThat(failed).isEqualTo(UpdateOutcome.Failed(UpdateFailure.STORAGE_UNAVAILABLE))
            assertUnchanged(original)
            assertThat(update(updating(), original, replacement)).isInstanceOf(UpdateOutcome.Updated::class.java)
            assertThat(storedCode(original.id)).isEqualTo("println(2);")
        }

        @Test
        fun `a replacement that fails before committing keeps the previous snippet complete`() {
            val original = created()
            val failing =
                object : SnippetContentLinks by links {
                    override fun replaceContent(
                        id: UUID,
                        details: SnippetDetails,
                        reference: ContentReference,
                    ): ContentReplacementResult = throw connectionLost
                }

            val failed = update(updating(links = failing), original, replacement)

            assertThat(failed).isEqualTo(UpdateOutcome.Failed(UpdateFailure.METADATA_UNAVAILABLE))
            assertThat(store.findConfirmed(original.id)).isEqualTo(original)
            assertThat(storedCode(original.id)).isEqualTo(draft().code)
            assertThat(storedFiles()).isEqualTo(2)
        }

        @Test
        fun `an uncertain replacement leaves the new snippet complete and can be repeated`() {
            val original = created()
            val committedThenLost =
                object : SnippetContentLinks by links {
                    override fun replaceContent(
                        id: UUID,
                        details: SnippetDetails,
                        reference: ContentReference,
                    ): ContentReplacementResult {
                        links.replaceContent(id, details, reference)
                        throw connectionLost
                    }
                }

            val failed = update(updating(links = committedThenLost), original, replacement)

            assertThat(failed).isEqualTo(UpdateOutcome.Failed(UpdateFailure.METADATA_UNAVAILABLE))
            assertThat(store.findConfirmed(original.id)?.name).isEqualTo("Renamed")
            assertThat(storedCode(original.id)).isEqualTo("println(2);")
            assertThat(update(updating(), original, replacement)).isInstanceOf(UpdateOutcome.Updated::class.java)
            assertThat(storedCode(original.id)).isEqualTo("println(2);")
        }

        @Test
        fun `the update succeeds even if the previous code cannot be deleted`() {
            val original = created()
            val undeletable =
                object : SnippetContentStorage by storage {
                    override fun delete(reference: ContentReference): DeleteResult = StorageUnavailable
                }

            val outcome = update(updating(storage = undeletable), original, replacement)

            assertThat(outcome).isInstanceOf(UpdateOutcome.Updated::class.java)
            assertThat(storedCode(original.id)).isEqualTo("println(2);")
            assertThat(storedFiles()).isEqualTo(2)
        }

        @Test
        fun `concurrent updates leave the details and the code of one same request`() {
            val original = created()
            val requests = 6
            val ready = CountDownLatch(requests)
            val start = CountDownLatch(1)
            val updating = updating()

            val outcomes =
                Executors.newFixedThreadPool(requests).use { executor ->
                    val futures =
                        List(requests) { number ->
                            executor.submit(
                                Callable {
                                    ready.countDown()
                                    check(start.await(10, TimeUnit.SECONDS))
                                    val numbered = draft().copy(name = "v$number", code = "println($number);")
                                    update(updating, original, numbered)
                                },
                            )
                        }
                    check(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    futures.map { it.get(30, TimeUnit.SECONDS) }
                }

            val winner = checkNotNull(store.findConfirmed(original.id)).name.removePrefix("v")
            assertThat(outcomes).allMatch { outcome -> outcome is UpdateOutcome.Updated }
            assertThat(storedCode(original.id)).isEqualTo("println($winner);")
            assertThat(storedFiles()).isEqualTo(1)
        }

        private fun updating(
            storage: SnippetContentStorage = this.storage,
            links: SnippetContentLinks = this.links,
            permissions: ModificationChecker = allowed,
            validation: ValidationResult = ValidationResult.Valid,
        ): UpdateSnippet =
            UpdateSnippet(
                preparation =
                    PrepareUpdate(
                        identityResolver = owner,
                        metadata = store,
                        permissions = permissions,
                        language = language(validation),
                    ),
                replacement = SnippetReplacement(storage, links),
            )

        private fun update(
            updating: UpdateSnippet,
            snippet: SnippetMetadata,
            replacement: SnippetDraft,
        ): UpdateOutcome = updating.update(replacement, snippet.id.toString(), context)

        private fun created(): SnippetMetadata {
            val registered =
                OwnershipRegistrar { snippetId, ownerId ->
                    OwnershipRegistration.Registered(Ownership(snippetId, ownerId), created = true)
                }
            val creation =
                CreateSnippet(
                    preparation =
                        PrepareCreation(
                            identityResolver = owner,
                            language = language(ValidationResult.Valid),
                            metadata = store,
                            fingerprint = CreationFingerprint(),
                        ),
                    content = CreationContent(storage, links),
                    owner = CreationOwner(registered),
                    confirmation = CreationConfirmation(store),
                )
            val created = creation.create(draft(), UUID.randomUUID().toString(), context) as CreationOutcome.Created

            return created.snippet
        }

        private fun assertUnchanged(original: SnippetMetadata) {
            assertThat(store.findConfirmed(original.id)).isEqualTo(original)
            assertThat(storedCode(original.id)).isEqualTo(draft().code)
            assertThat(storedFiles()).isEqualTo(1)
        }

        private fun storedCode(snippetId: UUID): String? {
            val reference = links.findContent(snippetId) ?: return null

            return (storage.read(reference) as? ReadResult.Found)?.code
        }

        private fun storedFiles(): Int = Files.list(directory).use { files -> files.count().toInt() }
    }
