package com.jjt.ingsis.snippets.creation

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

sealed interface DraftInput {
    data class Accepted(
        val draft: SnippetDraft,
    ) : DraftInput

    data object Rejected : DraftInput
}

@Component
class DraftJsonDecoder {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val fields = setOf("name", "description", "language", "version", "code")
    private val required = setOf("name", "language", "version", "code")

    fun decode(json: String): DraftInput =
        try {
            val node = mapper.readTree(json)
            val description = node?.get("description")
            when {
                node == null || !node.isObject -> {
                    DraftInput.Rejected
                }

                node.propertyNames().any { it !in fields } -> {
                    DraftInput.Rejected
                }

                required.any { node.get(it)?.isString != true } -> {
                    DraftInput.Rejected
                }

                description != null && !description.isNull && !description.isString -> {
                    DraftInput.Rejected
                }

                else -> {
                    val draft =
                        SnippetDraft(
                            name = node.get("name").asString(),
                            description = description?.takeUnless { it.isNull }?.asString(),
                            language = node.get("language").asString(),
                            version = node.get("version").asString(),
                            code = node.get("code").asString(),
                        )
                    if (draft.invalidField() == null) DraftInput.Accepted(draft) else DraftInput.Rejected
                }
            }
        } catch (_: JacksonException) {
            DraftInput.Rejected
        }
}

@Component
class CreationFingerprint {
    private val mapper = JsonMapper.builder().build()

    fun calculate(draft: SnippetDraft): String {
        val bytes =
            mapper.writeValueAsBytes(
                listOf(draft.name, draft.description, draft.language, draft.version, draft.code),
            )
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256-v1:" + HexFormat.of().formatHex(digest)
    }
}
