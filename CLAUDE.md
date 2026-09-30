# Claude Context - Ignition Camera Driver Module

Module-specific instructions. Shared standards are in `/modules/CLAUDE.md` and
`/modules/.claude/skills/`.

## Project Overview

**Language**: Java 17
**Framework**: Inductive Automation Ignition SDK 8.3.0
**Purpose**: Multi-protocol IP camera driver exposing a single unified `Camera`
device type, with per-device connection methods (RTSP, MJPEG, snapshot URLs,
ONVIF)

## Project Location

```text
/modules/ignition-module-camera-driver/
```

## Essential Reading Order

1. `docs/PROJECT_CHARTER.md` — purpose, definition of done, won't-do list;
   drives all release decisions
2. This file — architecture and gotchas
3. `/modules/.claude/skills/` — shared skills (building, reviewing, testing,
   security)
4. `SECURITY.md` — security architecture and practices
5. `README.md` — user-facing documentation
6. `docs/ARCHITECTURE.md` — diagrammed component/flow reference

## Building the Module

```bash
./gradlew clean build          # Build module
./gradlew clean build test     # Build and test
./gradlew test                 # Run tests only
```

## Architecture

The module registers a single unified `Camera` device type
(`CameraExtensionPoint`, type id `com.gaskony.camera.Camera`). Each device
supports multiple connection methods, enabled per device:

1. **RTSP** — direct video streaming (browser playback via bundled go2rtc + ffmpeg)
2. **MJPEG** — direct HTTP MJPEG streaming
3. **Snapshot** — HTTP JPEG snapshot capture
4. **ONVIF** — SOAP-based discovery, media profiles, PTZ, and auto-detection
   of stream/snapshot URLs

A hidden legacy alias type (`LegacyCameraExtensionPoint`, type id
`com.onvif.driver.Camera`) exists only so pre-v3.0.0 device profiles still
load and remain editable. It is not a separate device type for new use.

Module layout and the full component/sequence diagrams live in
`docs/ARCHITECTURE.md` — keep that doc current when you change a flow rather
than duplicating it here.

### Key components

- **CameraModuleHook** — the only entry point. Registers the device type (plus
  the hidden legacy alias), initialises the go2rtc/ffmpeg streaming manager,
  registers i18n resource bundles, mounts HTTP routes and Gateway Config
  navigation.
- **CameraDevice / CameraConfig / AddressSpaceBuilder** — device lifecycle,
  per-method configuration toggles, and the OPC-UA node hierarchy built from
  camera data.
- **onvif/** — SOAP layer: `ONVIFClient` (WS-UsernameToken auth, XXE-protected
  XML), `ONVIFPoller` (PTZ/status polling), data models.
- **generic/** — URL-based helpers for direct stream/snapshot URLs (no ONVIF
  needed).
- **Go2RtcManager** — RTSP-to-browser streaming via bundled go2rtc + ffmpeg:
  WebRTC (primary, signalling proxied through `WebRtcHandler`, media over ICE
  port 8555) with fMP4/MSE and snapshot fallbacks. Client side is one shared
  `CameraStreamEngine` (`web-ui/src/utils/`) used by Perspective components,
  the Gateway Config UI, and the static pages' player — never fork it per-scope.
- **CameraRoutes** — the `/data/camera-driver/*` HTTP API: snapshot, stream,
  device list, auth, rate limiting.

## Security Architecture

- **Authentication**: Gateway session, Basic Auth (5 failures → 15 min
  lockout), SHA-256 API keys, and a Perspective session-token handshake
  (`SessionTokenStore`) for browser-side components that carry no Gateway
  cookie.
- **Per-IP rate limiting**: deliberately high (600 req/min) to support
  Perspective polling many cameras; proxy-aware (`X-Forwarded-For`,
  `X-Real-IP`).
- **XML**: XXE protection and injection-safe escaping on all SOAP parsing —
  use `XmlUtil`, never parse ONVIF XML ad hoc.

## Critical Bugs to Avoid

### Display names show as "?...?"

**Cause**: Resource bundle not registered with `BundleUtil`.
**Fix**: Register in `CameraModuleHook.startup()`:

```java
BundleUtil.get().addBundle("Camera", CameraExtensionPoint.class, "Camera");
```

### Property file location

The properties file must sit at the exact package path:
`gateway/src/main/resources/com/gaskony/camera/gateway/device/Camera.properties`
— a mismatch fails silently rather than erroring.

## Working with This Project

### Before Making Changes

1. Load the relevant shared skills from `/modules/.claude/skills/`
2. Check `SECURITY.md` for security requirements
3. Check `docs/PROJECT_CHARTER.md` for what's in/out of scope

### Making Changes

1. Follow existing code patterns
2. Validate all user inputs
3. Escape XML properly (use `XmlUtil`)
4. Update tests if applicable
5. Update `CHANGELOG.md`

### Package and Class Names

Java packages are `com.gaskony.camera.*`. The module entry point and shared
routes are `CameraModuleHook` and `CameraRoutes`. Classes that genuinely
implement the ONVIF protocol keep the `ONVIF` prefix — `ONVIFClient`,
`ONVIFAuth`, `ONVIFService`, `ONVIFPoller`, plus the data models
`DeviceInformation`, `MediaProfile`, `PTZStatus` — because they describe the
protocol, not the package vendor. The module ID is `com.gaskony.camera.opcua`.
Pre-3.0.0 device profiles load via the hidden legacy alias type
`com.onvif.driver.Camera`.
