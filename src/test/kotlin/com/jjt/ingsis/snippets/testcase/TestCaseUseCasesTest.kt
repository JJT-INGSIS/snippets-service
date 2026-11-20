package com.jjt.ingsis.snippets.testcase

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private val snippetId = UUID.randomUUID()
private val snippet =
    LinkedSnippet(
        SnippetMetadata(
            id = snippetId,
            name = "tested",
            description = null,
            language = "printscript",
            version = "1.0",
            state = SnippetState.CONFIRMED,
        ),
        ContentReference("code"),
    )
private val definition = TestCaseDefinition(listOf("", "a", "a"), listOf("🌎"))
private val stored = TestCase(UUID.randomUUID(), snippetId, definition)

private fun granted(relation: AccessRelation): ReadGate =
    ReadGate { _, _ -> ReadDecision.Granted(snippet, SnippetAccess(snippetId, "dev-juan", relation)) }

private fun refused(failure: ReadFailure): ReadGate = ReadGate { _, _ -> ReadDecision.Refused(failure) }

private class RecordingStore(
    private val creation: TestCaseCreation = TestCaseCreation.Created(stored),
    private val listed: List<TestCase> = listOf(stored),
    private val failing: Boolean = false,
) : TestCaseStore {
    val calls = mutableListOf<String>()

    override fun create(
        snippetId: UUID,
        definition: TestCaseDefinition,
        maximumPerSnippet: Int,
    ): TestCaseCreation {
        calls += "create $snippetId $maximumPerSnippet"
        if (failing) throw MetadataPersistenceException(IllegalStateException("down"))
        return creation
    }

    override fun list(snippetId: UUID): List<TestCase> {
        calls += "list $snippetId"
        if (failing) throw MetadataPersistenceException(IllegalStateException("down"))
        return listed
    }
}

class TestCaseUseCasesTest {
    @Test
    fun `the owner creates with the per snippet limit`() {
        val store = RecordingStore()

        val addition = CreateTestCase(granted(AccessRelation.OWNED), store).create("id", definition, ActorContext())

        assertEquals(TestCaseAddition.Created(stored), addition)
        assertEquals(listOf("create $snippetId ${TestCaseRules.MAXIMUM_PER_SNIPPET}"), store.calls)
    }

    @Test
    fun `a recipient can list but not create`() {
        val store = RecordingStore()

        assertEquals(
            TestCaseAddition.NotOwner,
            CreateTestCase(granted(AccessRelation.SHARED), store).create("id", definition, ActorContext()),
        )
        assertEquals(
            TestCaseListing.Listed(listOf(stored)),
            ListTestCases(granted(AccessRelation.SHARED), store).list("id", ActorContext()),
        )
        assertEquals(listOf("list $snippetId"), store.calls)
    }

    @Test
    fun `an invalid definition is rejected before asking who the actor is`() {
        val tooMany = TestCaseDefinition(List(TestCaseRules.MAXIMUM_ITEMS + 1) { "x" }, emptyList())
        val unasked = ReadGate { _, _ -> error("Access must not be checked for an invalid definition") }

        assertEquals(
            TestCaseAddition.Invalid(DefinitionRejection.TOO_LARGE),
            CreateTestCase(unasked, RecordingStore()).create("id", tooMany, ActorContext()),
        )
    }

    @Test
    fun `refusals reach neither creation nor listing`() {
        ReadFailure.entries.forEach { failure ->
            val store = RecordingStore()

            assertEquals(
                TestCaseAddition.Failed(failure),
                CreateTestCase(refused(failure), store).create("id", definition, ActorContext()),
            )
            assertEquals(
                TestCaseListing.Failed(failure),
                ListTestCases(refused(failure), store).list("id", ActorContext()),
            )
            assertTrue(store.calls.isEmpty())
        }
    }

    @Test
    fun `a full snippet reports the limit`() {
        val full = RecordingStore(creation = TestCaseCreation.LimitReached)

        assertEquals(
            TestCaseAddition.LimitReached,
            CreateTestCase(granted(AccessRelation.OWNED), full).create("id", definition, ActorContext()),
        )
    }

    @Test
    fun `database failures are reported instead of a fake creation or an empty list`() {
        val down = RecordingStore(failing = true)
        val unavailable = ReadFailure.METADATA_UNAVAILABLE

        assertEquals(
            TestCaseAddition.Failed(unavailable),
            CreateTestCase(granted(AccessRelation.OWNED), down).create("id", definition, ActorContext()),
        )
        assertEquals(
            TestCaseListing.Failed(unavailable),
            ListTestCases(granted(AccessRelation.OWNED), down).list("id", ActorContext()),
        )
    }

    @Test
    fun `an empty listing is a real empty list`() {
        assertEquals(
            TestCaseListing.Listed(emptyList()),
            ListTestCases(
                granted(AccessRelation.OWNED),
                RecordingStore(listed = emptyList()),
            ).list("id", ActorContext()),
        )
    }
}
