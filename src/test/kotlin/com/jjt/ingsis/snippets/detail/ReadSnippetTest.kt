package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private val id = UUID.randomUUID()
private val first = ContentReference("first")
private val second = ContentReference("second")
private val original = metadata("Original")
private val owned = SnippetAccess(id, "dev-juan", AccessRelation.OWNED)
private val identified = ActorIdentityResolver { ActorIdentity.Identified("dev-juan") }
private val printScript =
    Language(
        listOf(
            object : LanguageValidator {
                override val language = "printscript"
                override val extension = "prs"

                override fun validate(
                    version: String,
                    code: String,
                ): ValidationResult = ValidationResult.Valid
            },
        ),
    )

private fun metadata(
    name: String,
    state: SnippetState = SnippetState.CONFIRMED,
): SnippetMetadata =
    SnippetMetadata(
        id = id,
        name = name,
        description = null,
        language = "printscript",
        version = "1.0",
        state = state,
    )

private class ScriptedLinks(
    vararg answers: LinkedSnippet?,
) : SnippetContentLinks {
    private val next = answers.iterator()

    override fun findLinked(id: UUID): LinkedSnippet? = next.next()

    override fun findContent(id: UUID): ContentReference? = error("Not part of reading a detail")

    override fun linkContent(
        id: UUID,
        reference: ContentReference,
    ): ContentLinkResult = error("Reading never links content")

    override fun replaceContent(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ): ContentReplacementResult = error("Reading never replaces content")
}

private class ScriptedStorage(
    private val contents: Map<ContentReference, ReadResult>,
) : SnippetContentStorage {
    override fun read(reference: ContentReference): ReadResult =
        contents[reference] ?: error("The storage must not be read for $reference")

    override fun store(code: String): StoreResult = error("Reading never stores content")

    override fun delete(reference: ContentReference): DeleteResult = error("Reading never deletes content")
}

private fun reader(
    links: SnippetContentLinks,
    storage: SnippetContentStorage = ScriptedStorage(emptyMap()),
    access: AccessChecker = AccessChecker { _, _ -> AccessLookup.Granted(owned) },
    identity: ActorIdentityResolver = identified,
): ReadSnippet =
    ReadSnippet(
        identityResolver = identity,
        links = links,
        accessChecker = access,
        content = ContentReading(links, storage),
        language = printScript,
    )

private fun found(
    metadata: SnippetMetadata,
    access: SnippetAccess,
    code: String,
): DetailOutcome = DetailOutcome.Found(SnippetDetail(ListedSnippet(metadata, access, "prs"), code))

class ReadSnippetTest {
    @Test
    fun `the owner reads the code with every capability`() {
        val storage = ScriptedStorage(mapOf(first to ReadResult.Found("println(1);")))

        val outcome = reader(ScriptedLinks(LinkedSnippet(original, first)), storage).read(id.toString(), ActorContext())

        assertThat(outcome).isEqualTo(found(original, owned, "println(1);"))
        assertThat((outcome as DetailOutcome.Found).detail.capabilities).isEqualTo(Capabilities(true, true))
    }

    @Test
    fun `a recipient reads the same code without capabilities`() {
        val shared = SnippetAccess(id, "dev-thiago", AccessRelation.SHARED)
        val storage = ScriptedStorage(mapOf(first to ReadResult.Found("println(1);")))
        val access = AccessChecker { _, _ -> AccessLookup.Granted(shared) }

        val outcome =
            reader(ScriptedLinks(LinkedSnippet(original, first)), storage, access).read(id.toString(), ActorContext())

        assertThat(outcome).isEqualTo(found(original, shared, "println(1);"))
        assertThat((outcome as DetailOutcome.Found).detail.capabilities).isEqualTo(Capabilities(false, false))
    }

    @Test
    fun `without access or owner the storage is never read`() {
        listOf(AccessLookup.Denied, AccessLookup.Missing).forEach { lookup ->
            val outcome =
                reader(ScriptedLinks(LinkedSnippet(original, first)), access = { _, _ -> lookup })
                    .read(id.toString(), ActorContext())

            assertThat(outcome).isEqualTo(DetailOutcome.Missing)
        }
    }

    @Test
    fun `pending or missing snippets are not found and permissions are not asked`() {
        val unasked = AccessChecker { _, _ -> error("Permissions must not be asked") }
        val pending = LinkedSnippet(metadata("Draft", SnippetState.PENDING), first)

        assertThat(reader(ScriptedLinks(pending), access = unasked).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.Missing)
        assertThat(reader(ScriptedLinks(null), access = unasked).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.Missing)
    }

    @Test
    fun `a replacement between reading the reference and the code is read once more coherently`() {
        val renamed = metadata("Renamed")
        val links = ScriptedLinks(LinkedSnippet(original, first), LinkedSnippet(renamed, second))
        val storage = ScriptedStorage(mapOf(first to ReadResult.Missing, second to ReadResult.Found("println(2);")))

        assertThat(reader(links, storage).read(id.toString(), ActorContext()))
            .isEqualTo(found(renamed, owned, "println(2);"))
    }

    @Test
    fun `missing code with an unchanged reference or a second miss is a retryable failure`() {
        val unchanged = ScriptedLinks(LinkedSnippet(original, first), LinkedSnippet(original, first))
        val missingFirst = ScriptedStorage(mapOf(first to ReadResult.Missing))
        assertThat(reader(unchanged, missingFirst).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.StorageUnavailable)

        val replacedTwice = ScriptedLinks(LinkedSnippet(original, first), LinkedSnippet(metadata("Renamed"), second))
        val bothMissing = ScriptedStorage(mapOf(first to ReadResult.Missing, second to ReadResult.Missing))
        assertThat(reader(replacedTwice, bothMissing).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.StorageUnavailable)
    }

    @Test
    fun `storage failures are never an empty code`() {
        val unavailable = ScriptedStorage(mapOf(first to StorageUnavailable))
        assertThat(
            reader(ScriptedLinks(LinkedSnippet(original, first)), unavailable).read(id.toString(), ActorContext()),
        ).isEqualTo(DetailOutcome.StorageUnavailable)

        val invalid = ScriptedStorage(mapOf(first to InvalidContentReference))
        assertThat(reader(ScriptedLinks(LinkedSnippet(original, first)), invalid).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.ContentBroken)
        assertThat(reader(ScriptedLinks(LinkedSnippet(original, null))).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.ContentBroken)
    }

    @Test
    fun `identity id permissions and metadata failures have their own outcomes`() {
        val noIdentity = ActorIdentityResolver { ActorIdentity.Missing }
        assertThat(reader(ScriptedLinks(), identity = noIdentity).read(id.toString(), ActorContext()))
            .isEqualTo(DetailOutcome.IdentityRejected)
        assertThat(reader(ScriptedLinks()).read("1-1-1-1-1", ActorContext()))
            .isEqualTo(DetailOutcome.InvalidId)
        assertThat(reader(ScriptedLinks()).read("not-a-uuid", ActorContext())).isEqualTo(DetailOutcome.InvalidId)

        val failed = AccessChecker { _, _ -> AccessLookup.Failed(PermissionsFailure.UNAVAILABLE) }
        assertThat(
            reader(ScriptedLinks(LinkedSnippet(original, first)), access = failed).read(id.toString(), ActorContext()),
        ).isEqualTo(DetailOutcome.PermissionsUnavailable)

        val broken =
            object : SnippetContentLinks by ScriptedLinks() {
                override fun findLinked(id: UUID): LinkedSnippet? =
                    throw MetadataPersistenceException(IllegalStateException("down"))
            }
        assertThat(reader(broken).read(id.toString(), ActorContext())).isEqualTo(DetailOutcome.MetadataUnavailable)
    }
}
