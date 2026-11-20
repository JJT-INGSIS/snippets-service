package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.OutboxPublisher
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.OutboxTestConfiguration
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.background.redis.RedisStreamInbox
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesOutcome
import com.jjt.ingsis.snippets.formattingrules.SaveFormattingRules
import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

private const val LIVE_URL = "PRINTSCRIPT_SERVICE_URL"
private const val MAX_CYCLES = 150
private const val PAUSE_MILLIS = 200L

private val processorJson = JsonMapper.builder().build()

@EnabledIfEnvironmentVariable(named = LIVE_URL, matches = ".+")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["snippets.capacity.enabled=true", "snippets.worker.lease=5s", "execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class, SnippetSeeds::class)
class FormattingReprocessingLiveTest(
    @Autowired private val save: SaveFormattingRules,
    @Autowired private val consumer: JobConsumer,
    @Autowired private val deliveries: JdbcEventDeliveries,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val variants: FormattedVariants,
    @Autowired private val seeds: SnippetSeeds,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val outbox = OutboxSettings()

    private val owner = "dev-" + UUID.randomUUID()

    @Test
    fun `the real PrintScript formats every owned snippet and a lexical error fails alone`() {
        val compact = snippet("if(true){println(1);}")
        val unterminated = snippet("println(\"abc);")
        val job = saveRules()

        assertThat(processUntilFinished(job)).isEqualTo(JobState.PARTIAL)

        val variant = checkNotNull(variants.find(compact))
        assertThat(seeds.code(variant.derived)).isEqualTo("if(true)\n{\n  println(1);\n}")
        assertThat(seeds.code(checkNotNull(seeds.reference(compact)))).isEqualTo("if(true){println(1);}")
        assertThat(variant.basis.processor.value).isEqualTo(advertisedFormatRevision())
        assertThat(variants.find(unterminated)).isNull()
        assertThat(reasonOf(job, unterminated)).isEqualTo("UNFORMATTABLE")
    }

    private fun saveRules(): UUID {
        val rules =
            mapOf(
                "if-brace-below-line" to true,
                "indent-inside-if" to 2,
                "mandatory-line-breaks-inside-if" to true,
            )
        val outcome =
            save.save(language = "printscript", version = "1.1", rules = rules, context = ActorContext(listOf(owner)))
        return checkNotNull((outcome as FormattingRulesOutcome.Shown).view.jobId)
    }

    private fun processUntilFinished(job: UUID): JobState {
        redis.delete(outbox.stream)
        OutboxPublisher(deliveries, RedisEventStream(redis, outbox), outbox).publishBatch()
        val settings = WorkerSettings(reclaimIdle = Duration.ofMillis(1))
        val inbox = RedisStreamInbox(redis = redis, outbox = outbox, settings = settings, consumer = "live")
        repeat(MAX_CYCLES) {
            consumer.cycle(inbox)
            val state = checkNotNull(jobs.find(job)).state
            if (state in finished) return state
            Thread.sleep(PAUSE_MILLIS)
        }
        error("The batch did not finish")
    }

    private fun advertisedFormatRevision(): String {
        val request = HttpRequest.newBuilder(URI("${System.getenv(LIVE_URL)}/processor")).GET().build()
        val body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
        val capabilities = processorJson.readTree(body).get("capabilities")
        return capabilities.get("format").asString()
    }

    private fun reasonOf(
        job: UUID,
        snippet: UUID,
    ): String? =
        jdbc.queryForObject(
            "SELECT last_error FROM background_job_items WHERE job_id = ? AND item_key = ?",
            String::class.java,
            job,
            snippet.toString(),
        )

    private fun snippet(code: String): UUID = seeds.confirmed(code, "1.1", owner).also { ledger.own(owner, it) }

    companion object {
        private val finished = setOf(JobState.COMPLETED, JobState.PARTIAL, JobState.FAILED, JobState.SUPERSEDED)

        private val ledger = AccessLedger()

        private val permissions = ScriptedHttpService { request -> ledger.answer(request) }

        @JvmStatic
        @DynamicPropertySource
        fun dependencies(registry: DynamicPropertyRegistry) {
            registry.add("permissions.base-url") { permissions.baseUrl }
            registry.add("language.printscript.base-url") { System.getenv(LIVE_URL).orEmpty() }
            registry.add("storage.local.directory") { Files.createTempDirectory("reformatting-live").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stopPermissions() {
            permissions.close()
        }
    }
}
