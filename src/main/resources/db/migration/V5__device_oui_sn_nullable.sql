-- V5: oui_sn relaxed to nullable.
-- The assignment's actual device interfaces never supply it: GET /devices?version= returns
-- only { deviceId, firmwareVersion } (prompt.md), and inform/command-response bodies carry
-- no identity fields either. V4's NOT NULL can never be satisfied by DeviceRepository.upsert,
-- so every insert would fail. model_name is unaffected; it was already nullable.
ALTER TABLE device ALTER COLUMN oui_sn DROP NOT NULL;

