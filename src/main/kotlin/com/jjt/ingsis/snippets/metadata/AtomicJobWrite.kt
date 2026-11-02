package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.ConfirmedJobChange
import org.springframework.stereotype.Component

@Component
class AtomicJobWrite(
    private val transaction: MetadataTransaction,
    private val jobs: JdbcJobRecords,
) {
    fun <T : Any> commit(change: () -> ConfirmedJobChange<T>): T =
        transaction.commit {
            val confirmed = change()
            jobs.insert(confirmed.job)
            confirmed.value
        }
}
