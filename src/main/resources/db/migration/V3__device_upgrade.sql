-- V3: device_upgrade, one campaign's attempt on one device. The only table with a state machine.
CREATE TABLE device_upgrade (
    id                          BIGSERIAL PRIMARY KEY,
    campaign_id                 UUID        NOT NULL REFERENCES campaign (id),
    device_id                   TEXT        NOT NULL REFERENCES device (device_id),

    -- snapshot taken at campaign creation; never updated
    from_version                TEXT        NOT NULL,

    -- state machine (IMPLEMENTATION-PLAN.md §2.3)
    state                       TEXT        NOT NULL CHECK (state IN (
                                    'PENDING', 'NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED',
                                    'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    outcome_reason              TEXT,       -- REJECTED, FAULT_<n>, ROLLED_BACK, NO_CALLBACK, ...

    -- correlation across the reboot; a unique key per Download command
    command_key                 TEXT        UNIQUE,

    -- retry and timeout bookkeeping
    cr_attempts                 INT         NOT NULL DEFAULT 0,
    download_attempts           INT         NOT NULL DEFAULT 0,
    deadline_at                 TIMESTAMPTZ,    -- absolute, so it survives a restart

    -- evidence captured when the attempt resolved
    last_reported_version       TEXT,
    fault_code                  INT,
    late_evidence               BOOLEAN     NOT NULL DEFAULT FALSE,
    reported_version_mismatch   BOOLEAN     NOT NULL DEFAULT FALSE,   -- D9

    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminal_at                 TIMESTAMPTZ,
    version                     INT         NOT NULL DEFAULT 0,       -- optimistic lock

    UNIQUE (campaign_id, device_id)
);

-- At most one in-flight upgrade per device across all campaigns (ADR 0004 safety net).
CREATE UNIQUE INDEX one_active_upgrade_per_device ON device_upgrade (device_id)
    WHERE state IN ('NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED');

CREATE INDEX idx_du_campaign_state ON device_upgrade (campaign_id, state);
CREATE INDEX idx_du_deadline       ON device_upgrade (deadline_at)
    WHERE state IN ('NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED');
CREATE INDEX idx_du_device         ON device_upgrade (device_id);

