-- V7: keep the device-reported start and complete times of a TransferComplete (HLD 9.2).
-- Null until a matching TransferComplete that carries them arrives.
ALTER TABLE device_upgrade
    ADD COLUMN transfer_start_time    TIMESTAMPTZ,
    ADD COLUMN transfer_complete_time TIMESTAMPTZ;

