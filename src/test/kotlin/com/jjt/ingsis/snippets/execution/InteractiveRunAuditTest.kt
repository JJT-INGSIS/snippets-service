package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@Import(MetadataTestConfiguration::class)
class InteractiveRunAuditTest
    @Autowired
    constructor(
        private val audit: ExecutionAudit,
        private val jdbc: JdbcTemplate,
        private val postgres: PostgreSQLContainer,
    ) {
        private val json = JsonMapper.builder().findAndAddModules().build()

        @Test
        fun `an interactive run is saved without a test and with its mode`() {
            val run = savedRun(interactiveSnapshot())

            assertThat(audit.reserve(run)).isTrue()
            assertThat(audit.find(run.id)).isEqualTo(run)
            assertThat(jdbc.queryForMap("SELECT mode, test_id FROM snippet_execution_audit WHERE id = ?", run.id))
                .containsEntry("mode", "INTERACTIVE")
                .containsEntry("test_id", null)
        }

        @Test
        fun `an actor has one active run whatever its mode`() {
            val interactive = savedRun(interactiveSnapshot())
            val test = savedRun(snapshot()).copy(actorId = interactive.actorId)

            assertThat(audit.reserve(interactive)).isTrue()
            assertThat(audit.reserve(test)).isFalse()
        }

        @Test
        fun `the database refuses a mode that does not match the test`() {
            val run = savedRun(snapshot())
            audit.reserve(run)

            assertThatThrownBy {
                jdbc.update("UPDATE snippet_execution_audit SET mode = 'INTERACTIVE' WHERE id = ?", run.id)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `the upgrade keeps the test runs saved before interactive runs existed`() {
            val schema = "audit_${UUID.randomUUID().toString().replace("-", "")}"
            val snapshot = snapshot()
            val id = UUID.randomUUID()

            migrate(schema, target = "4")
            saveSnippet(schema, snapshot)
            jdbc.update(
                "INSERT INTO $schema.snippet_execution_audit " +
                    "(id, actor_id, snippet_id, test_id, snapshot, started_at) " +
                    "VALUES (?, 'owner', ?, ?, CAST(? AS jsonb), now())",
                id,
                snapshot.snippetId,
                checkNotNull(snapshot.test).id,
                previousShape(snapshot),
            )
            migrate(schema, target = "latest")

            val row =
                jdbc.queryForMap(
                    "SELECT mode, snapshot::text AS snapshot FROM $schema.snippet_execution_audit WHERE id = ?",
                    id,
                )
            assertThat(row["mode"]).isEqualTo("TEST")
            assertThat(json.readValue(row["snapshot"] as String, ExecutionSnapshot::class.java)).isEqualTo(snapshot)
        }

        private fun savedRun(snapshot: ExecutionSnapshot): AuditedRun {
            saveSnippet("public", snapshot)
            return AuditedRun(
                id = UUID.randomUUID(),
                actorId = "actor-${UUID.randomUUID()}",
                snapshot = snapshot,
                startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
            )
        }

        private fun saveSnippet(
            schema: String,
            snapshot: ExecutionSnapshot,
        ) {
            jdbc.update(
                "INSERT INTO $schema.snippets (id, name, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) " +
                    "VALUES (?, 'audit', 'printscript', '1.1', 'CONFIRMED', ?, 'owner', 'f')",
                snapshot.snippetId,
                UUID.randomUUID(),
            )
            snapshot.test?.let { test ->
                jdbc.update(
                    "INSERT INTO $schema.snippet_test_cases (id, snippet_id, inputs, expected_outputs) " +
                        "VALUES (?, ?, '[]', '[]')",
                    test.id,
                    snapshot.snippetId,
                )
            }
        }

        private fun previousShape(snapshot: ExecutionSnapshot): String {
            val test = checkNotNull(snapshot.test)
            return json.writeValueAsString(
                mapOf(
                    "snippetId" to snapshot.snippetId,
                    "testId" to test.id,
                    "language" to snapshot.language,
                    "version" to snapshot.version,
                    "code" to snapshot.code,
                    "contentReference" to snapshot.contentReference,
                    "definition" to test.definition,
                    "codeHash" to snapshot.codeHash,
                    "testHash" to test.hash,
                ),
            )
        }

        private fun migrate(
            schema: String,
            target: String,
        ) {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .target(target)
                .load()
                .migrate()
        }
    }
