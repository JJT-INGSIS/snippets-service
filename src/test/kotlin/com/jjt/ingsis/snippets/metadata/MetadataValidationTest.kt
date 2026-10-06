package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MetadataValidationTest {
    @Test
    fun requiredDetailsRejectBlankValuesBeforeWritingToTheDatabase() {
        val details = creationRequest().details
        assertThrows(IllegalArgumentException::class.java) { details.copy(name = " \t\n") }
        assertThrows(IllegalArgumentException::class.java) { details.copy(language = "") }
        assertThrows(IllegalArgumentException::class.java) { details.copy(version = " ") }
    }

    @Test
    fun creationIdentityAndFingerprintCannotBeBlank() {
        val request = creationRequest()
        assertThrows(IllegalArgumentException::class.java) { request.copy(actorId = " ") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(fingerprint = "") }
    }

    @Test
    fun optionalDescriptionAndUnrecognizedLanguagesAreNotLanguageValidation() {
        val details =
            SnippetDetails(name = " Example ", description = null, language = "future-language", version = "x")
        assertEquals(" Example ", details.name)
        assertNull(details.description)
        assertEquals("", details.copy(description = "").description)
    }
}
