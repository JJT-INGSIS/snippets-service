package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.RejectedDeliveries
import com.jjt.ingsis.snippets.background.StreamDelivery
import com.jjt.ingsis.snippets.background.safeJobReason
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.util.HexFormat

@Repository
class JdbcRejectedDeliveries(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : RejectedDeliveries {
    override fun persist(
        delivery: StreamDelivery,
        reason: String,
    ) = transaction.commit {
        val bytes = delivery.payload.orEmpty().toByteArray(Charsets.UTF_8)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        jdbc.update(
            "INSERT INTO rejected_stream_deliveries (stream, record_id, reason, payload_hash, payload_bytes) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            delivery.stream,
            delivery.id,
            safeJobReason(reason),
            hash,
            bytes.size,
        )
        Unit
    }
}
