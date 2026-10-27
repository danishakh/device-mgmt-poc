-- V2: device, what the controller last heard from each device. Current facts only.
-- Eligibility comes from GET /devices?version=, so this table is filled by campaign
-- creation (upsert) and by every inform (upsert). Upgrade data lives in device_upgrade.
CREATE TABLE device (
    device_id           TEXT PRIMARY KEY,
    firmware_version    TEXT        NOT NULL,   -- last version the device reported
    last_event          TEXT,                   -- BOOT | CONNECTION_REQUEST | PERIODIC | TRANSFER_COMPLETE
    last_inform_at      TIMESTAMPTZ,            -- null until the device has informed
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_version ON device (firmware_version);

