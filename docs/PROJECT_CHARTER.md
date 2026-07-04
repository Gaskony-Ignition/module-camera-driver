# Project Charter — Camera Driver Module

**Adopted:** 2026-07-04 · Purpose wording from the maintainer. Modelled on the
Python 3 module's charter.

This document is the authoritative statement of what this project is for, what
"done" means, and what will never be built. A release is justified **only** by
the Maintenance Policy below.

## 1. Purpose

SCADA is supervisory control and data acquisition — and in today's world, no
matter where SCADA is used, cameras are a key insight into what is going on.
Yet camera video traditionally lives in a separate VMS window, disconnected
from the SCADA screens operators actually watch.

**This module makes any commodity IP camera a first-class Ignition device**:
an operator sees live video and PTZ control on the same Perspective page as
the process data it relates to, and the camera's health, status, and
capabilities are OPC-UA tags that alarm and historise like any other device.

No vendor lock-in (standard ONVIF/RTSP/MJPEG/snapshot — any compliant camera),
no cloud dependency, no separate VMS licence: everything flows through the
gateway the plant already runs.

## 2. Definition of Done

The module is done when, on a clean Ignition 8.3 gateway:

1. Install signed `.modl` → unified `Camera` device type available, zero manual config
2. Add a camera by IP + credentials → ONVIF auto-detects streams/snapshot; or
   enter direct URLs with no ONVIF at all
3. Live video renders in a Perspective page via the module's endpoints,
   authenticated — WebRTC primary (sub-second, via bundled go2rtc/ffmpeg),
   with fMP4/MSE and snapshot fallbacks, all through the shared
   `CameraStreamEngine` client
4. PTZ works from Perspective against an ONVIF camera (correct service port!)
5. Device status/info/profiles appear as OPC-UA tags; connection loss alarms
6. Pre-v3.0.0 device profiles still load via the legacy alias type
7. All endpoints authenticated + rate-limited; XML parsing XXE-proof
8. The admin can verify a newly added camera — live view, snapshot, PTZ,
   status — entirely from the gateway Connection Browser, without the Designer

**Measured acceptance criteria** (agreed with the maintainer 2026-07-03; a
release may claim "Production Ready" only against a recorded run of these):

| # | Criterion | Target |
| --- | --------- | ------ |
| A1 | Time to first frame | ≤ 6 s |
| A2 | Steady-state video latency | ≤ 1 s via WebRTC (MSE fallback ≤ 3 s, keyframe-interval bound) |
| A3 | Smoothness | no freeze > 1.5 s over a 5-minute watch |
| A4 | PTZ press → camera physically moves | ≤ 1 s; visible in the stream ≤ 2 s |
| A5 | Resource hygiene | go2rtc consumers == open viewers; returns to 0 when all views close |
| A6 | Soak | 30 min at full load: zero 503s, zero stream restarts in logs |
| A7 | Scale | 10 cameras streaming simultaneously; gateway CPU/heap stable |
| A8 | OPC-UA | DeviceInfo / PTZ / Status tags live and updating |
| A9 | Docs | IMPLEMENTATION_STATUS current; "Production Ready" cites the acceptance run |

## 3. Maintenance Policy

After §2 passes, a release is justified only by: a defect in a §2 workflow, a
security issue, compatibility with a new Ignition version, or a deliberately
chosen candidate feature (one at a time).

## 4. Won't-Do list (permanent)

| Item | Why not |
| ---- | ------- |
| Video recording / NVR features (storage, timeline, playback archive) | That's a VMS; this is a live-view + device-data driver |
| Video analytics / motion detection / AI object detection | Cameras and dedicated systems do this; consume their outputs as tags instead |
| Proprietary vendor APIs (Hikvision/Dahua/Axis native SDKs) | Standard protocols only; vendor APIs are a support treadmill |
| Transcoding pipelines beyond the bundled go2rtc/ffmpeg passthrough | Resource sink on a gateway host |
| Cloud camera services (Ring, Nest, etc.) | On-prem SCADA scope only |

---

*Change to this charter requires the maintainer's explicit decision, recorded here with a date.*
