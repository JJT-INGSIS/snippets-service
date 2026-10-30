package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.JsonSnapshotFingerprint
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.testcase.TestCase
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ReadExecutionSnapshotTest {
    private val definition = snapshot()
    private val test = checkNotNull(definition.test)
    private val linked =
        LinkedSnippet(
            SnippetMetadata(
                id = definition.snippetId,
                name = "n",
                description = null,
                language = "printscript",
                version = "1.1",
                state = SnippetState.CONFIRMED,
            ),
            ContentReference("original"),
        )
    private val links = mock(SnippetContentLinks::class.java)
    private val storage = mock(SnippetContentStorage::class.java)
    private val tests = mock(TestCaseStore::class.java)

    private fun reader(
        gate: ReadGate =
            ReadGate { _, _ ->
                ReadDecision.Granted(
                    linked,
                    SnippetAccess(
                        linked.metadata.id,
                        "owner",
                        AccessRelation.OWNED,
                    ),
                )
            },
    ) = ReadExecutionSnapshot(
        gate = gate,
        reading = ContentReading(links, storage),
        tests = tests,
        fingerprints = JsonSnapshotFingerprint(),
    )

    @Test
    fun `refuses unauthorized actors and foreign tests before fetching any code`() {
        val denied = reader(ReadGate { _, _ -> ReadDecision.Refused(ReadFailure.SNIPPET_MISSING) })
        assertThat(denied.read(linked.metadata.id.toString(), test.id.toString(), ActorContext()))
            .isEqualTo(RunResult.Refused(RunFailure.SNIPPET_MISSING))
        verifyNoInteractions(tests, storage)
        `when`(
            tests.list(linked.metadata.id),
        ).thenReturn(listOf(TestCase(UUID.randomUUID(), linked.metadata.id, test.definition)))
        assertThat(reader().read(linked.metadata.id.toString(), test.id.toString(), ActorContext()))
            .isEqualTo(RunResult.Refused(RunFailure.TEST_MISSING))
        verifyNoInteractions(storage)
    }

    @Test
    fun `uses a verified new tuple when code or version changes during the blob read`() {
        val updated =
            linked.copy(
                metadata = linked.metadata.copy(version = "1.0"),
                reference = ContentReference("updated"),
            )
        `when`(
            tests.list(linked.metadata.id),
        ).thenReturn(listOf(TestCase(test.id, linked.metadata.id, test.definition)))
        `when`(storage.read(linked.reference!!)).thenReturn(ReadResult.Found("old"))
        `when`(storage.read(updated.reference!!)).thenReturn(ReadResult.Found("new"))
        `when`(links.findLinked(linked.metadata.id)).thenReturn(updated)
        val read =
            reader().read(
                linked.metadata.id.toString(),
                test.id.toString(),
                ActorContext(),
            ) as RunResult.Success
        assertThat(read.value.code).isEqualTo("new")
        assertThat(read.value.version).isEqualTo("1.0")
        assertThat(read.value.contentReference).isEqualTo("updated")
    }

    @Test
    fun `three concurrent changes produce a conflict instead of a mixed snapshot`() {
        val count = AtomicInteger()
        `when`(
            tests.list(linked.metadata.id),
        ).thenReturn(listOf(TestCase(test.id, linked.metadata.id, test.definition)))
        listOf("original", "changed-1", "changed-2").forEach { reference ->
            `when`(storage.read(ContentReference(reference))).thenReturn(ReadResult.Found("body"))
        }
        `when`(links.findLinked(linked.metadata.id)).thenAnswer {
            linked.copy(reference = ContentReference("changed-${count.incrementAndGet()}"))
        }
        assertThat(reader().read(linked.metadata.id.toString(), test.id.toString(), ActorContext()))
            .isEqualTo(RunResult.Refused(RunFailure.SNAPSHOT_CONFLICT))
        assertThat(count.get()).isEqualTo(3)
    }

    @Test
    fun `technical storage failures do not masquerade as concurrent edits`() {
        `when`(
            tests.list(linked.metadata.id),
        ).thenReturn(listOf(TestCase(test.id, linked.metadata.id, test.definition)))
        `when`(storage.read(linked.reference!!)).thenReturn(StorageUnavailable)
        assertThat(reader().read(linked.metadata.id.toString(), test.id.toString(), ActorContext()))
            .isEqualTo(RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE))
    }

    @Test
    fun `an interactive snapshot reads the confirmed code without looking up tests`() {
        `when`(storage.read(ContentReference("original"))).thenReturn(ReadResult.Found("println(1);"))
        `when`(links.findLinked(linked.metadata.id)).thenReturn(linked)

        val read = reader().read(linked.metadata.id.toString(), ActorContext()) as RunResult.Success

        assertThat(read.value.code).isEqualTo("println(1);")
        assertThat(read.value.test).isNull()
        verifyNoInteractions(tests)
    }

    @Test
    fun `an interactive snapshot refuses actors without access before reading any code`() {
        val denied = reader(ReadGate { _, _ -> ReadDecision.Refused(ReadFailure.SNIPPET_MISSING) })

        assertThat(denied.read(linked.metadata.id.toString(), ActorContext()))
            .isEqualTo(RunResult.Refused(RunFailure.SNIPPET_MISSING))
        verifyNoInteractions(tests, storage)
    }
}
