package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.CompletedRun
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

private val auditMapper = JsonMapper.builder().findAndAddModules().build()

internal fun writeAuditJson(value: Any): String =
    try {
        auditMapper.writeValueAsString(value)
    } catch (failure: JacksonException) {
        throw MetadataPersistenceException(failure)
    }

internal fun readAuditedRun(row: ResultSet): AuditedRun =
    AuditedRun(
        id = row.getObject("id", UUID::class.java),
        actorId = row.getString("actor_id"),
        snapshot = decodeAudit(row.getString("snapshot"), ExecutionSnapshot::class.java),
        startedAt = row.getTimestamp("started_at").toInstant(),
        remote = row.getString("remote")?.let { decodeAudit(it, ExecutionDescriptor::class.java) },
        completed = row.getString("result")?.let { decodeAudit(it, CompletedRun::class.java) },
        finishedAt = row.getTimestamp("finished_at")?.toInstant(),
        failure = row.getString("failure"),
        reserved = row.getBoolean("reserved"),
    )

private fun <T> decodeAudit(
    value: String,
    type: Class<T>,
): T =
    try {
        auditMapper.readValue(value, type)
    } catch (failure: JacksonException) {
        throw MetadataPersistenceException(failure)
    }
