# The device simulator, explained

This document explains `simulator/simulator.py`, the device simulator provided with the assignment (received as `device_simulator.txt` at the repo root). It covers what the script does, how to run it, every endpoint and environment variable, and the behavioural details worth knowing before you point the controller at it. The controller's design is in [HLD.md](HLD.md).

**Provenance.** `device_simulator.txt` at the repo root is kept exactly as received. `simulator/simulator.py` is a runnable copy of the same code, with only the module docstring's run instructions adjusted for its new location. The logic is unchanged.

---

## 1. What the script is

A single FastAPI application (`simulator/simulator.py`) that plays the part of an entire device fleet:

- On startup it creates `DEVICE_COUNT` devices, each assigned a fixed **behaviour profile** (healthy, offline, rejects downloads, reports a fault, and so on; see [§3](#3-device-profiles)).
- It exposes the two endpoints the controller is allowed to call (`POST /devices/{id}/connection-request`, `GET /devices`), plus one endpoint for the test-runner to read ground truth (`GET /_admin/summary`).
- In the other direction, it calls the controller's `POST /devices/{id}/inform` and `POST /devices/{id}/command-response`, exactly as a real device would.

Nothing here talks to a real network or flashes a real image. Downloads and reboots are simulated with `asyncio.sleep`.

---

## 2. Running it

### Standalone (Python)

```bash
cd simulator
pip install -r requirements.txt
CONTROLLER_URL=http://localhost:8080 DEVICE_COUNT=1000 \
    uvicorn simulator:app --host 0.0.0.0 --port 9000
```

`requirements.txt` pins `fastapi`, `uvicorn[standard]` and `httpx`. Nothing else is needed.

### Via Docker Compose

`compose.yaml` has a `simulator` service, built from `simulator/Dockerfile`. Every environment variable the script reads (see [§4](#4-environment-variables)) is passed through explicitly, each with the same default the script itself uses, so the full set is visible and overridable from `compose.yaml` or the shell:

```bash
docker compose up -d postgres simulator
```

By default it targets `http://host.docker.internal:8080`, so it can reach a controller you run on the host with `./mvnw spring-boot:run`. Override any variable from the shell, or by exporting it before `docker compose up`. Compose's `${VAR:-default}` syntax picks it up automatically:

```bash
CONTROLLER_URL=http://devicemgmt-controller:8080 DEVICE_COUNT=10000 P_OFFLINE=0.10 \
    docker compose up -d
```

A `.env` file at the repo root works the same way.

### A "happy path only" smoke test

Every failure mode is independently dialled in by an environment variable (see [§4](#4-environment-variables)). To get a fleet that always succeeds, set every `P_*` variable to `0`:

```bash
P_OFFLINE=0 P_REJECT=0 P_FAULT=0 P_NO_TC=0 P_REVERT=0 \
P_DUPLICATE=0 P_LATE=0 \
CONTROLLER_URL=http://localhost:8080 DEVICE_COUNT=100 \
    uvicorn simulator:app --port 9000
```

With all of these at zero, every device is `healthy`: it accepts the Download, "reboots", and reports a `TransferComplete` with `faultCode: 0`.

---

## 3. Device profiles

On startup each device is assigned one profile, chosen once from `PROFILES` (plus an implicit `healthy` for whatever probability is left over). The profile never changes for the lifetime of the run.

| Profile | Share (env var) | What it does | Matching controller outcome |
|---|---|---|---|
| `healthy` | remainder | Accepts, "reboots", reports `TransferComplete` with `faultCode: 0` on the target version | Succeeded (TransferComplete) |
| `offline` | `P_OFFLINE` (0.05) | Never responds to a connection request or sends an inform | Timed Out (no callback) |
| `reject` | `P_REJECT` (0.03) | Replies to the Download with `REJECTED` | Failed (rejected) |
| `fault` | `P_FAULT` (0.04) | Accepts, "reboots", reports `TransferComplete` with `faultCode: 9010`, version unchanged | Failed (fault) |
| `no_tc` | `P_NO_TC` (0.03) | Accepts, "reboots" onto the **target** version, sends a `BOOT` inform with no `transferComplete` | Succeeded (inferred from version) |
| `revert` | `P_REVERT` (0.02) | Accepts, "reboots" but stays on the **old** version, sends a `BOOT` inform with no `transferComplete` | Failed (rolled back) |

The shares must sum to at most `1.0` (`validate_settings` enforces this); whatever is left over is `healthy`. With the defaults above, about 83% of the fleet is `healthy`.

A `busy` device (one in the middle of a simulated download/reboot) rejects any further Download with `REJECTED "BUSY"`, ignores connection requests, and skips its own periodic inform until it is free again.

---

## 4. Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `CONTROLLER_URL` | `http://localhost:8080` | Base URL the simulator calls for informs and command-responses |
| `DEVICE_COUNT` | `1000` | Fleet size |
| `SEED` | `42` | Seeds both the fleet-generation RNG and the behaviour-timing RNG, so a run is repeatable |
| `OLD_VERSION` | `1.0.0` | The firmware version every device starts on |
| `PERIODIC_INFORM_SEC` | `300` | Interval between a device's unprompted informs |
| `DOWNLOAD_MIN_SEC` / `DOWNLOAD_MAX_SEC` | `2` / `20` | Simulated download time after a Download is accepted |
| `REBOOT_MIN_SEC` / `REBOOT_MAX_SEC` | `5` / `240` | Simulated reboot time after the download |
| `P_DUPLICATE` | `0.05` | Chance any given callback (inform or command-response) is sent a second time, 0.1–5 s later |
| `P_LATE` | `0.05` | Chance a device's `TransferComplete` is delayed; see [§5](#5-things-worth-knowing-before-you-rely-on-this) |
| `LATE_EXTRA_SEC` | `600` | Upper bound of the extra delay when `P_LATE` fires (the delay is 60 s to this value) |
| `P_OFFLINE` | `0.05` | Share of the fleet that never responds to anything |
| `P_REJECT` | `0.03` | Share that always rejects the Download |
| `P_FAULT` | `0.04` | Share that reports a TransferComplete fault |
| `P_NO_TC` | `0.03` | Share that reboots onto the target version with no TransferComplete |
| `P_REVERT` | `0.02` | Share that reboots back onto the old version with no TransferComplete |

All `P_*` values must be between 0 and 1, and the five profile shares must not sum past 1. `validate_settings()` checks this at startup and raises before the app serves any traffic. Durations are in **seconds** and probabilities are **fractions** (`0.05`), not percentages.

---

## 5. Things worth knowing before you rely on this

### The device-facing contract matches the assignment exactly

Requests and responses use the exact field names in the assignment: `deviceId`, `firmwareVersion`, `event`, `transferComplete { commandKey, faultCode, startTime, completeTime }`, `commands: [{ type: "DOWNLOAD", commandKey, url, targetVersion }]`, and `{ commandKey, status, reason? }` for the command-response. No adapter layer is needed.

### `GET /devices` does not leak behaviour

The response only ever contains `deviceId` and `firmwareVersion`. There is no way to tell a device's profile from this endpoint. `GET /_admin/summary` does expose profile and version counts, but it is a test-runner tool. Use it only to check a run's ground truth, never to make the controller's decisions.

### A busy device rejects a repeated Download

A device that is already downloading answers any further Download with `REJECTED`, reason `BUSY`, using the **same** commandKey. A duplicated inform can make the controller send the Download again while the first is still in progress. The controller treats a busy rejection as temporary, not as a failure; see [HLD.md](HLD.md).

### "Late" does not test what you might expect

`P_LATE` does **not** simply delay the `TransferComplete`. For a device that is not offline or rejecting, the code path is:

1. The device reboots (onto the target version for `healthy`; unchanged for `fault`).
2. It immediately sends a `BOOT` inform, **with no `transferComplete`**.
3. *Then* it sleeps an extra 60–600 s (`LATE_EXTRA_SEC`).
4. Only after that does it send the real `TransferComplete`.

Because the `BOOT` inform in step 2 is already conclusive evidence, the controller finishes the device upgrade before the delayed `TransferComplete` arrives:

- A `healthy` device is Succeeded (inferred from version). The later `TransferComplete` is trailing evidence.
- A `fault` device comes back on the old version, so it is Failed (rolled back). The later `TransferComplete` carries fault 9010, which the controller records next to the outcome without changing it.

**In practice:** `P_LATE` reliably exercises duplicate handling and trailing evidence on finished device upgrades. It rarely exercises *late evidence after a timeout*, because the early `BOOT` almost always finishes the device upgrade first. The state machine's unit tests cover that transition directly.

### Timing defaults line up with the controller's timeouts

| Simulator delay | Default | Controller wait it runs against | Default |
|---|---|---|---|
| Connection request → inform | 0.2–3 s | Wait for callback | 30 s |
| Download accept → acknowledgement | immediate | Wait for acknowledgement | 30 s |
| Download + reboot (worst case) | up to 20 + 240 = 260 s | Wait for result | 300 s |

The worst-case download-plus-reboot time (260 s) stays inside the default 300 s wait for a result, so a normal run should not spuriously time out. If you raise `REBOOT_MAX_SEC` or `DOWNLOAD_MAX_SEC`, raise the controller's result wait to match.

### Resource use at scale

Every device gets its own `asyncio` task for periodic informs, and all outbound calls share one `httpx.AsyncClient` capped at 500 connections. This handles 1k and 10k device runs comfortably. Much larger fleets would need a bigger host, a higher connection limit, or several simulator instances with disjoint device ids.

---

## 6. File map

| Path | What it is |
|---|---|
| `device_simulator.txt` (repo root) | The file as received. Left untouched for provenance. |
| `simulator/simulator.py` | Runnable copy of the same code. Only the module docstring's run instructions changed. |
| `simulator/requirements.txt` | `fastapi`, `uvicorn[standard]`, `httpx` |
| `simulator/Dockerfile` | Builds the image used by the `simulator` service in `compose.yaml` |
| `compose.yaml` | Runs `postgres` and `simulator`; the simulator defaults to `CONTROLLER_URL=http://host.docker.internal:8080` |

