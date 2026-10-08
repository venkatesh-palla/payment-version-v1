-- V2__level2_hardening.sql: Schema additions for Level 2 reliability
-- Adds idempotency_keys, payment_outbox, payment_business_process,
-- consumer_processed_events, shedlock, and partial indexes.

-- 1. Idempotency tracking table
CREATE TABLE idempotency_keys (
    scope VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status_code INT,
    response_body JSONB,
    payment_id UUID CONSTRAINT fk_idempotency_payment REFERENCES payments(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys(expires_at);

-- 2. Transactional Outbox table
CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no BIGSERIAL,
    event_id VARCHAR(128) NOT NULL CONSTRAINT uq_payment_outbox_event_id UNIQUE,
    payment_id UUID NOT NULL CONSTRAINT fk_payment_outbox_payment REFERENCES payments(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CONSTRAINT chk_payment_outbox_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_outbox_status_next_retry ON payment_outbox(status, next_retry_at);

-- 3. Downstream Business Process tracking table
CREATE TABLE payment_business_process (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL CONSTRAINT fk_payment_bp_payment REFERENCES payments(id) ON DELETE CASCADE,
    process_type VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CONSTRAINT chk_payment_bp_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payment_bp_payment_process UNIQUE (payment_id, process_type)
);

CREATE INDEX idx_payment_bp_status_next_attempt ON payment_business_process(status, next_attempt_at);

-- 4. Downstream consumer deduplication table
CREATE TABLE consumer_processed_events (
    event_id VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_name)
);

-- 5. ShedLock table for multi-instance scheduler synchronization
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

-- 6. Partial unique index to enforce at most one ACTIVE payment per order
CREATE UNIQUE INDEX uq_payments_active_order ON payments(order_id)
WHERE status IN ('CREATED', 'QR_GENERATED', 'PENDING');

-- 7. Partial indexes for scheduled workers
CREATE INDEX idx_payments_pending_expires_at ON payments(expires_at)
WHERE status = 'PENDING';

CREATE INDEX idx_payments_pending_next_verification_at ON payments(next_verification_at)
WHERE status = 'PENDING';

