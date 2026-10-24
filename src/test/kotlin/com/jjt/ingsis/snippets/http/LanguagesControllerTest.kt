package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.SupportedLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class LanguagesControllerTest {
    @Test
    fun `mapping preserves the public fields including all explicit versions`() {
        assertThat(LanguageResponse.from(SupportedLanguage("custom", "src", listOf("v1", "v2"))))
            .isEqualTo(LanguageResponse("custom", "src", listOf("v1", "v2")))
    }

    @Test
    fun `unexpected catalog failures use Problem Details rather than an empty successful catalog`() {
        val language = mock(Language::class.java)
        `when`(language.supportedLanguages()).thenThrow(IllegalStateException("technical failure"))
        val mvc =
            MockMvcBuilders
                .standaloneSetup(LanguagesController(language))
                .setControllerAdvice(HttpExceptionHandler())
                .build()

        val response = mvc.perform(get("/languages")).andReturn().response

        assertThat(response.status).isEqualTo(500)
        assertThat(response.contentType).startsWith("application/problem+json")
        assertThat(response.contentAsString).contains("urn:snippets:problem:technical-failure")
    }
}
