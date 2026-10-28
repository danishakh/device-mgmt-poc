-- V6: align the schema with docs/HLD.md and ADR 0005.
-- Added as a new migration because V1 to V5 are already applied and Flyway rejects edits to them.

-- device: model_name and oui_sn are never supplied by any interface (see V5). deviceId is the identity.
ALTER TABLE device
    DROP COLUMN model_name,
    DROP COLUMN oui_sn;

-- campaign: ADR 0004 (one active campaign) is superseded by ADR 0005.
DROP INDEX one_active_campaign;

-- min_sample must be at least 1. Existing rows are lifted first so the new check cannot fail.
UPDATE campaign SET min_sample = 1 WHERE min_sample < 1;
ALTER TABLE campaign DROP CONSTRAINT campaign_min_sample_check;
ALTER TABLE campaign ADD CONSTRAINT campaign_min_sample_check CHECK (min_sample >= 1);

-- How many matching devices were left out at creation because they already have an open upgrade
-- in another campaign. A fact recorded once, not a running counter.
ALTER TABLE campaign
    ADD COLUMN excluded_devices INT NOT NULL DEFAULT 0 CHECK (excluded_devices >= 0);

-- device_upgrade: CANCELLED is a new state (HLD 7.1).
ALTER TABLE device_upgrade DROP CONSTRAINT device_upgrade_state_check;
ALTER TABLE device_upgrade ADD CONSTRAINT device_upgrade_state_check CHECK (state IN (
    'PENDING', 'NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED',
    'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'));

-- Evidence that arrives after an outcome and does not change it (HLD 7.2).
ALTER TABLE device_upgrade
    ADD COLUMN trailing_evidence   TEXT,   -- FAULT | REPORTED_TARGET_VERSION; null until some arrives
    ADD COLUMN trailing_fault_code INT,    -- set when trailing_evidence is FAULT
    ADD COLUMN outcome_detail      TEXT;   -- extra text for the outcome, e.g. the rejection reason

-- A device has at most one OPEN upgrade across all campaigns: PENDING counts too, so the database
-- enforces ADR 0005 even when two campaign creations race. Replaces the in-flight-only index from V3.
DROP INDEX one_active_upgrade_per_device;
CREATE UNIQUE INDEX one_open_upgrade_per_device ON device_upgrade (device_id)
    WHERE state IN ('PENDING', 'NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED');

