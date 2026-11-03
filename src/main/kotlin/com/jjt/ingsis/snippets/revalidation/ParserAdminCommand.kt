package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.SnippetsServiceApplication
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.JobProgressStore
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.sun.security.auth.module.UnixSystem
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.scheduling.config.TaskManagementConfigUtils
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

object ParserAdminCommand {
    private val logger = LoggerFactory.getLogger(ParserAdminCommand::class.java)

    fun run(arguments: List<String>) {
        val administrator = authenticate()
        val command = ParserAdminArguments.parse(arguments)
        val app = SpringApplication(SnippetsServiceApplication::class.java)
        app.setWebApplicationType(WebApplicationType.NONE)
        app.addInitializers({ context ->
            context.addBeanFactoryPostProcessor { factory ->
                val registry = factory as BeanDefinitionRegistry
                val name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
                if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name)
            }
        })
        app
            .run(
                "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false",
                "--snippets.worker.enabled=false",
                "--snippets.outbox.enabled=false",
                "--snippets.capacity.enabled=false",
            ).use { context ->
                val result: Any =
                    when (command) {
                        is ParserAdminArguments.Activate -> {
                            context.getBean(ActivateParser::class.java).activate(command.request, administrator)
                        }

                        is ParserAdminArguments.Status -> {
                            val jobs = context.getBean(JobProgressStore::class.java)
                            check(
                                jobs.find(command.jobId)?.event?.eventType == EventType.ParserVersionActivated,
                            ) { "Parser job not found" }
                            checkNotNull(jobs.progress(command.jobId))
                        }
                    }
                logger.info(
                    "Parser administration outcome={} result={}",
                    result.javaClass.simpleName,
                    JsonMapper
                        .builder()
                        .findAndAddModules()
                        .build()
                        .writeValueAsString(result),
                )
                if (result is ParserActivationResult && result !is ParserActivationResult.Confirmed) {
                    error("Parser reference activation was not confirmed")
                }
            }
    }

    internal fun authenticate(): ParserAdministrator {
        check(System.getProperty("os.name").lowercase().contains("linux")) {
            "Parser administration requires authorized Linux OS access"
        }
        val unix = UnixSystem()
        check(unix.uid == 0L) { "Parser administration requires root through authorized sudo/container access" }
        return ParserAdministrator("os:${unix.username}:uid=${unix.uid}")
    }
}

sealed interface ParserAdminArguments {
    data class Activate(
        val request: ParserActivationRequest,
    ) : ParserAdminArguments

    data class Status(
        val jobId: UUID,
    ) : ParserAdminArguments

    companion object {
        fun parse(arguments: List<String>): ParserAdminArguments {
            require(arguments.isNotEmpty()) { "Expected activate or status" }
            val rest = arguments.drop(1)
            require(rest.size % 2 == 0) { "Expected named argument/value pairs" }
            val pairs = rest.chunked(2)
            require(pairs.map { it[0] }.distinct().size == pairs.size) { "Duplicate argument" }
            val values = pairs.associate { it[0] to it[1] }
            return when (arguments.first()) {
                "activate" -> {
                    require(values.keys == setOf("--request-id", "--language", "--version", "--processor", "--reason"))
                    Activate(
                        ParserActivationRequest(
                            requestId = UUID.fromString(values.getValue("--request-id")),
                            scope = ParserScope(values.getValue("--language"), values.getValue("--version")),
                            processor = ProcessorRevision(values.getValue("--processor")),
                            reason = values.getValue("--reason"),
                        ),
                    )
                }

                "status" -> {
                    require(values.keys == setOf("--job-id"))
                    Status(UUID.fromString(values.getValue("--job-id")))
                }

                else -> {
                    error("Expected activate or status")
                }
            }
        }
    }
}
