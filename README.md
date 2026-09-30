# Ignition Camera Driver Module

A single unified **Camera** device type for Ignition 8.3 that brings live video, PTZ control, and camera health straight into the platform — no separate VMS window, no vendor lock-in.

## Why this exists

SCADA is supervisory control and data acquisition — and in today's world, no
matter where SCADA is used, cameras are a key insight into what is going on.
Yet camera video traditionally lives in a separate VMS window, disconnected
from the SCADA screens operators actually watch. **This module makes any
commodity IP camera a first-class Ignition device**: live video and PTZ
control on the same Perspective page as the process data it relates to, and
camera health, status, and capabilities as OPC-UA tags that alarm and
historise like any other device. Standard protocols only (ONVIF, RTSP, MJPEG,
snapshot URLs) — no vendor lock-in, no cloud, no separate VMS licence.

The full purpose, definition of done, and permanent won't-do list live in
[docs/PROJECT_CHARTER.md](docs/PROJECT_CHARTER.md) — the charter drives every
release decision.

## What it looks like

Every camera pictured below is a synthetic RTSP test source stood up for this
README (see *How to use it*) — `DemoLoadingDock` and `DemoYardCam`. No physical
camera is required to reproduce any of it.

**Camera device detail** — the Connection Browser's device list, expanded. It
shows exactly what the module discovers per device: `DemoLoadingDock`'s and
`DemoYardCam`'s RTSP media profiles (H.264), each with Snapshot/Stream/Copy
URI actions. (ONVIF discovery — manufacturer/model/firmware/serial and
multi-profile enumeration — is a real capability of the module against ONVIF
hardware; it isn't pictured here because it needs physical ONVIF hardware to
demonstrate honestly.)

![Camera device list showing RTSP media profiles for both synthetic devices](docs/images/camera-devices-list.png)

**Gateway Live View grid** — the Connection Browser's built-in multi-camera
grid, actually streaming: the left tile is `DemoLoadingDock`'s synthetic feed
(colour bars, a moving marker, and a live timestamp overlay proving it isn't
a static image); the right tile is `DemoYardCam`, a second synthetic source
with a visibly different test pattern (SMPTE bars) so the two tiles are
clearly distinct.

![Gateway Live View grid with two tiles showing live synthetic test video and a live timestamp overlay](docs/images/gateway-live-view-grid.png)

**Perspective `CameraViewer` and `CameraGrid` components** — the same video,
now embedded in a Perspective page next to whatever process data it belongs
with. The top pane is a single `CameraViewer` bound to `DemoLoadingDock`; the
bottom pane is a `CameraGrid` showing both synthetic devices side by side,
proving the grid component streams multiple devices at once.

![Perspective view with a CameraViewer component on top and a two-camera CameraGrid below, both showing live video](docs/images/perspective-camera-viewer-grid.png)

**Designer-scope authoring** — `CameraViewer` and `CameraGrid` are ordinary
Perspective components in the Designer's own component palette (Camera Driver
category), draggable onto any view. The property editor shown here is a
`CameraViewer` dropped on a scratch view, exposing its real bindable
properties — `deviceName`, `mode`, `snapshotInterval`, `showOverlay`,
`objectFit`, `showSaveButton`, `showPtzControls` — the same as any built-in
component, with no special-casing needed to use them.

![Designer property editor for a CameraViewer component dropped on a scratch view, showing its deviceName, mode, snapshotInterval and other bindable properties](docs/images/designer-perspective-palette.png)

## What it does

| Method | Protocol | Capabilities |
| -------- | ---------- | ------------- |
| **RTSP** | RTSP | Direct video streaming, browser playback via bundled go2rtc + ffmpeg |
| **MJPEG** | HTTP MJPEG | Direct MJPEG video streaming |
| **Snapshot** | HTTP | JPEG snapshot capture from a still-image URL |
| **ONVIF** | ONVIF SOAP | Device discovery, media profile enumeration, PTZ control, and auto-detection of stream/snapshot URLs (Profile S/T) |

A single Camera device can use any combination of the above, enabled per
device. URLs for RTSP, MJPEG, and snapshot can be entered directly or
auto-detected via ONVIF when enabled.

- Single unified **Camera** device type with per-device connection methods
- Multi-protocol camera connectivity (RTSP, MJPEG, snapshot URLs, ONVIF)
- **Sub-second live video via WebRTC** (v3.1.0+), with automatic MSE → snapshot fallback
- PTZ control from Perspective and the gateway Connection Browser
- Authenticated HTTP endpoints (session auth, Basic Auth, API key, Perspective session tokens)
- Per-IP rate limiting (DoS protection)
- Bundled go2rtc and ffmpeg for RTSP-to-browser streaming — no external media server
- Hierarchical OPC-UA address space with camera data
- Connection Browser UI in Gateway Config (dashboard, live grid, diagnostics)
- Perspective `CameraViewer` and `CameraGrid` components
- Configurable SSL/TLS validation modes
- 550+ automated tests with 100% pass rate (gateway/common JUnit + web-ui vitest)

## How to use it

### 1. Install

Gateway web UI → **Config → System → Modules → Install or Upgrade a Module** →
upload the signed `.modl`. No gateway restart is required — the module
hot-loads.

**WebRTC note:** the bundled go2rtc listens for WebRTC media on port **8555**
(TCP+UDP). Host-network deployments need nothing. Docker *bridge*-network
deployments must publish `-p 8555:8555 -p 8555:8555/udp` and list the
host-reachable IP in `data/camera-driver/go2rtc/webrtc-candidates.txt` (one
`host:port` per line). If WebRTC cannot connect, playback silently falls back
to MSE.

### 2. Configure a device

1. Gateway → Config → OPC UA → Device Connections
2. Create new Device → Select **"Camera"**
3. Enter the camera IP address, username, and password
4. Enable the connection methods the camera supports (RTSP, MJPEG, Snapshot, ONVIF)
5. For RTSP/MJPEG/snapshot, either enter URLs directly or enable ONVIF to auto-detect them
6. Save and view tags in OPC Browser, or open the Connection Browser
   (`/data/camera-driver/connection-browser`)

Don't have a camera handy? The screenshots above were captured against
synthetic RTSP sources — no hardware required to try the module:

```bash
scripts/demo-cameras.sh up       # two synthetic cameras
scripts/demo-cameras.sh status   # running? is anything actually watching?
scripts/demo-cameras.sh down     # finished — leaves nothing behind
```

That publishes `rtsp://127.0.0.1:8556/cam1` (test pattern) and
`rtsp://127.0.0.1:8556/cam2` (colour bars, so a two-tile CameraGrid is
obviously showing two cameras). Port 8556 rather than 8554 because the
gateway's own bundled go2rtc owns 8554 when the gateway is host-networked, and
the collision shows up as a stream that never opens rather than a port error.

Then create a Camera device as above with RTSP enabled, Snapshot/MJPEG/ONVIF
disabled, and **RTSP URL Override** set to `rtsp://127.0.0.1:8556/cam1` (and,
for a second device, `rtsp://127.0.0.1:8556/cam2`).

One container holds the whole demo, and encoding runs only while something is
reading a stream. **Run `down` when you are done** — an idle encoder otherwise
keeps running indefinitely.

### 3. Put it on a Perspective page

Drag a `CameraViewer` (single camera) or `CameraGrid` (multi-camera) component
onto a view and set `deviceName` (or `cameras: []` for the grid) to the device
name(s) created above. See **[docs/USAGE.md](docs/USAGE.md)** for the full
HTTP endpoint and component reference, and
**[docs/TESTING.md](docs/TESTING.md)** for complete testing instructions.

---

## Configuration Fields

All fields belong to the single **Camera** device type.

**General**: Device Name, Enabled

**Connection**: IP Address, Port, Username, Password, Use HTTPS, Connection Timeout, SSL Validation Mode

**Streams** (connection methods): RTSP Stream, MJPEG Stream, Snapshot, ONVIF / PTZ, plus optional RTSP URL / Snapshot URL / MJPEG URL overrides

**Advanced**: Poll Interval (ONVIF only)

## HTTP Endpoints

All endpoints are at `/data/camera-driver/*` and require authentication.

| Endpoint | Description |
| ---------- | ------------- |
| `/data/camera-driver/snapshot?device=X&profile=Y` | JPEG snapshot |
| `/data/camera-driver/stream?device=X&profile=Y&fps=Z` | Live stream (fMP4/MJPEG fallback chain) |
| `POST /data/camera-driver/webrtc?device=X` | WebRTC SDP signaling (offer in, answer out) |
| `POST /data/camera-driver/ptz/move?device=X&pan=&tilt=&zoom=` | PTZ continuous move (hold) |
| `POST /data/camera-driver/ptz/stop?device=X` | PTZ stop (release) |
| `/data/camera-driver/ptz/status?device=X` | Current PTZ position |
| `/data/camera-driver/devices` | List all devices |
| `/data/camera-driver/device/:name/status` | Device status |
| `/data/camera-driver/connection-browser` | Connection Browser UI |
| `/data/camera-driver/health` | Health check |
| `/data/camera-driver/metrics` | Per-camera resource metrics |

## Current Status

**Version**: 3.3.1 | **Status**: Production Ready

The gateway UI, the standalone dedicated page and the Perspective `CameraViewer`/`CameraGrid` components meet WCAG 2.1 AA, with one recorded exception in [a11y.json](a11y.json): Chromium never draws a focus ring on the standalone page's content frame once it holds a loaded document.

## Documentation

| Document | Description |
| ---------- | ------------- |
| [CHANGELOG.md](CHANGELOG.md) | Complete version history |
| [docs/PROJECT_CHARTER.md](docs/PROJECT_CHARTER.md) | Purpose, definition of done, won't-do list |
| [/modules/.claude/skills/](../.claude/skills/) | Shared skills across all modules |
| [docs/USAGE.md](docs/USAGE.md) | HTTP endpoint usage guide |
| [SECURITY.md](SECURITY.md) | Security architecture |
| [docs/TESTING.md](docs/TESTING.md) | Testing guide |
| [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) | Known issues |

## Camera Resources

### RTSP / Streaming

- **go2rtc**: <https://github.com/AlexxIT/go2rtc> (bundled for RTSP-to-browser streaming)

### ONVIF

- **ONVIF Official**: <https://www.onvif.org/>
- **ONVIF Specifications**: <https://www.onvif.org/profiles/>

## Building

```bash
./gradlew clean build        # Build module
./gradlew test               # Run tests
./gradlew clean build test   # Build and test
# Output: build/CameraDriver-3.3.1.modl
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

Copyright (c) 2025 Nigel Gwork — [@nigelgwork](https://github.com/nigelgwork)

- **GitHub**: <https://github.com/Gaskony-Ignition/ignition-module-camera-driver>
- **Issues**: <https://github.com/Gaskony-Ignition/ignition-module-camera-driver/issues>
- **Security Policy**: See [SECURITY.md](SECURITY.md)
