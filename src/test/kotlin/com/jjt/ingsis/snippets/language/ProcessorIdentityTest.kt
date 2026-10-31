package com.jjt.ingsis.snippets.language

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProcessorIdentityTest {
    private val first = ProcessorRevision("sha256-v1:" + "a".repeat(64))
    private val second = ProcessorRevision("sha256-v1:" + "b".repeat(64))

    @Test
    fun `a header is verified only when it has the revision format`() {
        assertThat(ProcessorIdentity.of(first.value)).isEqualTo(ProcessorIdentity.Verified(first))
        assertThat(ProcessorIdentity.of(null)).isEqualTo(ProcessorIdentity.Missing)
        assertThat(ProcessorIdentity.of("1.1")).isEqualTo(ProcessorIdentity.Malformed("1.1"))
        assertThat(
            ProcessorIdentity.of("sha256-v1:" + "A".repeat(64)),
        ).isInstanceOf(ProcessorIdentity.Malformed::class.java)
    }

    @Test
    fun `only the expected verified revision accredits a result`() {
        assertThat(ProcessorIdentity.Verified(first).checkAgainst(first)).isEqualTo(ProcessorCheck.ACCREDITED)
        assertThat(ProcessorIdentity.Verified(second).checkAgainst(first)).isEqualTo(ProcessorCheck.MISMATCH)
        assertThat(ProcessorIdentity.Missing.checkAgainst(first)).isEqualTo(ProcessorCheck.UNVERIFIABLE)
        assertThat(ProcessorIdentity.Malformed("1.1").checkAgainst(first)).isEqualTo(ProcessorCheck.UNVERIFIABLE)
    }

    @Test
    fun `a language version is never a processor revision`() {
        assertThatThrownBy { ProcessorRevision("1.1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ProcessorIdentity.Missing.verifiedOrNull()).isNull()
        assertThat(ProcessorIdentity.Verified(first).verifiedOrNull()).isEqualTo(first)
    }
}
