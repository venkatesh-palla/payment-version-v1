package com.venkat.payment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC repository for Transactional Outbox event storage, locking, and dispatching.
 */
@Repository
public class PaymentOutboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final OutboxRowMapper rowMapper = new OutboxRowMapper();

    public PaymentOutboxRepository(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts an outbox event within the calling transaction.
     * Guaranteed deterministic and idempotent by UNIQUE constraint on event_id.
     */
    public void insertOutboxEvent(final UUID paymentId,
                                  final String eventId,
                                  final String eventType,
                                  final String payloadJson) {
        final String sql = """
                INSERT INTO payment_outbox (
                    id, payment_id, event_id, event_type, payload, status,
                    retry_count, next_retry_at, created_at
                ) VALUES (
                    gen_random_uuid(), :paymentId, :eventId, :eventType, CAST(:payload AS JSONB),
                    'PENDING', 0, NOW(), NOW()
                ) ON CONFLICT (event_id) DO NOTHING
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("payload", payloadJson);

        this.jdbcTemplate.update(sql, params);
    }

    /**
     * Claims a batch of pending outbox events using SKIP LOCKED concurrency control.
     */
    public List<OutboxRecord> claimPendingEvents(final int limit, final Duration leaseDuration) {
        final String selectSql = """
                SELECT id, sequence_no, event_id, payment_id, event_type, payload,
                       status, retry_count, next_retry_at, last_error, created_at, processed_at
                FROM payment_outbox
                WHERE status = 'PENDING' AND next_retry_at <= NOW()
                ORDER BY sequence_no ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """;

        final List<OutboxRecord> claimed = this.jdbcTemplate.query(
                selectSql,
                new MapSqlParameterSource("limit", limit),
                this.rowMapper
        );

        if (!claimed.isEmpty()) {
            final List<UUID> ids = claimed.stream().map(OutboxRecord::id).toList();
            final String updateSql = """
                    UPDATE payment_outbox
                    SET next_retry_at = NOW() + :leaseInterval
                    WHERE id IN (:ids)
                    """;
            this.jdbcTemplate.update(
                    updateSql,
                    new MapSqlParameterSource()
                            .addValue("ids", ids)
                            .addValue("leaseInterval", leaseDuration.getSeconds() + " seconds")
            );
        }

        return claimed;
    }

    public void markDelivered(final UUID outboxId) {
        final String sql = """
                UPDATE payment_outbox
                SET status = 'DELIVERED',
                    processed_at = NOW(),
                    last_error = NULL
                WHERE id = :id
                """;

        this.jdbcTemplate.update(sql, new MapSqlParameterSource("id", outboxId));
    }

    public void recordDeliveryFailure(final UUID outboxId,
                                      final int newRetryCount,
                                      final Instant nextRetryAt,
                                      final String errorMessage,
                                      final boolean markFailedPermanently) {
        final String sql = """
                UPDATE payment_outbox
                SET status = :status,
                    retry_count = :retryCount,
                    next_retry_at = :nextRetryAt,
                    last_error = :errorMessage,
                    processed_at = :processedAt
                WHERE id = :id
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", outboxId)
                .addValue("status", markFailedPermanently ? "FAILED" : "PENDING")
                .addValue("retryCount", newRetryCount)
                .addValue("nextRetryAt", Timestamp.from(nextRetryAt))
                .addValue("errorMessage", errorMessage)
                .addValue("processedAt", markFailedPermanently ? Timestamp.from(Instant.now()) : null);

        this.jdbcTemplate.update(sql, params);
    }

    public List<OutboxRecord> findEventsAfter(final long afterSequenceNo, final int limit) {
        final String sql = """
                SELECT id, sequence_no, event_id, payment_id, event_type, payload,
                       status, retry_count, next_retry_at, last_error, created_at, processed_at
                FROM payment_outbox
                WHERE sequence_no > :afterSequenceNo
                ORDER BY sequence_no ASC
                LIMIT :limit
                """;

        return this.jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("afterSequenceNo", afterSequenceNo).addValue("limit", limit),
                this.rowMapper
        );
    }

    public int requeueFailedEvents() {
        final String sql = """
                UPDATE payment_outbox
                SET status = 'PENDING',
                    next_retry_at = NOW(),
                    last_error = NULL
                WHERE status = 'FAILED'
                """;

        return this.jdbcTemplate.update(sql, new MapSqlParameterSource());
    }

    public record OutboxRecord(
            UUID id,
            long sequenceNo,
            String eventId,
            UUID paymentId,
            String eventType,
            String payload,
            String status,
            int retryCount,
            Instant nextRetryAt,
            String lastError,
            Instant createdAt,
            Instant processedAt
    ) {
    }

    private static class OutboxRowMapper implements RowMapper<OutboxRecord> {
        @Override
        public OutboxRecord mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Timestamp nextRetryTs = rs.getTimestamp("next_retry_at");
            final Timestamp createdAtTs = rs.getTimestamp("created_at");
            final Timestamp processedAtTs = rs.getTimestamp("processed_at");

            return new OutboxRecord(
                    rs.getObject("id", UUID.class),
                    rs.getLong("sequence_no"),
                    rs.getString("event_id"),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getInt("retry_count"),
                    nextRetryTs != null ? nextRetryTs.toInstant() : null,
                    rs.getString("last_error"),
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    processedAtTs != null ? processedAtTs.toInstant() : null
            );
        }
    }
}

