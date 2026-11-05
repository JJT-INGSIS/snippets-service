package com.jjt.ingsis.snippets.metadata

import org.springframework.jdbc.core.PreparedStatementSetter
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private const val IDS_INDEX = 3
private const val NAME_INDEX = 4
private const val LANGUAGE_INDEX = 6
private const val LINT_INDEX = 8

internal object LintQueryParameters {
    private val json = JsonMapper.builder().build()

    fun of(
        ids: Set<UUID>,
        filter: SnippetFilter,
        includeLintFilter: Boolean = true,
    ): PreparedStatementSetter =
        PreparedStatementSetter { statement ->
            val processors =
                filter.lintBasis?.processors.orEmpty().entries.groupBy { it.key.language }.mapValues { (_, entries) ->
                    entries.associate {
                        it.key.version to
                            mapOf("processor" to it.value.value, "defaults" to filter.lintBasis?.defaults?.get(it.key))
                    }
                }
            statement.setString(1, json.writeValueAsString(processors))
            statement.setString(
                2,
                json.writeValueAsString(
                    filter.lintBasis
                        ?.owners
                        .orEmpty()
                        .mapKeys { it.key.toString() },
                ),
            )
            statement.setArray(IDS_INDEX, statement.connection.createArrayOf("uuid", ids.toTypedArray()))
            statement.setString(NAME_INDEX, filter.name)
            statement.setString(NAME_INDEX + 1, filter.name)
            statement.setString(LANGUAGE_INDEX, filter.language)
            statement.setString(LANGUAGE_INDEX + 1, filter.language)
            if (includeLintFilter) {
                statement.setString(LINT_INDEX, filter.lintStatus?.value)
                statement.setString(LINT_INDEX + 1, filter.lintStatus?.value)
            }
        }
}
