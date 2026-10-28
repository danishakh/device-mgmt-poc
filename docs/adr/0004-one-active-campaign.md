---
status: superseded by ADR-0005
---

# One active campaign at a time

`POST /campaigns` returns 409 while another campaign is RUNNING or PAUSED. If two campaigns ran at once, their eligible devices could overlap. Handling that properly is a design problem of its own, and none of the assignment's requirements asks for it.

The schema does not block the multi-campaign case:
- `device_upgrade` is keyed by `(campaign_id, device_id)`.
- A partial unique index allows at most one in-flight device upgrade per device across all campaigns. It is kept as a safety net.

## Consequences: what supporting multiple campaigns would need

- **Claiming a slot.** The scheduler fills free slots with `AND NOT EXISTS (in-flight upgrade for this device in any campaign)`. Today the constraint would raise an error instead. A device that is busy elsewhere stays PENDING and is retried on a later tick.
- **A stale from version.** Campaign B may pick a device while it is on 1.0.0, and campaign A then moves it to 2.0.0. Before a connection request is sent, B checks `device.firmware_version` against its `from_version` (or its target). If the device is already on B's target, B marks it Succeeded (`ALREADY_AT_TARGET`). If the device no longer matches B's filter, B marks it Finished (`NO_LONGER_ELIGIBLE`) without counting it toward the failure rate.
- **Conflicting targets.** Two active campaigns with different target versions for the same from version need a priority or a rejection rule when the second campaign is created. That is a product decision, not a technical one.
- **Throughput limits.** Each campaign's `maxConcurrent` is separate. A global cap would need a fleet-level in-flight count.

