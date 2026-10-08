package com.venkat.payment.repository;

import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC repository for Payment entities.
 */
@Repository
public class PaymentRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PaymentRowMapper rowMapper = new PaymentRowMapper();

    public PaymentRepository(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Payment insert(final Payment payment) {
        final String sql = """
                INSERT INTO payments (
                    id, payment_reference, order_id, customer_id, amount, currency,
                    gateway, gateway_order_id, gateway_payment_id, status, payment_method,
                    qr_data, expires_at, paid_at, requires_manual_review, review_reason,
                    verification_attempts, next_verification_at, version, created_at, updated_at
                ) VALUES (
                    :id, :paymentReference, :orderId, :customerId, :amount, :currency,
                    :gateway, :gatewayOrderId, :gatewayPaymentId, :status, :paymentMethod,
                    :qrData, :expiresAt, :paidAt, :requiresManualReview, :reviewReason,
                    :verificationAttempts, :nextVerificationAt, :version, :createdAt, :updatedAt
                )
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", payment.getId())
                .addValue("paymentReference", payment.getPaymentReference())
                .addValue("orderId", payment.getOrderId())
                .addValue("customerId", payment.getCustomerId())
                .addValue("amount", payment.getAmount())
                .addValue("currency", payment.getCurrency())
                .addValue("gateway", payment.getGateway())
                .addValue("gatewayOrderId", payment.getGatewayOrderId())
                .addValue("gatewayPaymentId", payment.getGatewayPaymentId())
                .addValue("status", payment.getStatus().name())
                .addValue("paymentMethod", payment.getPaymentMethod())
                .addValue("qrData", payment.getQrData())
                .addValue("expiresAt", Timestamp.from(payment.getExpiresAt()))
                .addValue("paidAt", payment.getPaidAt() != null ? Timestamp.from(payment.getPaidAt()) : null)
                .addValue("requiresManualReview", payment.isRequiresManualReview())
                .addValue("reviewReason", payment.getReviewReason())
                .addValue("verificationAttempts", payment.getVerificationAttempts())
                .addValue("nextVerificationAt", payment.getNextVerificationAt() != null ? Timestamp.from(payment.getNextVerificationAt()) : null)
                .addValue("version", payment.getVersion())
                .addValue("createdAt", Timestamp.from(payment.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(payment.getUpdatedAt()));

        this.jdbcTemplate.update(sql, params);
        return payment;
    }

    public Optional<Payment> findById(final UUID id) {
        final String sql = "SELECT * FROM payments WHERE id = :id";
        try {
            final Payment payment = this.jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("id", id), this.rowMapper);
            return Optional.ofNullable(payment);
        } catch (final EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Payment> findByPaymentReference(final String paymentReference) {
        final String sql = "SELECT * FROM payments WHERE payment_reference = :paymentReference";
        try {
            final Payment payment = this.jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("paymentReference", paymentReference), this.rowMapper);
            return Optional.ofNullable(payment);
        } catch (final EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Payment> findByGatewayOrderId(final String gatewayOrderId) {
        final String sql = "SELECT * FROM payments WHERE gateway_order_id = :gatewayOrderId";
        try {
            final Payment payment = this.jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("gatewayOrderId", gatewayOrderId), this.rowMapper);
            return Optional.ofNullable(payment);
        } catch (final EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Updates gateway identifiers and transitions status to PENDING upon successful gateway order creation.
     */
    public int updateGatewayDetailsAndStatus(final UUID id,
                                             final String gatewayOrderId,
                                             final String qrData,
                                             final PaymentStatus newStatus,
                                             final long expectedVersion) {
        final String sql = """
                UPDATE payments
                SET gateway_order_id = :gatewayOrderId,
                    qr_data = :qrData,
                    status = :status,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id AND version = :expectedVersion
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("gatewayOrderId", gatewayOrderId)
                .addValue("qrData", qrData)
                .addValue("status", newStatus.name())
                .addValue("expectedVersion", expectedVersion);

        return this.jdbcTemplate.update(sql, params);
    }

    /**
     * Performs a status-guarded optimistic locking update on payment.
     */
    public int updateStatusWithGuard(final UUID id,
                                     final PaymentStatus newStatus,
                                     final Collection<String> allowedFromStatuses,
                                     final Instant paidAt,
                                     final String gatewayPaymentId,
                                     final boolean requiresManualReview,
                                     final String reviewReason,
                                     final long expectedVersion) {
        final String sql = """
                UPDATE payments
                SET status = :newStatus,
                    paid_at = COALESCE(:paidAt, paid_at),
                    gateway_payment_id = COALESCE(:gatewayPaymentId, gateway_payment_id),
                    requires_manual_review = :requiresManualReview,
                    review_reason = COALESCE(:reviewReason, review_reason),
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id
                  AND status IN (:allowedFromStatuses)
                  AND version = :expectedVersion
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("newStatus", newStatus.name())
                .addValue("allowedFromStatuses", allowedFromStatuses)
                .addValue("paidAt", paidAt != null ? Timestamp.from(paidAt) : null)
                .addValue("gatewayPaymentId", gatewayPaymentId)
                .addValue("requiresManualReview", requiresManualReview)
                .addValue("reviewReason", reviewReason)
                .addValue("expectedVersion", expectedVersion);

        return this.jdbcTemplate.update(sql, params);
    }

    /**
     * Marks payment as FAILED with an optional review reason.
     */
    public int markFailed(final UUID id, final String reason, final long expectedVersion) {
        final String sql = """
                UPDATE payments
                SET status = :status,
                    review_reason = :reason,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id AND version = :expectedVersion
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", PaymentStatus.FAILED.name())
                .addValue("reason", reason)
                .addValue("expectedVersion", expectedVersion);

        return this.jdbcTemplate.update(sql, params);
    }

    private static class PaymentRowMapper implements RowMapper<Payment> {
        @Override
        public Payment mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Timestamp expiresAtTs = rs.getTimestamp("expires_at");
            final Timestamp paidAtTs = rs.getTimestamp("paid_at");
            final Timestamp nextVerifTs = rs.getTimestamp("next_verification_at");
            final Timestamp createdAtTs = rs.getTimestamp("created_at");
            final Timestamp updatedAtTs = rs.getTimestamp("updated_at");

            return new Payment(
                    rs.getObject("id", UUID.class),
                    rs.getString("payment_reference"),
                    rs.getString("order_id"),
                    rs.getString("customer_id"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getString("gateway"),
                    rs.getString("gateway_order_id"),
                    rs.getString("gateway_payment_id"),
                    PaymentStatus.valueOf(rs.getString("status")),
                    rs.getString("payment_method"),
                    rs.getString("qr_data"),
                    expiresAtTs != null ? expiresAtTs.toInstant() : null,
                    paidAtTs != null ? paidAtTs.toInstant() : null,
                    rs.getBoolean("requires_manual_review"),
                    rs.getString("review_reason"),
                    rs.getInt("verification_attempts"),
                    nextVerifTs != null ? nextVerifTs.toInstant() : null,
                    rs.getLong("version"),
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    updatedAtTs != null ? updatedAtTs.toInstant() : null
            );
        }
    }
}

