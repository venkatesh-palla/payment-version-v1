-- V1__init.sql: Initial schema for UPI QR Payment Service
-- Contains payments table and payment_events audit/deduplication table

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_reference VARCHAR(64) NOT NULL CONSTRAINT uk_payments_reference UNIQUE,
    order_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64),
    amount NUMERIC(19,2) NOT NULL CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    gateway VARCHAR(32) NOT NULL,
    gateway_order_id VARCHAR(128),
    gateway_payment_id VARCHAR(128),
    status VARCHAR(20) NOT NULL CONSTRAINT chk_payments_status CHECK (
        status IN ('CREATED', 'QR_GENERATED', 'PENDING', 'SUCCESS', 'FAILED', 'EXPIRED', 'CANCELLED', 'REFUNDED')
    ),
    payment_method VARCHAR(32),
    qr_data TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    requires_manual_review BOOLEAN NOT NULL DEFAULT FALSE,
    review_reason VARCHAR(64),
    verification_attempts INT NOT NULL DEFAULT 0,
    next_verification_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_gateway_order_id ON payments(gateway_order_id);
CREATE INDEX idx_payments_gateway_payment_id ON payments(gateway_payment_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_expires_at ON payments(expires_at);

CREATE TABLE payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID CONSTRAINT fk_payment_events_payment REFERENCES payments(id) ON DELETE SET NULL,
    event_id VARCHAR(128) NOT NULL CONSTRAINT uk_payment_events_event_id UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL CONSTRAINT chk_payment_events_source CHECK (source IN ('WEBHOOK', 'SYSTEM', 'GATEWAY_POLL')),
    payload JSONB,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_events_payment_id ON payment_events(payment_id);
CREATE INDEX idx_payment_events_event_id ON payment_events(event_id);

