---
status: accepted (supersedes ADR-0004)
---

# A device belongs to at most one active campaign

Several campaigns may run at once, but their device sets never overlap. When a campaign is created, any eligible device that already has an open device upgrade (pending or in flight) in another campaign is left out of the new campaign's snapshot, and the response reports how many were excluded. We chose this over rejecting the whole request with 409 (which blocks an operator because of a few busy devices) and over letting campaigns share devices (which needs priority rules between conflicting targets, re-checks of a device's from version, and a fleet-wide in-flight count: a product problem, not one the assignment asks for).

## Consequences

- Each campaign's concurrency limit, failure rate and halt rule are computed from its own device upgrades only. There is no fleet-wide concurrency cap.
- Two active campaigns may share a from version with different targets. Their device sets are disjoint, so nothing conflicts.
- The database enforces at most one in-flight device upgrade per device. Exclusion is checked against open device upgrades, not campaign status: a halted campaign's in-flight devices are still excluded until they finish, and a paused campaign's pending devices stay reserved for it.
- A device becomes eligible again once it has no open device upgrade: its upgrade finished, or was cancelled when its campaign halted.

