package com.venkat.payment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository managing downstream business process state transitions and deduplication.
 */
@Repository
public class BusinessProcessRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final BusinessProcessRowMapper rowMapper = new BusinessProcessRowMapper();

    public BusinessProcessRepository(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertConsumerProcessedEvent(final String eventId, final String consumerName) {
        final String sql = """
                INSERT INTO consumer_processed_events (event_id, consumer_name, processed_at)
                VALUES (:eventId, :consumerName, NOW())
                ON CONFLICT (event_id, consumer_name) DO NOTHING
                """;

        final int affected = this.jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("consumerName", consumerName)
        );
        return affected > 0;
    }

    public void insertBusinessProcess(final UUID paymentId, final String processType) {
        final String sql = """
                INSERT INTO payment_business_process (
                    id, payment_id, process_type, status, retry_count, next_attempt_at, created_at, updated_at
                ) VALUES (
                    gen_random_uuid(), :paymentId, :processType, 'PENDING', 0, NOW(), NOW(), NOW()
                ) ON CONFLICT (payment_id, process_type) DO NOTHING
                """;

        this.jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("processType", processType)
        );
    }

    public List<BusinessProcessRecord> claimPendingProcesses(final int limit) {
        final String sql = """
                SELECT id, payment_id, process_type, status, retry_count, next_attempt_at,
                       last_error, started_at, completed_at, created_at, updated_at
                FROM payment_business_process
                WHERE status = 'PENDING' AND next_attempt_at <= NOW()
                ORDER BY created_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """;

        return this.jdbcTemplate.query(sql, new MapSqlParameterSource("limit", limit), this.rowMapper);
    }

    public void markProcessing(final UUID id) {
        final String sql = """
                UPDATE payment_business_process
                SET status = 'PROCESSING',
                    started_at = NOW(),
                    updated_at = NOW()
                WHERE id = :id
                """;

        this.jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    public void markCompleted(final UUID id) {
        final String sql = """
                UPDATE payment_business_process
                SET status = 'COMPLETED',
                    completed_at = NOW(),
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = :id
                """;

        this.jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    public void recordFailure(final UUID id,
                              final int retryCount,
                              final Instant nextAttemptAt,
                              final String error,
                              final boolean permanentlyFailed) {
        final String sql = """
                UPDATE payment_business_process
                SET status = :status,
                    retry_count = :retryCount,
                    next_attempt_at = :nextAttemptAt,
                    last_error = :error,
                    updated_at = NOW()
                WHERE id = :id
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", permanentlyFailed ? "FAILED" : "PENDING")
                .addValue("retryCount", retryCount)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("error", error);

        this.jdbcTemplate.update(sql, params);
    }

    public record BusinessProcessRecord(
            UUID id,
            UUID paymentId,
            String processType,
            String status,
            int retryCount,
            Instant nextAttemptAt,
            String lastError,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private static class BusinessProcessRowMapper implements RowMapper<BusinessProcessRecord> {
        @Override
        public BusinessProcessRecord mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Timestamp nextAttemptTs = rs.getTimestamp("next_attempt_at");
            final Timestamp startedTs = rs.getTimestamp("started_at");
            final Timestamp completedTs = rs.getTimestamp("completed_at");
            final Timestamp createdTs = rs.getTimestamp("created_at");
            final Timestamp updatedTs = rs.getTimestamp("updated_at");

            return new BusinessProcessRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("process_type"),
                    rs.getString("status"),
                    rs.getInt("retry_count"),
                    nextAttemptTs != null ? nextAttemptTs.toInstant() : null,
                    rs.getString("last_error"),
                    startedTs != null ? startedTs.toInstant() : null,
                    completedTs != null ? completedTs.toInstant() : null,
                    createdTs != null ? createdTs.toInstant() : null,
                    updatedTs != null ? updatedTs.toInstant() : null
            );
        }
    }
}

