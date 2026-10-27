# The device simulator, explained

This document explains `simulator/simulator.py` — the device simulator the assignment actually provides, received as `device_simulator.txt` at the repo root. It covers what the script does, how to run it, every endpoint and environment variable, and the behavioural details worth knowing before you point the controller at it. It also records how this changes [Phase 2 of IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md#phase-2-simulator-happy-path-first-day-1-about-15-h).

**Provenance.** `device_simulator.txt` at the repo root is kept exactly as received, for provenance. `simulator/simulator.py` is a runnable copy of the same code, with only the module docstring's run instructions adjusted for its new location. The logic is unchanged.

---

## 1. What changes in the plan

[IMPLEMENTATION-PLAN.md decision D1](../IMPLEMENTATION-PLAN.md#1-settled-decisions-overrides-to-the-design-plan) said *"we write our own simulator"*, because at the time the real one was not available. **That decision is now superseded.** We have the actual simulator the assignment describes, and Phase 2 becomes: wire this script up, run it, and confirm the happy path end to end. There is no need to design or build a new simulator from scratch, and the aspirational environment-variable spec in [IMPLEMENTATION-PLAN.md §2.4](../IMPLEMENTATION-PLAN.md#24-simulator-spec-simulatorsimulatorpy) no longer describes the real script — see [§5](#5-how-this-differs-from-the-planned-24-spec) below for the mapping.

---

## 2. What the script is

A single FastAPI application (`simulator/simulator.py`) that plays the part of an entire device fleet:

- On startup it creates `DEVICE_COUNT` devices, each assigned a fixed **behaviour profile** (healthy, offline, rejects downloads, reports a fault, and so on — see [§4](#4-device-profiles)).
- It exposes the two endpoints the controller is allowed to call (`POST /devices/{id}/connection-request`, `GET /devices`), plus one endpoint for the test-runner to read ground truth (`GET /_admin/summary`).
- In the other direction, it calls the controller's `POST /devices/{id}/inform` and `POST /devices/{id}/command-response`, exactly as a real device would.

Nothing here talks to a real network or flashes a real image. Downloads and reboots are simulated with `asyncio.sleep`.

---

## 3. Running it

### Standalone (Python)

```bash
cd simulator
pip install -r requirements.txt
CONTROLLER_URL=http://localhost:8080 DEVICE_COUNT=1000 \
    uvicorn simulator:app --host 0.0.0.0 --port 9000
```

`requirements.txt` pins `fastapi`, `uvicorn[standard]` and `httpx` — nothing else is needed.

### Via Docker Compose

`compose.yaml` now has a `simulator` service, built from `simulator/Dockerfile`. Every environment variable the script reads (see [§6](#6-environment-variables)) is passed through explicitly, each with the same default the script itself uses, so the full set is visible and overridable from `compose.yaml` or the shell — nothing is silently stuck at a baked-in default:

```bash
docker compose up -d postgres simulator
```

By default it targets `http://host.docker.internal:8080`, so it can reach a controller you run on the host with `./mvnw spring-boot:run`. Override any variable from the shell, or by exporting it before `docker compose up` — Compose's `${VAR:-default}` syntax picks it up automatically:

```bash
CONTROLLER_URL=http://devicemgmt-controller:8080 DEVICE_COUNT=10000 P_OFFLINE=0.10 \
    docker compose up -d
```

A `.env` file at the repo root works the same way, if you prefer not to repeat flags on every run.

### A "happy path only" smoke test

Every failure mode is independently dialled in by an environment variable (see [§6](#6-environment-variables)). To get a fleet that always succeeds, the way Phase 2's first demo needs, set every `P_*` variable to `0`:

```bash
P_OFFLINE=0 P_REJECT=0 P_FAULT=0 P_NO_TC=0 P_REVERT=0 \
P_DUPLICATE=0 P_LATE=0 \
CONTROLLER_URL=http://localhost:8080 DEVICE_COUNT=100 \
    uvicorn simulator:app --port 9000
```

With all of these at zero, every device is `healthy`: it accepts the Download, "reboots", and reports a `TransferComplete` with `faultCode: 0`.

---

## 4. Device profiles

On startup each device is assigned one profile, chosen once from `PROFILES` (plus an implicit `healthy` for whatever probability is left over). The profile never changes for the lifetime of the run.

| Profile | Share (env var) | What it does | Matching controller outcome |
|---|---|---|---|
| `healthy` | remainder | Accepts, "reboots", reports `TransferComplete` with `faultCode: 0` on the target version | `SUCCEEDED` (`TRANSFER_COMPLETE`) |
| `offline` | `P_OFFLINE` (0.05) | Never responds to a connection request or sends an inform | `TIMED_OUT` (`NO_CALLBACK`) |
| `reject` | `P_REJECT` (0.03) | Replies to the Download with `REJECTED` | `FAILED` (`REJECTED`) |
| `fault` | `P_FAULT` (0.04) | Accepts, "reboots", reports `TransferComplete` with `faultCode: 9010`, version unchanged | `FAILED` (`FAULT`) |
| `no_tc` | `P_NO_TC` (0.03) | Accepts, "reboots" onto the **target** version, sends a `BOOT` inform with no `transferComplete` | `SUCCEEDED` (`INFERRED_FROM_VERSION`) |
| `revert` | `P_REVERT` (0.02) | Accepts, "reboots" but stays on the **old** version, sends a `BOOT` inform with no `transferComplete` | `FAILED` (`ROLLED_BACK`) |

The shares must sum to at most `1.0` (`validate_settings` enforces this); whatever is left over is `healthy`. With the defaults above, about 83% of the fleet is `healthy`.

A `busy` device (one in the middle of a simulated download/reboot) rejects any further Download with `REJECTED "BUSY"`, and skips its own periodic inform until it is free again.

---

## 5. How this differs from the planned §2.4 spec

[IMPLEMENTATION-PLAN.md §2.4](../IMPLEMENTATION-PLAN.md#24-simulator-spec-simulatorsimulatorpy) was written before we had this file, so its names and units do not match. Use this table, not that section, as the source of truth for configuring a run:

| Planned (§2.4) | Actual (this script) | Note |
|---|---|---|
| `CONTROLLER_URL`, `DEVICE_COUNT`, `SEED` | same names | Unchanged |
| `PORT` | n/a | Set via `uvicorn --port`, not an env var |
| `PERIODIC_INTERVAL_MS` | `PERIODIC_INFORM_SEC` (300) | **Seconds, not milliseconds** |
| `PCT_OFFLINE`, `PCT_REJECT`, `PCT_FAULT` | `P_OFFLINE`, `P_REJECT`, `P_FAULT` | Fractions (`0.05`), not percentages (`5`) |
| `PCT_NO_TC_NEW_VERSION` | `P_NO_TC` | Same meaning |
| `PCT_ROLLBACK` | `P_REVERT` | Same meaning, different name |
| `PCT_DUPLICATE`, `PCT_LATE` | `P_DUPLICATE`, `P_LATE` | Fractions, and the "late" mechanic is different — see [§7](#7-things-worth-knowing-before-you-rely-on-this) |
| `REBOOT_MIN_MS` / `REBOOT_MAX_MS` | `REBOOT_MIN_SEC` / `REBOOT_MAX_SEC` (5 / 240) | Seconds. There is also a separate `DOWNLOAD_MIN_SEC` / `DOWNLOAD_MAX_SEC` (2 / 20) delay before the reboot delay, which §2.4 did not have |
| n/a | `OLD_VERSION` (1.0.0) | The starting firmware version for every device |
| n/a | `LATE_EXTRA_SEC` (600) | Extra delay added for a "late" callback, on top of the normal reboot delay |

No `imageUrl`/`targetVersion` download actually happens; the script only sleeps for `DOWNLOAD_MIN_SEC`–`DOWNLOAD_MAX_SEC` to represent it.

---

## 6. Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `CONTROLLER_URL` | `http://localhost:8080` | Base URL the simulator calls for informs and command-responses |
| `DEVICE_COUNT` | `1000` | Fleet size |
| `SEED` | `42` | Seeds both the fleet-generation RNG and the behaviour-timing RNG, so a run is repeatable |
| `OLD_VERSION` | `1.0.0` | The firmware version every device starts on |
| `PERIODIC_INFORM_SEC` | `300` | Average interval between a device's unprompted informs |
| `DOWNLOAD_MIN_SEC` / `DOWNLOAD_MAX_SEC` | `2` / `20` | Simulated download time after a Download is accepted |
| `REBOOT_MIN_SEC` / `REBOOT_MAX_SEC` | `5` / `240` | Simulated reboot time after the download |
| `P_DUPLICATE` | `0.05` | Chance any given callback (inform or command-response) is sent a second time, 0.1–5 s later |
| `P_LATE` | `0.05` | Chance a device's `TransferComplete` is delayed — see [§7](#7-things-worth-knowing-before-you-rely-on-this) |
| `LATE_EXTRA_SEC` | `600` | Extra delay added when `P_LATE` fires (minimum 60 s) |
| `P_OFFLINE` | `0.05` | Share of the fleet that never responds to anything |
| `P_REJECT` | `0.03` | Share that always rejects the Download |
| `P_FAULT` | `0.04` | Share that reports a TransferComplete fault |
| `P_NO_TC` | `0.03` | Share that reboots onto the target version with no TransferComplete |
| `P_REVERT` | `0.02` | Share that reboots back onto the old version with no TransferComplete |

All `P_*` and `P_DUPLICATE`/`P_LATE` values must be between 0 and 1, and the five profile shares must not sum past 1. `validate_settings()` checks this at startup and raises before the app serves any traffic.

---

## 7. Things worth knowing before you rely on this

### The device-facing contract matches the assignment exactly

Requests and responses use the exact field names in `prompt.md`: `deviceId`, `firmwareVersion`, `event`, `transferComplete { commandKey, faultCode, startTime, completeTime }`, `commands: [{ type: "DOWNLOAD", commandKey, url, targetVersion }]`, and `{ commandKey, status, reason? }` for the command-response. No adapter layer is needed between this simulator and the controller's `DeviceController` (Phase 3).

### `GET /devices` does not leak behaviour

The response only ever contains `deviceId` and `firmwareVersion`. There is no way to tell a device's profile from this endpoint, which matches the assignment's "do not use this to poll for correlation" instruction — there is nothing in it to correlate with anyway. `GET /_admin/summary` does expose profile and version counts, but it is explicitly an interviewer/test-runner tool. Use it only to validate a demo run's ground truth, never to make the controller's own decisions.

### "Late" does not test what you might expect

`P_LATE` does **not** simply delay the `TransferComplete`. For a device that is otherwise `healthy`, the code path is:

1. The device reboots onto the **target version** (this happens before the late check, for `healthy` and `no_tc` profiles).
2. It immediately sends a `BOOT` inform — **with no `transferComplete`**.
3. *Then* it sleeps an extra 60–600 s (`LATE_EXTRA_SEC`).
4. Only after that does it send the real `TransferComplete`.

Because the `BOOT` inform in step 2 already reports the target version, the controller's state machine resolves the row via `INFERRED_FROM_VERSION` (plan §6.3) **before** the "late" `TransferComplete` even arrives. The row is already `SUCCEEDED` by the time the delayed `TransferComplete` shows up, so it lands on the "duplicate on a finished row" path, not on the genuine "late evidence after TIMED_OUT" path (plan §2.3's `TIMED_OUT → TransferComplete` row).

**What this means in practice:** `P_LATE` is a reliable way to test duplicate-handling and idempotency on an already-finished row. It is **not** a reliable way to exercise the TIMED_OUT→late-evidence transition, because the early `BOOT` almost always finishes the row first. `UpgradeStateMachineTest` already covers that transition directly (`TIMED_OUT: late TransferComplete ... succeeds` / `... fails`), so test coverage is not missing — just be aware this simulator's default knobs will rarely, if ever, drive it during a live run. If you want to see it live, you would need to either shrink `completion-wait-ms` below the typical download+reboot time, or temporarily patch the simulator to skip the early `BOOT`.

### Timing defaults already line up with the controller's defaults

| Simulator delay | Default | Controller timeout it runs against | Default |
|---|---|---|---|
| Connection-request → inform | 0.2–3 s | `cr-wait-ms` | 30 s |
| Download accept → ack | immediate | `ack-wait-ms` | 30 s |
| Download + reboot (worst case) | up to 20 + 240 = 260 s | `completion-wait-ms` | 300 s |

The worst-case download-plus-reboot time (260 s) stays inside the default 300 s completion wait, so a normal run should not spuriously time out `ACCEPTED` rows. If you raise `REBOOT_MAX_SEC` or `DOWNLOAD_MAX_SEC`, raise `completion-wait-ms` to match.

### Resource use at scale

Every device gets its own `asyncio` task for periodic informs, and all outbound calls share one `httpx.AsyncClient` capped at 500 connections (`httpx.Limits(max_connections=500)`). This comfortably handles the 1k and 10k demo runs in the plan. For the 1M–10M fleet sizes discussed in [docs/scheduler-explained.md §9](scheduler-explained.md#9-what-changes-at-1m10m-devices), this single-process simulator would itself need either a bigger host, a higher connection limit, or splitting the fleet across several simulator instances with disjoint device ids — that is a simulator-side scaling concern, separate from the controller-side scaling already covered in that document.

---

## 8. File map

| Path | What it is |
|---|---|
| `device_simulator.txt` (repo root) | The file as received. Left untouched for provenance. |
| `simulator/simulator.py` | Runnable copy of the same code. Only the module docstring's run instructions changed. |
| `simulator/requirements.txt` | `fastapi`, `uvicorn[standard]`, `httpx` |
| `simulator/Dockerfile` | Builds the image used by the `simulator` service in `compose.yaml` |
| `compose.yaml` | Now has a `simulator` service alongside `postgres`, defaulting to `CONTROLLER_URL=http://host.docker.internal:8080` |

