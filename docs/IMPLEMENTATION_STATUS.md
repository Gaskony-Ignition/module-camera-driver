# Implementation Status

**Project**: Ignition Camera Driver Module
**Current Version**: 3.1.1
**Last Updated**: 2026-07-03
**Status**: Release candidate — pending acceptance run (see PROJECT_CHARTER.md §2)

---

## Overview

The Ignition Camera Driver is a production-ready multi-protocol module for connecting to IP cameras.
It registers a single unified **Camera** device type that supports RTSP, MJPEG, snapshot URLs, and
ONVIF — all toggled per device.

---

## Feature Matrix

### Core camera protocols

| Feature | Status |
| ------- | ------ |
| ONVIF SOAP client (Profile S/T) | Complete |
| WS-UsernameToken authentication (SHA-1 digest) | Complete |
| Configurable SSL/TLS validation (STRICT / TRUST_FIRST_USE / INSECURE) | Complete |
| RTSP streaming via bundled go2rtc + ffmpeg | Complete |
| MJPEG direct streaming | Complete |
| HTTP snapshot capture | Complete |
| ONVIF auto-discovery of stream/snapshot URLs | Complete |
| PTZ status, continuous move (hold-to-move / release-to-stop), stop | Complete |
| ONVIF periodic polling (ONVIFPoller) | Complete |
| Auto-reconnect with exponential backoff | Complete |
| OPC-UA address space (DeviceInfo, MediaProfiles, PTZ, Status) | Complete |
| Legacy `com.onvif.driver.Camera` alias (pre-3.0 device configs) | Complete |

### HTTP endpoints

| Endpoint | Status |
| -------- | ------ |
| `GET /snapshot` — JPEG still with coalescing cache + fallback chain | Complete |
| `GET /stream` — Live MJPEG stream | Complete |
| `GET /devices` — All devices + status | Complete |
| `GET /device/:name/status` — Single device status | Complete |
| `GET /ptz/status` — Current PTZ position | Complete |
| `POST /ptz/move` — PTZ continuous move (query params, hold pattern) | Complete (fixed 3.0.10) |
| `POST /ptz/stop` — PTZ stop | Complete (fixed 3.0.10) |
| `POST /webrtc` — WebRTC SDP signaling proxy | Complete (3.1.0) |
| `GET /health` — Module health + JVM heap stats | Complete |
| `GET /metrics` — Per-camera resource metrics | Complete (3.0.7) |
| `GET /diagnostics` — Full internal diagnostics | Complete |
| `GET /auth-status` — Auth probe (open) | Complete |
| `GET /logs/gateway` — Recent gateway log lines | Complete |
| `GET /connection-browser` — React Connection Browser UI | Complete |
| `GET /player` — Embeddable Perspective video player | Complete |

### Security

| Feature | Status |
| ------- | ------ |
| Session authentication (Gateway WebUI) | Complete |
| Basic Auth with account lockout (5 failures → 15 min) | Complete |
| API key authentication (SHA-256 hashed) | Complete |
| Perspective session token auth (SessionTokenStore) | Complete (3.0.4) |
| Per-IP rate limiting (600 req/min) | Complete |
| Concurrency cap (50 concurrent snapshots) | Complete |
| XXE protection on all XML parsing | Complete |
| XML injection prevention | Complete |
| CORS exact-prefix matching | Complete |

### Perspective components

| Feature | Status |
| ------- | ------ |
| Camera Viewer component | Complete |
| Camera Grid component | Complete |
| CameraComponentDelegate (gateway-side token issuer) | Complete (3.0.4) |
| CameraAuthDelegate (client-side token holder + refresh) | Complete (3.0.4) |
| Token-gated fetch for `/snapshot` and `/stream` | Complete (3.0.4) |

### Streaming infrastructure

| Feature | Status |
| ------- | ------ |
| go2rtc v1.9.4 bundled binary | Complete |
| ffmpeg bundled binary | Complete |
| **WebRTC transport (sub-second latency, GOP-independent)** | Complete (3.1.0) |
| WebRTC ICE candidates: operator file or site-local auto-detect | Complete (3.1.0) |
| RTSP → MP4/fMP4 via go2rtc (video-only, avoids MSE A/V stalls) | Complete (3.0.14) |
| RTSP → MJPEG via ffmpeg | Complete |
| RTSP → single-frame JPEG via ffmpeg | Complete |
| go2rtc self-healing restart (5 attempts, exp backoff) | Complete |
| go2rtc API localhost-only binding + per-launch password | Complete (3.0.8) |
| Per-stream go2rtc consumer / bitrate / track metrics | Complete (3.0.7) |
| Stream lifetime cap (15 min) — route slots can never leak | Complete (3.0.13) |
| Unified client stream engine (WebRTC → MSE → snapshot), one implementation for Perspective + Gateway UI + static pages | Complete (3.1.0/3.1.1) |
| MSE live-edge pinning (bounded fallback latency) | Complete (3.0.14) |

### Snapshot coalescing cache (3.0.5)

| Feature | Status |
| ------- | ------ |
| In-flight coalescing via `CompletableFuture` (one fetch per device+profile) | Complete |
| Result TTL cache (4 seconds) | Complete |
| Per-camera fetch duration, timestamp, total, error tracking | Complete (3.0.7) |
| Broken-pipe write errors demoted to DEBUG (was WARN) | Complete |

### ONVIF reliability (3.0.6)

| Feature | Status |
| ------- | ------ |
| Manual 3xx redirect following for SOAP POST requests | Complete |

### Dashboard / UI

| Feature | Status |
| ------- | ------ |
| Connection Browser React app in Gateway Config | Complete |
| Status bar — CPU + JVM heap bars | Complete (3.0.7, was system RAM) |
| Dashboard stat cards (devices, running, ONVIF, generic) | Complete |
| Dashboard memory strip (JVM heap bar + go2rtc RSS) | Complete (3.0.7) |
| Dashboard per-camera metrics table (viewers, bitrate, tracks, snap latency, errors) | Complete (3.0.7) |
| PTZ pad in Cameras view inline preview (shown for PTZ-capable devices) | Complete (3.1.1) |

### Automated test suite

| Suite | Tests | Status |
| ----- | ----- | ------ |
| Gateway + common (JUnit 5, Mockito 5, AssertJ 3) | 535 | Passing |
| web-ui (vitest) | 22 | Passing |
| **Total** | **557** | **100% pass rate** |

Gateway coverage includes ValidationUtil, ONVIFAuth/Client, XmlUtil, handlers
(Stream/Snapshot/Ptz/WebRtc/Diagnostics), Go2RtcManager, AuthenticationManager,
SessionTokenStore. Security tests include XSS, SQL injection, XXE, Billion
Laughs, path traversal.

---

## Known Limitations

- **SHA-1 in WS-UsernameToken** — mandated by the ONVIF specification; cannot be changed.
- **Single-profile PTZ polling** — `ONVIFPoller` uses the first media profile only.
- **No ONVIF event subscriptions** — motion detection, tampering alerts not implemented.
- **No Profile G/M** — recording search/playback and metadata streaming not implemented.

---

## Version History

| Version | Date | Changes |
| ------- | ---- | ------- |
| 3.1.1 | 2026-07-03 | PTZ pad in the gateway Connection Browser (admin can verify PTZ without the Designer); static pages' player upgraded to WebRTC-first; documentation truth pass (endpoints, test counts, PTZ semantics) |
| 3.1.0 | 2026-07-03 | **WebRTC transport** — sub-second latency via authenticated `/webrtc` signaling proxy + go2rtc ICE (port 8555); unified client stream engine (WebRTC → MSE → snapshot) replacing duplicated MSE implementations |
| 3.0.14 | 2026-07-02 | Video-only fMP4 (drops AAC track that stalled MSE playback); MSE live-edge targets relaxed to sustainable values |
| 3.0.13 | 2026-07-01 | Stream connection leak fixed (15-min lifetime cap + explicit route concurrency + deterministic client teardown) — closes "Route concurrency limit reached" 503s |
| 3.0.12 | 2026-07-01 | MSE live-edge pinning applied in the Perspective components' own stream loop |
| 3.0.11 | 2026-06-29 | MSE live-edge pinning (Gateway Config UI player) |
| 3.0.10 | 2026-06-26 | PTZ routes registered as POST (were defaulting to GET → 404); PTZ verified end-to-end against a live camera |
| 3.0.9 | 2026-06-25 | ONVIF service parsing made namespace-prefix-agnostic; PTZ also detected from profile `PTZConfiguration`; interactive architecture diagram |
| 3.0.8 | 2026-06-23 | Security/robustness punch list: go2rtc API password, header-only tokens, same-host SOAP redirects, snapshot coalescing hardening, TRUST_FIRST_USE→STRICT fallback |
| 3.0.7 | 2026-06-22 | Per-camera resource metrics endpoint (`/metrics`); Dashboard memory strip + Frigate-style camera table; status bar now shows JVM heap (not system RAM); fixed health endpoint field names (`onvifDeviceCount`, `genericCameraCount`) |
| 3.0.6 | 2026-06-22 | ONVIF SOAP POST redirect following (fix PTZ on cameras that redirect); snapshot broken-pipe demoted to DEBUG |
| 3.0.5 | 2026-06-22 | Snapshot coalescing cache (one fetch per device+profile, 4s TTL) |
| 3.0.4 | 2026-06-15 | Perspective session token auth: SessionTokenStore, CameraComponentDelegate, CameraAuthDelegate — fixes "Authentication required" errors in Perspective |
| 3.0.3 | 2026-06-14 | Editable legacy devices, de-emphasise ONVIF, robust stream errors |
| 3.0.2 | 2026-06-13 | Hide standalone page header bar when logged out |
| 3.0.1 | 2026-06-12 | Minor fixes |
| 3.0.0 | 2026-06-10 | Package rename `com.onvif.driver` → `com.gaskony.camera`; legacy alias for pre-3.0 device profiles |
| 2.7.6 | 2026-02-11 | Version bump (development) |
| 2.7.5 | 2026-02-11 | Embed stored credentials in RTSP stream URLs for seamless browser playback |
| 2.7.3 | 2026-02-11 | Fix webpack entry export name for CameraConnectionBrowser |
| 2.7.2 | 2026-02-11 | Fix cross-module page collision — unique export name CameraConnectionBrowser |
| 2.7.1 | 2026-02-11 | Fix React iframe key prop for proper DOM destroy/recreate on navigation |
| 2.7.0 | 2026-02-11 | Resource diagnostics panel + embeddable Perspective player |
| 2.6.9 | 2026-02-11 | Restyle Connection Browser to match Ignition v8.3 gateway theme |
| 2.6.8 | 2026-02-11 | Fix MSE sourceopen race condition, strip charset from Content-Type |
| 2.6.7 | 2026-02-11 | Fix MSE codec mismatch — forward go2rtc Content-Type |
| 2.6.6 | 2026-02-11 | Implement MSE player for live fMP4 video streaming |
| 2.6.5 | 2026-02-11 | Switch to MP4 proxy from go2rtc |
| 2.6.4 | 2026-02-11 | Fix go2rtc API parameter (dst to name) for stream naming |
| 2.6.3 | 2026-02-11 | Fix getDeviceExtensionPoints() called before setup() |
| 2.6.2 | 2026-02-11 | Fix go2rtc startup timing race |
| 2.6.1 | 2026-02-11 | Fix Generic Camera profiles in Connection Browser, go2rtc Windows binary |
| 2.6.0 | 2026-02-10 | Multi-protocol rebranding (ONVIF Camera + Generic Camera) |
| 2.5.0 | 2026-02-10 | Module rename from "ONVIF Driver" to "Camera Driver" |
| 2.4.0 | 2025-12-11 | Production release: security audit, webpack CVE fix, documentation |
| 2.3.2 | 2025-11-26 | Connection Browser UI with device selector |
| 2.2.0 | 2025-11-24 | Production authentication with account lockout and SHA-256 API keys |
| 2.1.0 | 2025-11-22 | Authentication + testing: session, Basic Auth, API key, rate limiting, 168 tests |
| 2.0.0 | 2025-11-22 | Major security update: configurable SSL validation, environment credentials |
| 1.0.23 | 2025-11-22 | Clean ONVIF implementation with proper error handling |
| 1.0.0 | 2025-01-07 | Initial implementation (Phases 1–6) |

---

**Last Review**: 2026-07-03
