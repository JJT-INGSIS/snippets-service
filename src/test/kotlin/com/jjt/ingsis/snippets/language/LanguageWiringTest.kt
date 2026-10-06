package com.jjt.ingsis.snippets.language

import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:language;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "language.printscript.base-url=http://127.0.0.1:1",
        "language.printscript.connect-timeout=300ms",
        "language.printscript.read-timeout=400ms",
    ],
)
class LanguageWiringTest(
    @Autowired private val language: Language,
    @Autowired private val properties: PrintScriptProperties,
) {
    @Test
    fun `reads the address and the time limits of the PrintScript service from the configuration`() {
        Assertions.assertThat(properties.baseUrl).isEqualTo("http://127.0.0.1:1")
        Assertions.assertThat(properties.connectTimeout).isEqualTo(Duration.ofMillis(300))
        Assertions.assertThat(properties.readTimeout).isEqualTo(Duration.ofMillis(400))
    }

    @Test
    fun `knows PrintScript and reports a failure, not invalid code, while its service is down`() {
        Assertions
            .assertThat(language.validate("printscript", "1.0", "println(1);"))
            .isInstanceOf(ValidationResult.Failed::class.java)
    }

    @Test
    fun `rejects a language that nobody registered`() {
        Assertions
            .assertThat(language.validate("python", "3.12", "print(1)"))
            .isEqualTo(ValidationResult.UnsupportedLanguage("python"))
    }
}
