package com.venkat.payment.repository;

import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC repository for audit and webhook deduplication events.
 */
@Repository
public class PaymentEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PaymentEventRepository(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically inserts an event with conflict protection on event_id.
     *
     * @return true if row was inserted; false if duplicate exists (0 rows inserted)
     */
    public boolean insertEventIfNotExists(final UUID id,
                                          final UUID paymentId,
                                          final String eventId,
                                          final String eventType,
                                          final String source,
                                          final String payloadJson) {
        final String sql = """
                INSERT INTO payment_events (id, payment_id, event_id, event_type, source, payload, processed, created_at)
                VALUES (:id, :paymentId, :eventId, :eventType, :source, CAST(:payload AS JSONB), FALSE, NOW())
                ON CONFLICT (event_id) DO NOTHING
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id != null ? id : UUID.randomUUID())
                .addValue("paymentId", paymentId)
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("source", source)
                .addValue("payload", payloadJson != null ? payloadJson : "{}");

        final int affected = this.jdbcTemplate.update(sql, params);
        return affected > 0;
    }

    /**
     * Checks if an event is already marked as processed.
     */
    public boolean isEventProcessed(final String eventId) {
        final String sql = "SELECT processed FROM payment_events WHERE event_id = :eventId";
        try {
            final Boolean processed = this.jdbcTemplate.queryForObject(
                    sql,
                    new MapSqlParameterSource("eventId", eventId),
                    Boolean.class
            );
            return Boolean.TRUE.equals(processed);
        } catch (final EmptyResultDataAccessException e) {
            return false;
        }
    }

    /**
     * Marks an event as processed with current timestamp.
     */
    public void markEventProcessed(final String eventId) {
        final String sql = """
                UPDATE payment_events
                SET processed = TRUE,
                    processed_at = NOW()
                WHERE event_id = :eventId
                """;

        this.jdbcTemplate.update(sql, new MapSqlParameterSource("eventId", eventId));
    }
}

