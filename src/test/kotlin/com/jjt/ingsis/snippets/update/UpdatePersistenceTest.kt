package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.creation.identified
import com.jjt.ingsis.snippets.creation.language
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.permissions.ModificationChecker
import com.jjt.ingsis.snippets.permissions.ModificationPermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class UpdatePersistenceTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `prepared and rejected replacements preserve every persisted column`() {
            val request = creationRequest()
            store.prepareCreation(request)
            val pending =
                checkNotNull(
                    store.findCreation(
                        request.key,
                        request.actorId,
                    ) as? CreationLookup.Found,
                )
            val existing = checkNotNull(store.confirmCreation(pending.metadata.id))
            val snapshot = jdbc.queryForMap("SELECT * FROM snippets WHERE id = ?", existing.id)
            val replacement = draft().copy(name = "Renamed", description = "", version = "1.1", code = "println(2);")
            val allowed = preparation(ModificationPermission.Allowed)
            assertEquals(
                UpdatePreparationResult.Prepared(UpdateCandidate(existing.id, replacement)),
                allowed.prepare(replacement, existing.id.toString(), ActorContext()),
            )
            assertEquals(
                UpdatePreparationResult.Denied,
                preparation(ModificationPermission.Denied).prepare(replacement, existing.id.toString(), ActorContext()),
            )
            assertInstanceOf(
                UpdatePreparationResult.Rejected::class.java,
                allowed.prepare(replacement.copy(name = ""), existing.id.toString(), ActorContext()),
            )
            assertEquals(existing, store.findConfirmed(existing.id))
            assertEquals(snapshot, jdbc.queryForMap("SELECT * FROM snippets WHERE id = ?", existing.id))
        }

        @Test
        fun `pending replacements never check permission and remain pending`() {
            val request = creationRequest()
            store.prepareCreation(request)
            val pending =
                checkNotNull(
                    store.findCreation(
                        request.key,
                        request.actorId,
                    ) as? CreationLookup.Found,
                ).metadata
            val prepare =
                PrepareUpdate(
                    identityResolver = identified,
                    metadata = store,
                    permissions = ModificationChecker { _, _ -> error("Pending must not check permissions") },
                    language = language(ValidationResult.Valid),
                )
            assertEquals(
                UpdatePreparationResult.Pending,
                prepare.prepare(draft(), pending.id.toString(), ActorContext()),
            )
            assertEquals(pending, store.findMetadata(pending.id))
        }

        private fun preparation(permission: ModificationPermission): PrepareUpdate =
            PrepareUpdate(
                identityResolver = identified,
                metadata = store,
                permissions = ModificationChecker { _, _ -> permission },
                language = language(ValidationResult.Valid),
            )
    }
