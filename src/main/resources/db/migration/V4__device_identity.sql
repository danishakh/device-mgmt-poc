-- V4: device identity fields. Added as a new migration because V2 was already applied
-- and Flyway rejects edits to applied migrations (checksum mismatch).
-- oui_sn is NOT NULL: this runs on an empty device table. On a populated table it would
-- need a backfill first.
ALTER TABLE device
    ADD COLUMN model_name TEXT,                 -- model name of the device
    ADD COLUMN oui_sn     TEXT NOT NULL;        -- OUI-SerialNumber of the device

