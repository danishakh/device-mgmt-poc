-- V1: campaign, the operator's intent. Immutable except status, status_reason and completed_at.
CREATE TABLE campaign (
    id                      UUID PRIMARY KEY,
    target_version          TEXT        NOT NULL,
    image_url               TEXT        NOT NULL,
    from_version            TEXT        NOT NULL,   -- deviceFilter.currentVersion
    max_concurrent          INT         NOT NULL CHECK (max_concurrent > 0),
    failure_threshold_pct   NUMERIC     NOT NULL CHECK (failure_threshold_pct BETWEEN 0 AND 100),
    min_sample              INT         NOT NULL CHECK (min_sample >= 0),
    status                  TEXT        NOT NULL CHECK (status IN ('RUNNING', 'PAUSED', 'HALTED', 'COMPLETED')),
    status_reason           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ
);

CREATE INDEX idx_campaign_status ON campaign (status);

-- ADR 0004: at most one campaign RUNNING or PAUSED. The service checks this too,
-- but the index makes a race between two POST /campaigns calls fail safely.
CREATE UNIQUE INDEX one_active_campaign ON campaign ((true))
    WHERE status IN ('RUNNING', 'PAUSED');

