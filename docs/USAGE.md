# Camera Driver Module — HTTP Endpoint Usage

All routes are mounted under `/data/camera-driver/*` (not `/main/data/...` as the SDK docs suggest).
Replace `192.168.153.128:8088` with your gateway address.

---

## Endpoint Reference

### GET /snapshot

Returns a single JPEG frame from a camera.

**Auth:** Required  
**Parameters:**

- `device` (required) — device name as configured in Ignition
- `profile` (required) — media profile token (e.g. `000`, `main_stream`)

```
GET /data/camera-driver/snapshot?device=FrontDoor&profile=000
```

**Responses:** `200 image/jpeg` · `400` missing params · `401` not authenticated ·
`404` device not found · `429` rate limited · `503` device not connected

**Coalescing:** concurrent requests for the same device+profile within 4 seconds share one
in-flight fetch. Only the first caller hits the camera; others wait and get the same result.

---

### GET /stream

Returns a live MJPEG stream.

**Auth:** Required  
**Parameters:**

- `device` (required) — device name
- `profile` (required) — media profile token
- `fps` (optional) — 1–30, default 10

```
GET /data/camera-driver/stream?device=FrontDoor&profile=000&fps=15
```

**Response:** `multipart/x-mixed-replace; boundary=camera-stream-boundary`

---

### GET /devices

Lists all configured camera devices with their current status.

**Auth:** Required

```
GET /data/camera-driver/devices
```

---

### GET /device/:name/status

Returns the current status of a single device.

**Auth:** Required

```
GET /data/camera-driver/device/FrontDoor/status
```

---

### GET /ptz/status

Returns the current PTZ position for a camera.

**Auth:** Required  
**Parameters:** `device`, `profile`

```
GET /data/camera-driver/ptz/status?device=FrontDoor&profile=000
```

---

### POST /ptz/move

Sends a PTZ absolute-move command.

**Auth:** Required  
**Body (JSON):** `{ "device": "FrontDoor", "profile": "000", "pan": 0.5, "tilt": 0.2, "zoom": 0.0 }`

```
POST /data/camera-driver/ptz/move
Content-Type: application/json
```

---

### POST /ptz/stop

Stops PTZ movement.

**Auth:** Required  
**Body (JSON):** `{ "device": "FrontDoor", "profile": "000" }`

```
POST /data/camera-driver/ptz/stop
Content-Type: application/json
```

---

### GET /health

Module health check. Returns JVM heap, device counts, go2rtc availability, and streaming concurrency.

**Auth:** Required

```json
{
  "status": "ok",
  "version": "3.0.7",
  "deviceCount": 3,
  "runningCount": 3,
  "onvifDeviceCount": 2,
  "genericCameraCount": 1,
  "ramUsedMb": 38,
  "ramTotalMb": 512,
  "ramPercent": 7,
  "cpuPercent": 4,
  "go2rtcAvailable": true,
  "activeSnapshots": 0,
  "activeStreams": 1
}
```

> **Note:** `ramUsedMb` / `ramPercent` reflect the **JVM heap** (module-relevant), not system RAM.

---

### GET /metrics

Per-camera resource metrics. Powers the Frigate-style Dashboard table.

**Auth:** Required

```json
{
  "jvm": { "heapUsedMb": 38, "heapMaxMb": 512, "heapPercent": 7 },
  "go2rtc": { "alive": true, "processMemoryMb": 62, "processMemoryKb": 63488 },
  "cameras": {
    "FrontDoor": {
      "go2rtcConsumers": 2,
      "go2rtcBitrateKbps": 1240,
      "go2rtcProducerState": "online",
      "go2rtcProducerTracks": ["video/H264", "audio/PCMA"],
      "snapshotLastDurationMs": 83,
      "snapshotLastTimestampMs": 1750540000000,
      "snapshotTotalFetches": 47,
      "snapshotErrors": 0,
      "status": "RUNNING",
      "onvifAvailable": true,
      "go2rtcRegistered": true
    }
  },
  "timestamp": 1750540001234
}
```

---

### GET /diagnostics

Full internal diagnostics — go2rtc process info, gateway thread count, streaming counters.

**Auth:** Required

---

### GET /auth-status

Safe to call without authentication. Returns `{"authenticated": true/false}`.

**Auth:** None (open route)

```http
GET /data/camera-driver/auth-status
```

---

### GET /logs/gateway

Recent gateway log lines filtered to camera-driver output.

**Auth:** Required

---

### GET /connection-browser

The React Connection Browser UI served as a web page. Shown in Gateway Config.

**Auth:** Open (Ignition Gateway Config requires its own login)

---

### GET /player

Embeddable video player page for use in Perspective IFRAMEs.

**Auth:** Open

---

## Authentication

All endpoints except `/auth-status`, `/connection-browser`, and `/player` require authentication.
Three methods are accepted:

**1. Gateway session** (standard for Perspective / Gateway Config)  
No extra parameters — the browser's session cookie is used automatically.

**2. Basic Auth** (external tools / curl)

```bash
curl -u admin:password \
  "http://192.168.153.128:8088/data/camera-driver/snapshot?device=FrontDoor&profile=000"
```

**3. API key** (programmatic access)

```http
GET /data/camera-driver/snapshot?device=FrontDoor&profile=000&apiKey=YOUR_KEY
```

**4. Perspective session token** (Perspective components only)  
Camera Viewer and Camera Grid components receive a short-lived token automatically from the
gateway-side `CameraComponentDelegate`. The token is passed in the `X-Camera-Token` header and
refreshes every ~90 seconds. No user configuration required.

---

## Rate limiting

- **600 requests/minute per source IP** — deliberately high to support Perspective polling many
  cameras simultaneously.
- Returns `HTTP 429` when the limit is exceeded.
- Proxy-aware: respects `X-Forwarded-For` and `X-Real-IP`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| ------- | ------------ | --- |
| 404 on all routes | Wrong URL prefix | Use `/data/camera-driver/`, not `/main/data/...` |
| 401 in Perspective | No session / token | Use Camera Viewer/Grid components (they handle auth); or add API key |
| 404 device not found | Name mismatch | Check exact name in Config → OPC UA → Device Connections (case-sensitive) |
| 503 device not connected | Camera unreachable | Verify network path; check device status in OPC Browser |
| 429 rate limited | Too many requests | Reduce polling interval, or consolidate components |
| Snapshot returns HTML | Camera error page passed through | Check camera credentials; JPEG-header validation rejects these but logs the error |

---

Last updated 2026-06-22 — version 3.0.7
