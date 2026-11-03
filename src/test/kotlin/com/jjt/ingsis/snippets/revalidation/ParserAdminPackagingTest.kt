package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.SnippetsServiceApplication
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.JobProgressStore
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.StreamDelivery
import com.jjt.ingsis.snippets.background.StreamInbox
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.reformatting.SnippetSeeds
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Linux test JVM receives only a read-only jar and isolated fixture configuration, never a Docker socket. */
@EnabledIfEnvironmentVariable(named = "PARSER_ADMIN_TEST_IMAGE", matches = ".+")
class ParserAdminPackagingTest {
    @Test
    fun `packaged administration and a real parser batch keep valid and invalid results`() {
        val jar =
            Files.list(Path.of("build/libs")).use { paths ->
                paths
                    .filter { it.toString().endsWith(".jar") && !it.toString().endsWith("-plain.jar") }
                    .toList()
                    .single()
                    .toAbsolutePath()
            }
        assertThat(jar).exists()
        Network.newNetwork().use { network ->
            PostgreSQLContainer("postgres:18.6-bookworm")
                .withNetwork(network)
                .withNetworkAliases("metadata")
                .use { db ->
                    db.start()
                    Flyway
                        .configure()
                        .dataSource(db.jdbcUrl, db.username, db.password)
                        .load()
                        .migrate()
                    withProcessor(network.id) { port ->
                        val fixture = AdminFixture(jar = jar, network = network.id, db = db)
                        val job = verifyAdministration(fixture, processorRevision(port))
                        verifyRealBatch(db, port, job)
                    }
                }
        }
    }

    private fun verifyAdministration(
        fixture: AdminFixture,
        revision: String,
    ): UUID {
        val requestId = UUID.randomUUID()
        val args =
            listOf(
                "activate",
                "--request-id",
                requestId.toString(),
                "--language",
                "printscript",
                "--version",
                "1.1",
                "--processor",
                revision,
                "--reason",
                "isolated provider verification",
            )
        assertThat(fixture.command(args)).contains("outcome=Confirmed")
        val jdbc = fixture.jdbc()
        val job =
            checkNotNull(
                jdbc.queryForObject("SELECT job_id FROM parser_activations WHERE id = ?", UUID::class.java, requestId),
            )
        assertThat(
            jdbc.queryForObject(
                "SELECT operator_identity FROM parser_activations WHERE id = ?",
                String::class.java,
                requestId,
            ),
        ).isEqualTo("os:root:uid=0")
        assertThat(fixture.command(args)).contains("\"replayed\":true")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs", Int::class.java)).isEqualTo(1)
        assertThat(
            fixture.command(listOf("status", "--job-id", job.toString())),
        ).contains("outcome=JobProgress", job.toString())
        assertThat(jdbc.queryForObject("SELECT enabled FROM execution_capacity", Boolean::class.java)).isFalse()
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL", Int::class.java),
        ).isZero()
        assertThat(fixture.command(args, root = false)).contains("requires root")
        return job
    }

    private fun verifyRealBatch(
        db: PostgreSQLContainer,
        port: String,
        job: UUID,
    ) {
        SpringApplicationBuilder(SnippetsServiceApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.profiles.active=dev",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=${db.jdbcUrl}",
                "--spring.datasource.username=${db.username}",
                "--spring.datasource.password=${db.password}",
                "--language.printscript.base-url=http://127.0.0.1:$port",
                "--snippets.capacity.enabled=true",
                "--storage.local.directory=${Files.createTempDirectory("real-parser-")}",
            ).use { context ->
                val jdbc = context.getBean(JdbcTemplate::class.java)
                val seeds =
                    SnippetSeeds(
                        metadata = context.getBean(SnippetMetadataStore::class.java),
                        links = context.getBean(SnippetContentLinks::class.java),
                        storage = context.getBean(SnippetContentStorage::class.java),
                        jdbc = jdbc,
                    )
                val valid = seeds.confirmed("println(1);", "1.1", "real-owner-a")
                val invalid = seeds.confirmed("let x: number = ;", "1.1", "real-owner-b")
                val consumer = context.getBean(JobConsumer::class.java)
                val jobs = context.getBean(JobProgressStore::class.java)
                val payload =
                    checkNotNull(
                        jdbc.queryForObject(
                            "SELECT envelope::text FROM event_outbox WHERE job_id = ?",
                            String::class.java,
                            job,
                        ),
                    )
                val delivery = StreamDelivery("isolated-parser", "1-0", payload)
                val inbox = RecordingParserInbox()
                repeat(20) {
                    jdbc.update("UPDATE background_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", job)
                    consumer.receive(delivery, inbox)
                }
                assertThat(jobs.find(job)?.state).isEqualTo(JobState.COMPLETED)
                assertThat(inbox.acknowledged).isTrue()
                assertThat(
                    jdbc.queryForObject(
                        "SELECT valid FROM parser_validation_results WHERE snippet_id = ?",
                        Boolean::class.java,
                        valid,
                    ),
                ).isTrue()
                assertThat(
                    jdbc.queryForObject(
                        "SELECT valid FROM parser_validation_results WHERE snippet_id = ?",
                        Boolean::class.java,
                        invalid,
                    ),
                ).isFalse()
                assertInvalidDiagnostics(jdbc, invalid)
            }
    }

    private fun assertInvalidDiagnostics(
        jdbc: JdbcTemplate,
        invalid: UUID,
    ) {
        assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_array_length(diagnostics) FROM parser_validation_results WHERE snippet_id = ?",
                Int::class.java,
                invalid,
            ),
        ).isPositive()
    }

    private fun withProcessor(
        network: String,
        operation: (String) -> Unit,
    ) {
        val name = "sni70-processor-" + UUID.randomUUID()
        try {
            docker(
                listOf(
                    "run",
                    "-d",
                    "--name",
                    name,
                    "--network",
                    network,
                    "--network-alias",
                    "processor",
                    "--memory",
                    "512m",
                    "-p",
                    "127.0.0.1::8080",
                    checkNotNull(System.getenv("PARSER_PRINTSCRIPT_TEST_IMAGE")),
                ),
            )
            val port =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(docker(listOf("inspect", name)))
                    .get(0)
                    .get("NetworkSettings")
                    .get("Ports")
                    .get("8080/tcp")
                    .get(0)
                    .get("HostPort")
                    .asString()
            operation(port)
        } finally {
            docker(listOf("rm", "-f", name), success = null)
        }
    }

    private fun processorRevision(port: String): String {
        val request =
            HttpRequest
                .newBuilder(
                    URI("http://127.0.0.1:$port/processor"),
                ).timeout(Duration.ofSeconds(2))
                .build()
        val client = HttpClient.newHttpClient()
        repeat(100) {
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() !in 400..499) { "The image does not expose the SNI-76 processor contract" }
                if (response.statusCode() == 200) {
                    return JsonMapper
                        .builder()
                        .build()
                        .readTree(
                            response.body(),
                        ).get("capabilities")
                        .get("validate")
                        .asString()
                }
            } catch (_: java.io.IOException) {
                // Bounded readiness polling of this test's own container.
            }
            Thread.sleep(200)
        }
        error("Packaged PrintScript did not become ready")
    }
}

private class AdminFixture(
    private val jar: Path,
    private val network: String,
    private val db: PostgreSQLContainer,
) {
    fun jdbc() = JdbcTemplate(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))

    fun command(
        arguments: List<String>,
        root: Boolean = true,
    ): String {
        val name = "sni70-admin-" + UUID.randomUUID()
        val args =
            listOf(
                "run",
                "--rm",
                "--name",
                name,
                "--network",
                network,
                "--memory",
                "384m",
                "--user",
                if (root) "0" else "10001",
                "--mount",
                "type=bind,source=$jar,target=/app/app.jar,readonly",
            ) + environment() +
                listOf(
                    "--entrypoint",
                    "java",
                    checkNotNull(System.getenv("PARSER_ADMIN_TEST_IMAGE")),
                    "-Xmx192m",
                    "-jar",
                    "/app/app.jar",
                    "parser-admin",
                ) + arguments
        try {
            return docker(args, success = root)
        } finally {
            docker(listOf("rm", "-f", name), success = null)
        }
    }

    private fun environment(): List<String> =
        listOf(
            "-e",
            "SPRING_PROFILES_ACTIVE=dev",
            "-e",
            "SPRING_DATASOURCE_URL=jdbc:postgresql://metadata:5432/${db.databaseName}",
            "-e",
            "SPRING_DATASOURCE_USERNAME=${db.username}",
            "-e",
            "SPRING_DATASOURCE_PASSWORD=${db.password}",
            "-e",
            "LANGUAGE_PRINTSCRIPT_BASE_URL=http://processor:8080",
            "-e",
            "STORAGE_LOCAL_DIRECTORY=/tmp/content",
        )
}

private class RecordingParserInbox : StreamInbox {
    var acknowledged = false

    override fun initialize() = Unit

    override fun fresh() = emptyList<StreamDelivery>()

    override fun recover() = emptyList<StreamDelivery>()

    override fun acknowledge(delivery: StreamDelivery) {
        acknowledged = true
    }
}

private fun docker(
    arguments: List<String>,
    success: Boolean? = true,
): String {
    val process = ProcessBuilder(listOf("docker") + arguments).redirectErrorStream(true).start()
    val bytes = ByteArrayOutputStream()
    val reader = Thread.ofVirtual().start { process.inputStream.use { it.copyTo(bytes) } }
    val completed = process.waitFor(90, TimeUnit.SECONDS)
    if (!completed) process.destroyForcibly()
    reader.join(5000)
    check(completed && !reader.isAlive) { "Isolated Docker command did not terminate" }
    val output = bytes.toString(Charsets.UTF_8)
    if (success != null) assertThat(process.exitValue() == 0).describedAs(output).isEqualTo(success)
    return output
}
