package com.jjt.ingsis.snippets.listing

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.MetadataPage
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetOrder
import com.jjt.ingsis.snippets.metadata.SnippetSearch
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.metadata.SortDirection
import com.jjt.ingsis.snippets.metadata.SortField
import com.jjt.ingsis.snippets.permissions.AccessLister
import com.jjt.ingsis.snippets.permissions.AccessListing
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.permissions.RelationFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

private val query =
    ListingQuery(
        relation = RelationFilter.SHARED,
        filter = SnippetFilter(name = "he", language = ""),
        order = SnippetOrder(SortField.NAME, SortDirection.ASC),
        page = PageRequest(1, 2),
    )
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

private fun useCase(
    identity: ActorIdentityResolver,
    lister: AccessLister,
    search: SnippetSearch,
): ListSnippets =
    ListSnippets(identityResolver = identity, accessLister = lister, search = search, language = printScript)

class ListSnippetsTest {
    private val shared = SnippetAccess(UUID.randomUUID(), "dev-thiago", AccessRelation.SHARED)
    private val metadata =
        SnippetMetadata(
            id = shared.snippetId,
            name = "Hello",
            description = null,
            language = "printscript",
            version = "1.0",
            state = SnippetState.CONFIRMED,
        )

    @Test
    fun `the authorized ids and the query reach the search and permissions supply owner and relation`() {
        val lister =
            AccessLister { actor, filter ->
                check(actor == "dev-juan" && filter == RelationFilter.SHARED)
                AccessListing.Listed(listOf(shared))
            }
        val search =
            SnippetSearch { ids, filter, order, page ->
                check(
                    ids == setOf(shared.snippetId) && filter == query.filter && order == query.order &&
                        page == query.page,
                )
                MetadataPage(5, listOf(metadata))
            }

        val outcome = useCase(identified, lister, search).list(query, ActorContext())

        assertEquals(
            ListingOutcome.Listed(SnippetPage(query.page, 5, listOf(ListedSnippet(metadata, shared, "prs")))),
            outcome,
        )
    }

    @Test
    fun `a language without validator has no extension`() {
        val other = metadata.copy(language = "cobol")
        val outcome =
            useCase(
                identified,
                { _, _ -> AccessListing.Listed(listOf(shared)) },
                { _, _, _, _ -> MetadataPage(1, listOf(other)) },
            ).list(query, ActorContext())

        assertEquals(
            ListingOutcome.Listed(SnippetPage(query.page, 1, listOf(ListedSnippet(other, shared, null)))),
            outcome,
        )
    }

    @Test
    fun `without identity nothing is asked to permissions`() {
        val rejected = ActorIdentityResolver { ActorIdentity.Missing }
        val outcome =
            useCase(
                rejected,
                { _, _ -> error("Permissions must not be called") },
                { _, _, _, _ -> error("The search must not run") },
            ).list(query, ActorContext())

        assertEquals(ListingOutcome.IdentityRejected, outcome)
    }

    @Test
    fun `permissions failure is never an empty list`() {
        val outcome =
            useCase(
                identified,
                { _, _ -> AccessListing.Failed(PermissionsFailure.UNAVAILABLE) },
                { _, _, _, _ -> error("The search must not run") },
            ).list(query, ActorContext())

        assertEquals(ListingOutcome.PermissionsUnavailable, outcome)
    }

    @Test
    fun `metadata failure is never an empty list`() {
        val outcome =
            useCase(
                identified,
                { _, _ -> AccessListing.Listed(listOf(shared)) },
                { _, _, _, _ -> throw MetadataPersistenceException(IllegalStateException("down")) },
            ).list(query, ActorContext())

        assertEquals(ListingOutcome.MetadataUnavailable, outcome)
    }
}
