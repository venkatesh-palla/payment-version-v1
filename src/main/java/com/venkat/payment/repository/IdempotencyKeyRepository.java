package com.venkat.payment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC repository for tracking distributed API idempotency keys.
 */
@Repository
public class IdempotencyKeyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final IdempotencyRowMapper rowMapper = new IdempotencyRowMapper();

    public IdempotencyKeyRepository(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically claims an idempotency key with IN_PROGRESS status.
     * Returns true if successfully inserted, false if the key already exists (conflict).
     */
    public boolean tryClaimKey(final String scope,
                               final String key,
                               final String requestHash,
                               final Instant expiresAt) {
        final String sql = """
                INSERT INTO idempotency_keys (
                    scope, idempotency_key, request_hash, status, created_at, expires_at
                ) VALUES (
                    :scope, :key, :requestHash, 'IN_PROGRESS', NOW(), :expiresAt
                ) ON CONFLICT (scope, idempotency_key) DO NOTHING
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("key", key)
                .addValue("requestHash", requestHash)
                .addValue("expiresAt", Timestamp.from(expiresAt));

        final int affected = this.jdbcTemplate.update(sql, params);
        return affected > 0;
    }

    public Optional<IdempotencyRecord> findByKey(final String scope, final String key) {
        final String sql = """
                SELECT scope, idempotency_key, request_hash, status, response_status_code,
                       response_body, payment_id, created_at, expires_at
                FROM idempotency_keys
                WHERE scope = :scope AND idempotency_key = :key
                """;

        try {
            final IdempotencyRecord record = this.jdbcTemplate.queryForObject(
                    sql,
                    new MapSqlParameterSource("scope", scope).addValue("key", key),
                    this.rowMapper
            );
            return Optional.ofNullable(record);
        } catch (final EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Completes an idempotency key record with the final HTTP response payload and status.
     */
    public void completeKey(final String scope,
                            final String key,
                            final int statusCode,
                            final String responseBodyJson,
                            final UUID paymentId) {
        final String sql = """
                UPDATE idempotency_keys
                SET status = 'COMPLETED',
                    response_status_code = :statusCode,
                    response_body = CAST(:responseBody AS JSONB),
                    payment_id = :paymentId
                WHERE scope = :scope AND idempotency_key = :key
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("key", key)
                .addValue("statusCode", statusCode)
                .addValue("responseBody", responseBodyJson)
                .addValue("paymentId", paymentId);

        this.jdbcTemplate.update(sql, params);
    }

    public record IdempotencyRecord(
            String scope,
            String idempotencyKey,
            String requestHash,
            String status,
            Integer responseStatusCode,
            String responseBody,
            UUID paymentId,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    private static class IdempotencyRowMapper implements RowMapper<IdempotencyRecord> {
        @Override
        public IdempotencyRecord mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Timestamp createdAtTs = rs.getTimestamp("created_at");
            final Timestamp expiresAtTs = rs.getTimestamp("expires_at");
            final int statusCode = rs.getInt("response_status_code");

            return new IdempotencyRecord(
                    rs.getString("scope"),
                    rs.getString("idempotency_key"),
                    rs.getString("request_hash"),
                    rs.getString("status"),
                    rs.wasNull() ? null : statusCode,
                    rs.getString("response_body"),
                    rs.getObject("payment_id", UUID.class),
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    expiresAtTs != null ? expiresAtTs.toInstant() : null
            );
        }
    }
}

