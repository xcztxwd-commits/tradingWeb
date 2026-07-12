CREATE TABLE trading.batch_action_requests (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES core.trading_accounts(id) ON DELETE CASCADE,
    action_type VARCHAR(64) NOT NULL,
    request_id VARCHAR(256) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    owner_token UUID NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    scope_ids TEXT NOT NULL,
    response_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_batch_action_request_status
        CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_batch_action_response_state
        CHECK (
            (status = 'PROCESSING' AND response_payload IS NULL)
            OR (status = 'COMPLETED' AND response_payload IS NOT NULL)
        ),
    UNIQUE (account_id, action_type, request_id)
);

CREATE INDEX ix_batch_action_requests_account_created
    ON trading.batch_action_requests(account_id, created_at DESC);

CREATE INDEX ix_batch_action_requests_processing_lease
    ON trading.batch_action_requests(lease_until)
    WHERE status = 'PROCESSING';
