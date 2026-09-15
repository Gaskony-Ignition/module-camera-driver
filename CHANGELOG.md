# Changelog

All notable changes to the Ignition Camera Driver module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.3.0] - 2026-07-31

**Type:** MINOR — Apache HttpClient 4 → 5 migration (shipped dependency set changes materially)

### Changed

- **Migrated all HTTP client code from Apache HttpClient 4.5.14 / HttpCore 4.4.16 to
  HttpClient5 5.6.2 / HttpCore5 5.4.3** (`modlImplementation`, matching the version already
  proven in production by `ignition-module-git`). Every `org.apache.http.*` reference in the
  module is gone — `ONVIFClient` (SOAP client), `CameraDevice` (stream-discovery probing),
  `Go2RtcManager` (go2rtc REST API + stream proxying), `StreamHandler` (MJPEG proxy/stream),
  `WebRtcHandler` (WebRTC SDP signaling proxy), and `GenericCameraClient` (snapshot/MJPEG HTTP
  client) all now use `org.apache.hc.client5.http.*` / `org.apache.hc.core5.http.*`.
- **Every timeout carried over at the same duration**, split across HttpClient 5's two
  timeout homes (`RequestConfig.setConnectTimeout()` is deprecated in 5.x and does nothing on
  a pooling connection manager): connect and socket/data-wait timeouts now live on
  `ConnectionConfig` (applied via `PoolingHttpClientConnectionManagerBuilder`), while
  `RequestConfig` keeps the connection-lease wait (`connectionRequestTimeout`) and the
  response wait (`responseTimeout`, replacing the old `socketTimeout`). Affected: ONVIF
  client (operator-configured timeout, both connect+response), device stream-discovery probes
  (`PROBE_TIMEOUT_MS` = 1000ms), go2rtc API calls (`HTTP_TIMEOUT_MS` = 5000ms),
  `StreamHandler` proxy (5s connect / 30s response), `WebRtcHandler` signaling (5s connect /
  10s response), and `GenericCameraClient` (operator-configured timeout).
- **All three `sslValidationMode` behaviours (STRICT / TRUST_FIRST_USE / INSECURE) ported
  unchanged.** Custom TLS now goes through the connection manager as a `TlsSocketStrategy`
  (`DefaultClientTlsStrategy`) instead of a client-builder-level socket factory.
  `NoopHostnameVerifier` (INSECURE) and the JVM default hostname verifier via
  `HttpsSupport.getDefaultHostnameVerifier()` (STRICT) moved package but are otherwise the
  same classes; TRUST_FIRST_USE still falls back to STRICT with the same warning (it was
  never implemented as certificate pinning). No mode was weakened.
- **`StreamHandler`'s MJPEG/fMP4 proxy streaming was ported mechanically, not restructured**:
  it still opens a `ClassicHttpResponse`/`HttpEntity` and reads `entity.getContent()` manually
  on the same bounded write-thread-with-timeout path, so entity consumption and connection
  release happen at exactly the same points as before. `Go2RtcManager`'s stream-management
  API calls (add/remove/fetch/health-check) got the same faithful, non-restructuring port.
- `EntityUtils.toString(...)` now also throws checked `ParseException`
  (`org.apache.hc.core5.http.ParseException`, not an `IOException` subtype): wrapped into
  `IOException` in `ONVIFClient.sendSoapRequest()` to preserve its existing `throws IOException`
  contract, and added to `WebRtcHandler`'s existing upstream-failure catch (already handled
  transparently by `Go2RtcManager.getStreamInfo()`'s pre-existing broad `catch (Exception)`).
- `UsernamePasswordCredentials` now takes a `char[]` password and `AuthScope.ANY` no longer
  exists — replaced with `new AuthScope(null, -1)` (matches any host/port, the documented 5.x
  equivalent) across `ONVIFClient`, `CameraDevice`'s probe client, and `GenericCameraClient`.
- Removed `HttpUriRequestBase`'s `releaseConnection()` calls (no longer exists in 5.x) in
  favour of try-with-resources on the response, which is HttpClient 5's mechanism for
  releasing a pooled connection — equivalent-or-safer, not a semantics change.
- `ONVIFClientTest` — removed five dead `org.apache.http.*` imports left over from an earlier,
  since-simplified mocking attempt; they were unused by any test body (verified before removal).

### Verified

- **Live acceptance against the real FrontPTZ camera** (REOLINK RLC-823A 16X, ONVIF on
  port 8000), not just unit tests — these are the paths unit tests cannot reach:
  `GetDeviceInformation` returned manufacturer/model/firmware/serial, which requires a
  full SOAP round trip *with* WS-UsernameToken digest auth to have succeeded on the new
  stack; `GetProfiles` returned 2 real media profiles; PTZ capability detected; and
  `/stream` served live fMP4 (`ftyp`/`moov`/`moof`/`mdat` boxes, `video/mp4;
  codecs="avc1.640029"`) proxied through `StreamHandler` from go2rtc — the one path where
  entity-consumption and connection-release timing changed in HttpClient 5, and the one a
  snapshot fetch never exercises. go2rtc itself came up alive from the bundled binaries
  (port 1984, 0 restarts).
- **Snapshot returns HTTP 500 "No snapshot source available"** on this device — and that
  is NOT a regression from this migration. Confirmed by installing 3.2.1 (the HttpClient
  4 build) on the same gateway against the same device config and getting a byte-for-byte
  identical failure. The device has `enableSnapshot: true` with no snapshot URL
  configured, and the module does not call ONVIF `GetSnapshotUri` to discover one. That
  is a genuine pre-existing gap, tracked separately — it predates this work and was not
  fixed here, because a dependency migration is the wrong place to change behaviour.

- **Packaging verified by unzipping the built `.modl`**: `httpclient5-5.6.2.jar` and
  `httpcore5-5.4.3.jar` are present; `httpclient-4.*.jar`, `httpcore-4.*.jar`,
  `commons-logging-*.jar`, and `commons-codec-*.jar` are gone — HttpClient 5 has no runtime
  dependency on the latter two, so dropping them is a genuine (if small) reduction in the
  module's shipped surface, not just a version bump.

## [3.2.1] - 2026-07-30

**Type:** PATCH — dependency audit (compileOnly/modlImplementation/testImplementation review)

### Changed

- **Gson moved from `compileOnly` 2.11.0 to `modlImplementation` 2.14.0 (latest stable).** The
  only real `com.google.gson` usage in the module (`ApiKeyStore`) stays fully internal — a JSON
  string is read/written to a file, no `Gson`/`JsonElement`/`JsonObject` ever crosses an Ignition
  SDK API boundary — so shipping our own current copy is safe and avoids being pinned to the
  gateway's bundled 2.8.9 (2021-era). `compileOnly` at 2.11.0 was already ahead of the platform's
  actual bundled 2.8.9, which is the real landmine this audit was chasing: declaring newer than
  what the gateway loads compiles fine and then throws `NoSuchMethodError` at runtime the first
  time a post-2.8.9 API is touched. `CameraComponentDelegate`'s use of
  `com.inductiveautomation.ignition.common.gson.JsonObject` is Ignition's own shaded/relocated
  Gson and is unaffected either way. Precedent: `ignition-module-git` already ships its own newer
  sqlite-jdbc/Jackson the same way. Packaging verified by unzipping the built `.modl` —
  `gson-2.14.0.jar` (313KB) plus its `error_prone_annotations-2.48.0.jar` (20KB) are genuinely
  present, not merely declared.
- **`junit-jupiter` (test-only) bumped 5.11.3 → 5.14.4** (latest stable 5.x). JUnit 6.x was
  evaluated and deliberately not taken: `mockito-junit-jupiter` 5.23.0 — the latest stable
  Mockito — still targets the JUnit 5 platform, so moving Jupiter to 6.x would split the test
  stack across two platform generations. Revisit when Mockito ships JUnit 6 support.
- **`junit-platform-launcher` (test-only) pinned at 1.14.4** (new declaration). Gradle 8.10.2
  bundles a launcher too old for Jupiter ≥5.12 and test discovery fails with
  `OutputDirectoryCreator not available; probably due to unaligned versions` — the pin is
  mandatory, not cosmetic.
- **`mockito` (test-only) bumped 5.18.0 → 5.23.0** (latest stable 5.x).
- **`slf4j` (test-only here — not used `compileOnly` anywhere in this module) bumped 2.0.17 →
  2.0.18** (latest stable 2.0.x).
- `assertj` (test-only) reviewed — already at 3.27.7, the latest stable 3.x release; no change.
- `jakarta-servlet` (`compileOnly`) reviewed — left at 5.0.0. See report/commit notes: a 6.0.0
  attempt was evaluated but not carried, since this module's servlet types (`HttpServletRequest`/
  `HttpServletResponse` etc.) are handed to it directly by Ignition's Jetty-based routing, i.e.
  a genuine SDK-boundary crossing where `compileOnly` must never exceed what the platform
  actually loads (6.0.0 on the gateway scope) — 5.0.0 is safely older, not a bug.
- `httpclient`/`httpcore` — untouched; superseded by a separate HttpClient 4→5 migration.
- `ignition` SDK version (8.3.0) — untouched, out of scope.

## [3.2.0] - 2026-07-30

**Type:** MINOR — module now opts in to Ignition Maker Edition

### Added
- **`CameraModuleHook` now overrides `isMakerEditionCompatible()` to return `true`.**
  `AbstractDeviceModuleHook` (like every `AbstractGatewayModuleHook` subclass,
  including device drivers) defaults this to `false`, so without the override
  Maker Edition silently refuses to start the module and reports it as "not
  eligible for use with Ignition Maker Edition" — no fault, no other log line.
  Verified live on Maker 8.3.8 (see forum thread linked in the override's
  Javadoc). Free module; no device-driver behaviour identified that would
  misbehave under Maker's licensing. New capability, hence a minor bump rather
  than a patch. Regression test: `CameraModuleHookMakerEditionTest`.

## [3.1.9] - 2026-07-06

**Type:** PATCH — pre-release review fixes (adversarial review of 3.1.4–3.1.8)

### Fixed
- **WebRTC streams now recover from a mid-stream connection drop.** Once a WebRTC stream reached 'playing', a later connection failure (Wi-Fi rebind, go2rtc hiccup) did nothing — frozen frame behind a LIVE badge, dead RTCPeerConnection leaked. The engine now handles post-playing state changes: 'failed'/'closed' are immediately fatal, 'disconnected' gets a 5 s grace period; a confirmed drop falls through to MSE in auto mode, or retries WebRTC (3 attempts, backed off) in explicit mode. The retry budget only restores after 10 s of sustained health.
- **Grid resize or group switch no longer replays the last Stream All / Stop All.** Grid cells remount on resize/group change and treated the already-applied bulk command as new, silently auto-starting every visible camera. Cells now treat the mount-time command sequence as already applied (regression test added).
- **A stale cell assignment to a deleted/renamed camera no longer blocks auto-fill.** The cell is treated as empty until the device reappears; the stored assignment is preserved so a returning camera regains its place.
- **Transient HTTP errors (e.g. 503 during a gateway restart) on MSE connect now get the bounded reconnect** instead of failing immediately; 401/404 remain immediate, as those are permanent misconfigurations.
- **The MSE reconnect budget no longer resets on a one-frame flicker** — only after 10 s of sustained playback, so a connect-play-stall loop can't retry forever at the fastest backoff tier.
- **Stream-write executor is now shut down with the module** (was a static pool that outlived module shutdown, pinning the classloader on redeploy) and lazily recreated on module restart; a request racing shutdown fails cleanly.
- **Abandoned stream-writer threads are now counted and capped** (ceiling 16): each write-stall event pins one uninterruptible thread, and unbounded accumulation could exhaust JVM native threads. At the ceiling, new proxied streams are refused with a clear log message until the count drops; threads that eventually complete are un-counted.
- **`ice_servers: []` is now always emitted** in the go2rtc config (previously skipped when no WebRTC candidates were detected — exactly the constrained-network case where the STUN stall bites hardest).
- **`MediaProfile` results are deep-copied** from the device cache (`peekMediaProfiles()` and `getMediaProfilesCached()`), closing a shared-mutable-state hazard.

---

## [3.1.8] - 2026-07-06

**Type:** PATCH — MSE stream proxy consumer leak (found by the 10-camera soak)

### Fixed
- **The gateway leaked upstream go2rtc consumers on the MSE streaming path.** A 30-minute 10-camera soak showed each MSE-viewed camera accumulating ~5 stale go2rtc consumers — connections the gateway kept feeding long after the browser had gone. Root cause: `StreamHandler.proxyStream()`'s write loop blocks in `output.write()` when a client goes away *without* a clean TCP close (network drop, laptop sleep, blackholed connection); a blocking Java write can't be force-unblocked from another thread, so the upstream connection was held until the 15-minute hard cap. A clean disconnect (tab close, or the MSE engine's reconnect) was already detected within ~1 s and is unaffected. Each write now runs on a dedicated thread bounded by a 20 s stall timeout; on timeout the loop closes the upstream go2rtc/MJPEG connection immediately (go2rtc reaps its consumer within ~6 s of the close), so orphaned consumers can no longer accumulate. WebRTC was never affected (ICE reaps dead peers itself).

---

## [3.1.7] - 2026-07-06

**Type:** PATCH — Live View "All Cameras" auto-populates

### Fixed
- **"Stream All" only started the handful of cameras manually assigned to grid cells.** With the built-in "All Cameras" group selected, empty cells stayed on "-- Select Camera --" and Stream All ignored every unassigned camera, so connecting 10 cameras still showed a mostly empty grid (reported three times). The "All Cameras" group now auto-populates: every connected device that isn't already placed is filled into the next empty cell in stable alphabetical order, up to the grid's capacity, and Stream All / Snapshot All operate over that effective set. Manual placements are preserved; a cell the admin explicitly blanks stays blank (tracked with an internal cleared-cell marker) rather than being re-filled. Auto-fill is display-only and re-derived each render, so a newly-connected camera appears immediately and resizing the grid re-flows — nothing is written back to storage. Custom (named) groups are unchanged and remain manual-assignment only.

---

## [3.1.6] - 2026-07-06

**Type:** PATCH — WebRTC always fell back to MSE; device list still timed out on a cold ONVIF cache

### Fixed
- **WebRTC never negotiated — every stream silently fell back to MSE.** The bundled go2rtc waits for ICE gathering to finish before answering an SDP offer, and its default config contacts Google's public STUN server (`stun.l.google.com:19302`). On the LAN/NAT test environment that round trip intermittently stalled at 5–10 s (measured directly against `/api/webrtc`: responses clustered at ~5.0 s and ~10.0 s vs ~20–40 ms when STUN was skipped), exceeding `WebRtcHandler`'s 10 s proxy read timeout so signalling timed out on every attempt. Since the module already advertises explicit host candidates (operator file or auto-detected site-local addresses), STUN adds nothing: `Go2RtcManager` now emits `ice_servers: []` under `webrtc:` whenever candidates are configured, disabling STUN gathering. Signalling now completes in milliseconds and WebRTC connects instead of degrading to MSE. This also makes the implementation match SECURITY.md's claim that no negotiation traffic leaves the local network.
- **`/devices` could still time out right after a gateway restart.** The 3.1.4 fix cached ONVIF device info/profiles/URIs but the accessors still fell back to a *live* SOAP call on a cache miss; with 10 devices and a cold cache (e.g. gateway just restarted while the camera was busy), one list request could still fan out into dozens of serial round-trips and exceed the UI's fetch timeout. `DeviceApiHandler.handleListDevices()` now uses new peek-only accessors on `CameraDevice` (`peekDeviceInformation()`, `peekMediaProfiles()`, `peekStreamUri()`, `peekSnapshotUri()`) that read the cache and never touch the network; a cold field is simply omitted from the JSON and fills in once the device's cache warms up. The single-device status endpoint (`handleDeviceStatus`) is unchanged and may still lazily fetch once, which is acceptable for one device.

---

## [3.1.5] - 2026-07-04

**Type:** PATCH — Live View bulk controls

### Fixed
- **"Stream All" / "Stop All" in Live View did nothing.** Both handlers were stubs that only wrote a line to the event log. They now broadcast a sequenced command that every grid cell applies once — starting all assigned, running, not-already-streaming cameras (or stopping them). Found during the 10-camera acceptance run.
- **Grid cell play/stop icon and LIVE badge never updated.** The cell's streaming flag lived in a non-rendering ref; it is now React state (mirrored in a ref for timer callbacks), so the ▶/■ toggle and LIVE badge reflect reality.

---

## [3.1.4] - 2026-07-04

**Type:** PATCH — device list scalability (found by the 10-camera acceptance test)

### Fixed
- **`/devices` timed out with 10 devices ("Failed to load devices: signal timed out").** The device-list and device-status handlers made *live* ONVIF SOAP calls per device per request — device info, media profiles, and stream+snapshot URIs for every profile (~8 round-trips each). At 1 device this was invisible; at 10 devices against one camera (already servicing 10 RTSP sessions and 10 pollers), a single page load serialised ~80 SOAP calls and blew past the UI timeout. `CameraDevice` now caches device information, media profiles, and per-profile URIs — populated at ONVIF connect, lazily filled for URIs, cleared on reconnect/close — and `DeviceApiHandler` serves from that cache, so listing devices costs zero camera round-trips in steady state. The address-space builder reuses the same cache, trimming redundant startup calls too.

---

## [3.1.3] - 2026-07-04

**Type:** PATCH — Dedicated Page boot fix

### Fixed
- **Dedicated Page showed "CameraConnectionBrowser export was not found or is not a component."** The 3.1.2 shell resolved the UMD global as `exported.default || exported`, but `web-ui/src/index.ts` uses a *named* export (`export { default as CameraConnectionBrowser }`), so the global is a namespace object with the component at `.CameraConnectionBrowser` — the same export name the embedded Config view's SystemJS mount selects. The shell now resolves the named export first (with `.default`/direct-function fallbacks). The readable in-page error worked exactly as designed and made this a one-line diagnosis.

---

## [3.1.2] - 2026-07-03

**Type:** PATCH — Dedicated Page now renders the real dashboard

### Fixed
- **The Dedicated Page showed an outdated dashboard.** The standalone view iframed a legacy self-contained vanilla-JS page (pre-3.0.7: no JVM-heap/go2rtc memory strip, no Camera Resources table, system-RAM status bar). That 130 KB page is replaced by a 12 KB shell that auth-gates and then loads the **same React app** the embedded Gateway Config view uses (`connectionBrowser.js`, with pinned React 18.3.1 UMD served from the module — still fully self-contained, no CDN). Both entry points now render one dashboard and can never drift apart again. Script/bundle failures show a readable in-page error instead of a blank page.

---

## [3.1.1] - 2026-07-03

**Type:** PATCH — release-readiness review: gateway UI PTZ, static player parity, documentation truth pass

### Added
- **PTZ pad in the gateway Connection Browser** (Cameras view inline preview, shown only for PTZ-capable devices). Completes the admin workflow: a newly connected camera can now be fully verified — live view, snapshot, PTZ, status — from the gateway, without opening the Designer. Press-and-hold continuous move, zoom in/out, stop; first failed command surfaces a toast.

### Changed
- **Static pages' player upgraded to WebRTC-first.** `/player` and the standalone/dedicated Connection Browser page shared a stale MSE-only player with none of the leak/latency fixes; it now negotiates WebRTC first (same `/webrtc` signaling), falls back to MSE with live-edge pinning, and carries the generation-guarded teardown. Public `MsePlayer` API unchanged, so the pages needed no edits.

### Fixed (documentation truth pass)
- WebRTC documented across README, USAGE (new `POST /webrtc` section), SECURITY (port 8555 threat model), CLAUDE.md, and the interactive architecture diagram.
- `docs/USAGE.md` PTZ documentation was **wrong** — it described a JSON request body; the endpoints take query parameters (and are ContinuousMove, not absolute move). Corrected with the press-and-hold pattern.
- `docs/IMPLEMENTATION_STATUS.md` was frozen at 3.0.7 — brought current (3.0.8→3.1.1 history, WebRTC/leak/latency features, PTZ semantics).
- Stale "168 tests" claim corrected to the real counts (535 gateway/common JUnit + 22 web-ui vitest) in all five places.
- README: broken `docs/SECURITY.md` link fixed; install instructions no longer say `docker restart` (module hot-loads via the Gateway web UI); WebRTC/8555 deployment note added.
- `docs/PROJECT_CHARTER.md` §2 gains the measured acceptance criteria (A1–A9: ≤1 s WebRTC latency, 10-camera scale, soak, resource hygiene) and the admin-verification workflow as done-criterion #8.

---

## [3.1.0] - 2026-07-03

**Type:** MINOR — WebRTC transport for sub-second live video + unified stream engine

### Added
- **WebRTC live-video transport (primary).** New `POST /data/camera-driver/webrtc?device=<name>` route proxies the SDP offer/answer exchange to the bundled go2rtc (which stays bound to localhost); media then flows browser ↔ go2rtc over ICE on port 8555. WebRTC does not use an MSE `SourceBuffer`, so steady-state latency is network + decode (~0.3–0.8 s) regardless of the camera's keyframe interval — the GOP now only affects join time. This meets the ≤1 s latency acceptance target that MSE structurally could not on long-GOP cameras.
- go2rtc config now includes a `webrtc:` block — listener on `:8555` and ICE candidates from an optional operator file (`data/camera-driver/go2rtc/webrtc-candidates.txt`, one `host:port` per line) or auto-detected site-local IPv4 addresses. Docker deployments on bridge networks must publish 8555/tcp+udp and set the candidates file; host-network deployments need nothing.
- `mode` property on Camera Viewer / Camera Grid gains `webrtc`; `auto` now tries **WebRTC → MSE → snapshot**.

### Changed
- **One stream engine, everywhere.** The two duplicated MSE implementations (`MsePlayer.ts` used by the Gateway Config UI, and the inline loop in `useCameraStream` used by Perspective) are replaced by a single `CameraStreamEngine` used by all four call sites (Camera Viewer, Camera Grid, GridCell, InlineStream). The engine carries the generation-counter teardown (3.0.13) and MSE live-edge pinning (3.0.14), and guarantees WebRTC/MSE/snapshot attachment points are all reset on stop/switch. This removes the duplication that caused the 3.0.11 fix to land in dead code.

### Tests
- Gateway: `WebRtcHandlerTest` (auth, validation, 404, missing body, go2rtc-down 502) + Go2RtcManager webrtc-config tests — 468 gateway tests passing.
- web-ui: engine fallback-ordering tests — 22 vitest tests passing.

---

## [3.0.14] - 2026-07-02

**Type:** PATCH — video-only stream to stop periodic MSE freeze-and-jump

### Fixed
- **Perspective video froze and jumped every ~8 s.** The gateway proxied go2rtc's fMP4 with **both** the H.264 video track and the camera's AAC audio track. A browser appends that muxed stream into a single MSE `SourceBuffer`, where the playable range is the *intersection* of the audio and video buffered ranges — when the two tracks buffer unevenly (they do here: 10 fps video vs 16 kHz AAC), playback advances in chunks and stalls, producing the multi-second freeze-and-jump observed while watching the on-screen clock. The gateway now requests a **video-only** stream (`stream.mp4?src=…&video=h264,h265`); with a single track there is no A/V intersection to stall on, and it lowers latency and bandwidth. Camera monitoring does not use the audio track.
- **Live-edge targets relaxed for smoothness.** The 3.0.12/3.0.13 live-edge pin aimed for 0.5 s latency, which a 10 fps / 4 s-GOP stream cannot sustain without starving the decoder. It now targets ~1.5 s of buffer and only hard-seeks when more than 4 s behind — low latency without re-introducing stalls.

### Notes
- Diagnosed with the bundled ffmpeg against the live go2rtc output: video PTS are monotonic ~10 fps; the muxed A/V interleave (and the browser's single-SourceBuffer intersection) was the stall source, not the video timestamps themselves.

---

## [3.0.13] - 2026-07-01

**Type:** PATCH — stream connection leak → route concurrency exhaustion (503) + reconnect stutter

### Fixed
- **Streams leaked route concurrency slots, eventually 503-ing all viewers.** Diagnosed on the live gateway: a single Perspective component instance left **5 frozen go2rtc consumers** that never closed. When the browser navigates away or the component reconnects, it did not always close its old stream connection; the gateway, blocked writing a low-bitrate feed into an OS socket buffer, never saw the disconnect, so `handle()` never returned and its route concurrency slot (and the module's stream counter) leaked permanently. Accumulated leaks hit Ignition's per-route concurrency cap → `503 "Route concurrency limit reached"` for new viewers, and starved/among competing streams caused the ~8 s freeze-and-jump seen in the Designer.
  - **Gateway:** every proxied/polled stream now has a hard `MAX_STREAM_DURATION_MS` (15 min) lifetime, so a handler always returns and frees its slot even if a client vanishes; a still-watching client transparently reconnects.
  - **Gateway:** the `/stream` and `/snapshot` routes now declare explicit `concurrency(64, 16)` headroom instead of the framework default (tuned for short request/response), which is far too low for a multi-camera Perspective dashboard.
  - **Client:** `useCameraStream` now uses a generation counter and explicit reader cancellation, so a reconnect/remount deterministically tears down the previous stream (cancels the reader, closes the connection) instead of leaving an orphaned reader draining the feed.

---

## [3.0.12] - 2026-07-01

**Type:** PATCH — PTZ/live-latency fix applied to the correct player

### Fixed
- **The 3.0.11 live-edge fix was in a file the Perspective view never runs.** `MsePlayer.ts` (edited in 3.0.11) is only used by the Gateway Config UI preview; the Perspective `CameraViewer`/`CameraGrid` components use a *separate inline* MSE reader loop in `useCameraStream`, which had no live-edge control. So the Perspective view still drifted seconds behind the live edge and, sitting in stale buffer, periodically resynchronised with a visible freeze-and-jump (observed ~8 s cadence). The live-edge pinning (gentle 1.1× catch-up for minor drift, hard seek to the live edge past 1.5 s) is now applied directly in `useCameraStream`, so it takes effect in the actual Perspective components.

### Notes
- Diagnosed against the live gateway: PTZ command → camera is ~50 ms; go2rtc delivers the MP4 smoothly (~150 ms cadence); the gateway proxy flushes per-chunk. The residual latency was entirely browser-side playhead drift in the un-pinned inline loop.
- The camera (Reolink sub-stream) keyframe interval is fixed at 4 s and cannot be lowered via ONVIF (`SetVideoEncoderConfiguration` reports success then reverts), so first-frame-on-connect can still take up to ~4 s; steady-state tracking after that is ~0.5–1 s.

---

## [3.0.11] - 2026-06-29

**Type:** PATCH — PTZ responsiveness / live-stream latency

### Fixed
- **PTZ felt laggy and unresponsive in Perspective.** The PTZ command itself reaches the camera in ~50 ms (measured), but the MSE video feed had no live-edge control: the `<video>` playhead drifted further behind the live edge on every decode stall — and a PTZ move causes exactly such a stall (the whole scene changes until the next keyframe). Latency accumulated into multiple seconds, so the picture caught up long after the command landed. `MsePlayer` now continuously pins the playhead near the live edge (gentle 1.1× catch-up for minor drift, hard seek to the live edge past 1.5 s), capping live latency at ~0.5 s. Note: the floor is still bounded by the camera's keyframe/GOP interval — lower the camera's I-frame interval for best results.

---

## [3.0.10] - 2026-06-26

**Type:** PATCH — PTZ control fix

### Fixed
- **Perspective PTZ controls did nothing.** The `/ptz/move` and `/ptz/stop` routes were registered without an HTTP method, so Ignition's data-route framework defaulted them to GET — but the Camera Viewer's PTZ buttons POST to them. Every move/stop returned **404 "No route match"** and was silently swallowed by the component's fire-and-forget fetch (`.catch(() => {})`), so the camera never received the command. Added `.method(HttpMethod.POST)` to both routes (`/ptz/status` stays GET). PTZ now reaches the camera via ONVIF ContinuousMove/Stop. This was latent — PTZ had never been exercised end-to-end until a PTZ camera was connected.

---

## [3.0.9] - 2026-06-25

**Type:** PATCH — ONVIF PTZ discovery robustness

### Fixed
- **PTZ-capable cameras could report no PTZ over ONVIF.** `ONVIFClient.parseServices()` matched service elements by a hardcoded `tds:` XML prefix, so cameras that use a different SOAP prefix had *all* services silently dropped — taking PTZ and ONVIF media discovery with them. Service parsing is now namespace-prefix-agnostic (matched by local name), consistent with `parseMediaProfiles()`.
- **PTZ is also detected from a media profile's `PTZConfiguration`.** Cameras that advertise PTZ via the profile rather than a discrete PTZ service are now recognised. Added `MediaProfile.hasPtz()`; `CameraDevice` falls back to it when no standalone PTZ service is listed.

### Added
- Interactive architecture diagram at `docs/architecture.html` — offline, pan/zoom, click-through component map of the whole module.

### Tests
- Regression tests for non-`tds` prefix service parsing and `PTZConfiguration` detection.

---

## [3.0.8] - 2026-06-23

**Type:** PATCH — security & robustness hardening (deep-review punch list)

### Security
- **go2rtc API authenticated** — a random per-launch password is written to the go2rtc config and sent as HTTP Basic on every API call, closing the unauthenticated-localhost credential readback (C3).
- **Session token & API key are header-only** — removed the `?token=` / `?apiKey=` query-param paths that leak into access logs (H6).
- **ONVIF SOAP redirects are only followed to the same host** — prevents a malicious camera redirecting WS-UsernameToken auth to an external host (H3).

### Fixed
- **Snapshot device cleanup (C1)** — `forgetDevice()` clears every per-device metric map and cache on shutdown and unblocks in-flight waiters.
- **Snapshot coalescing hang (C2)** — the winning fetch is wrapped so any unchecked exception completes waiters immediately; join timeout reduced 30s → 15s.
- **Per-stream bitrate baseline (H1)** — `/metrics` and `/diagnostics` no longer corrupt each other's bitrate deltas.
- **Diagnostics/metrics/health endpoints rate-limited (H2).**
- **ONVIF connection-pool leak (H4)** — response entity consumed on non-200.
- **Device status visibility (H5)** — `deviceStatus` is now volatile and the OPC-UA address-space build is guarded so a node-build failure can't permanently brick the device.
- IP/host validation wired into config-save via `ValidationUtil.isValidIpOrHost` (M6); device-name pattern widened (M7); periodic expired-token sweep (M1); go2rtc restart counter only resets after stable uptime (M2); frontend clock-skew, refresh button, MSE token-ref and stale-metrics fixes (M5/L5/L6/L7).
- **`TRUST_FIRST_USE` SSL mode no longer throws** — it falls back to STRICT with a warning instead of bricking ONVIF on save.

### Docs
- SECURITY.md documents the go2rtc localhost trust boundary; corrected the suite-table module ID.

---

## [3.0.3] - 2026-06-18

**Type:** PATCH — bug fix / robustness / internal rename

### Fixed
- **Devices on the legacy `com.onvif.driver.Camera` type could not be edited** — opening one in the Gateway's Device Connections editor showed **"Web UI Component type not found"**. `LegacyCameraExtensionPoint.getWebUiComponent()` returned `Optional.empty()` for *all* component types, which hid the type from the "Add Device" dropdown (intended) but also stripped the **edit** form (unintended). It now returns the standard Camera editor for `EDIT_FORM` while still returning empty for `ADD_FORM`, so any device left on the legacy type after a pre-v3.0.0 upgrade stays fully editable. The editor form construction is shared between `CameraExtensionPoint` and `LegacyCameraExtensionPoint` so add/edit forms can't drift. Covered by `LegacyCameraExtensionPointWebUiTest`.

### Changed
- **De-emphasised ONVIF as the module's primary identity.** ONVIF is now documented and structured as one of several connection methods (alongside RTSP, MJPEG, and snapshot) on the single unified **Camera** device type — not the headline. Renamed the two ONVIF-named *non-protocol* classes: `ONVIFModuleHook` → **`CameraModuleHook`** (gateway hook) and `ONVIFRoutes` → **`CameraRoutes`** (shared HTTP endpoints), with the gateway hook-class reference updated accordingly. The genuine ONVIF protocol classes (`ONVIFClient`, `ONVIFAuth`, `ONVIFService`, `ONVIFPoller`, etc.) keep their names. `README.md` and `CLAUDE.md` reframed around the unified Camera device type. **No change to the module ID (`com.gaskony.camera.opcua`) or device type ID (`com.gaskony.camera.Camera`)** — existing devices are unaffected.

---

## [3.0.2] - 2026-06-17

**Type:** PATCH — bug fix / robustness

### Fixed
- **Live stream returned a misleading `400 "No streaming source available"` when the camera was configured but unreachable.** If go2rtc could not pull the upstream RTSP source (e.g. wrong stream path → camera `404`, bad credentials, or camera offline), `StreamHandler` fell through its fallback chain and reported a 400 implying nothing was configured. It now distinguishes the two cases: a configured-but-unreachable source returns **`502`** with the real reason (e.g. *"go2rtc returned HTTP 500"* / *"…could not be reached"*), while `400` is reserved for genuinely no source configured. `proxyStream()` now propagates the upstream HTTP status instead of a bare boolean.
- **H.265/HEVC streams failed silently in the browser player.** When the camera served HEVC (e.g. a Reolink main stream), the MSE player fell back to an H.264 codec and fed it mismatched bytes, producing an opaque failure. Both players (`mse-player.js` and `web-ui` `MsePlayer.ts`) now trust the server-declared codec: they play HEVC where the browser supports it, and otherwise show a clear message directing the user to the H.264 sub-stream. The players also surface the server's error body, so a 502 now reads as *"HTTP 502 - Camera stream source is unavailable: …"* instead of a bare status code.

### Security
- **Stopped logging credential-bearing stream URLs.** `StreamHandler.proxyStream()` previously logged the full upstream URL (`rtsp://user:pass@…`) on a non-200 response; it now logs only the device name and status code.

### Tests
- Added `StreamHandlerTest` (auth, missing/invalid device params, the new 502-on-upstream-failure path, and 400-only-when-no-source-configured).

---

## [3.0.1] - 2026-05-22

**Type:** PATCH — bug fix

### Fixed
- **Dedicated/standalone page showed a redundant "Camera Driver" top bar when logged out.** `mounted/standalone.html` rendered its `dedicated-header` bar unconditionally, so a logged-out visitor saw the top bar *and* the centred "Authentication Required" card (which carries its own module identity) — inconsistent with the other four modules, whose logged-out view shows only the centred card. The header now toggles with auth state: hidden in the auth view, shown only once authenticated (matching the AI Terminal / Python3 pattern). As a side benefit, the bar no longer flashes before the auth check resolves.

---

## [3.0.0] - 2026-05-21

### MAJOR — BREAKING — package rename + new module ID

This is the suite-wide Gaskony rename promised in `/modules/.review/SECTION_10_DECISIONS.md` §10 #6. The Java packages move to the `com.gaskony.camera.*` prefix and the module ID changes accordingly. Customers MUST uninstall the old `com.onvif.driver.opcua` and install the new `com.gaskony.camera.opcua` — Ignition treats them as different modules. Existing device profiles continue to work through a legacy alias.

### Breaking
- **Module ID renamed.** `com.onvif.driver.opcua` → `com.gaskony.camera.opcua`. Customers must perform a manual uninstall + install via the Gateway module manager (the gateway does not auto-port config between module IDs).
- **Java packages renamed.** `com.onvif.driver.*` → `com.gaskony.camera.*`. Any Jython that referenced the old FQNs needs updating.
- **Extension-point type renamed.** `com.onvif.driver.Camera` → `com.gaskony.camera.Camera`. New device profiles must be created under the new type (visible as "Camera" in the OPC-UA device-type dropdown).

### Added
- `LegacyCameraExtensionPoint` — a back-compat alias registered under the pre-rename type ID `com.onvif.driver.Camera`. Existing v2.34.x device profiles continue to load transparently after the upgrade and emit a one-time `WARN` per device naming what to recreate later. Hidden from the "Add Device" dropdown (one-way alias). Scheduled for removal in Camera Driver v4.0.0.

### Migration
See `/modules/.review/MIGRATION-v3-v4.md` for the customer-facing playbook covering both Camera v3.0.0 and Python3 v4.0.0.

---

## [2.34.1] - 2026-03-07

### Cross-module standardisation (Round 4)

#### Changed
- Gradle group changed from `com.onvif.driver` to `com.gaskony` (aligns all 5 modules)
- Add `allprojects` block for consistent version/group propagation to subprojects
- Add `allowImportingTsExtensions: false` to `tsconfig.webpack.json` (matches AT, Git, Python3)
- Remove vestigial `prettier` devDependency (no `.prettierrc` existed)
- Standardise ESLint rule order to `no-unused-vars`, `no-explicit-any`, `ban-ts-comment` (matches AT, Git, Python3)

---

## [2.34.14] - 2026-05-07

### Sprint 3 closeout — a11y, perf, hygiene

#### Added
- A11y baseline: skip-link, `prefers-reduced-motion: reduce` block in `styles.css`, `aria-live="polite"` toast container, focus-trap on `Modal` primitive, label associations on form inputs (P10).

#### Performance
- Pause `DiagnosticsView` polling when `document.visibilityState === 'hidden'` to remove background network traffic.

#### Changed
- Standardise `.gitattributes` and `.gitignore` to the cross-module canonical version (Sprint 3 hygiene).

#### Documentation
- Replace stale `SKILLS.md` / `LEARNINGS.md` references with `.claude/skills/` paths; Australian English spelling sweep across READMEs (P8).

#### Tests
- `./gradlew check` now enforced in `pr-checks.yml` (JaCoCo + Checkstyle + SpotBugs gate PRs); JaCoCo threshold raised in line with measured coverage (P7).

---

## [2.34.13] - 2026-05-05

### Changed
- Refactor route handlers onto a unified `AccessControl` helper (Gateway session + localhost / private-IP) shared with the other 4 modules (P3-CD).

---

## [2.34.12] - 2026-05-05

### Performance
- SDK-compliant device lifecycle: probe + connect work moved off the Gateway startup thread; ONVIF discovery now runs async with bounded executor and per-device timeouts (P2-CD-1, P2-CD-2).

### Security
- Harden `.gitignore` to deny `gradle.properties`, `sign.properties`, `*.jks`, `*.keystore`, and broad `.env.*` patterns; allowlist for templates/examples and public-key suffixes (B3-autonomous).

---

## [2.34.11] - 2026-05-05

### Fixed
- Functional PTZ via Milo's `AttributeFilter`: PTZ writes were silently dropped because the PTZ continuous-move call was issued from the wrong execution context. Routed through `AttributeFilter` so writes are honoured (C10).

---

## [2.34.10] - 2026-05-05

### Security
- Default ONVIF SSL/TLS validation mode is now `STRICT`. `TRUST_FIRST_USE` and `INSECURE` remain available but require explicit configuration (C5).

---

## [2.34.9] - 2026-05-05

### Security
- Close authentication bypass on snapshot/stream/devices HTTP endpoints. All endpoints now go through Gateway session auth + Basic auth + API key auth before any device proxy is invoked (B1).

---

## [2.34.8] - 2026-03-12

### Changed
- Rebuild the React web UI on component architecture (shared layout shell, page-level routes, normalised CSS scopes) and normalise line endings to LF.

---

## [2.34.7] - 2026-03-14

### Changed
- Standardise the shimmer animation to percentage-based positioning so it scales cleanly at every viewport width.

---

## [2.34.6] - 2026-03-14

### Changed
- Standardise CSS values (spacing, radius, shadow tokens, transition timings) across the module UI per the cross-module variable contract.

---

## [2.34.5] - 2026-03-14

### Added
- Replace browser `prompt()` with a styled modal; add error-state retry buttons on connection failures.

---

## [2.34.4] - 2026-03-14

### Added
- Authentication overlay screen for the Gateway WebUI; fix section-header font sizes to match the cross-module typography scale.

---

## [2.34.3] - 2026-03-13

### Changed
- Cross-module UI standardisation v2.34.2: align typography, spacing, accent colours, and component shells with AT, Git, PLC, Python3.

---

## [2.34.2] - 2026-03-07

### Changed
- Wire `syncVersion` task into the build, fix logger declarations to `private static final Logger logger`, switch all log messages to SLF4J `{}` placeholders, add `lucide-react` icon library.
- Update version references throughout docs to 2.34.0; remove unnecessary helper files; remove the `license.set` block and `license.html` syncVersion reference (Round-5 cleanup).

## [2.6.8] - 2026-02-10

### Fixed
- **MSE sourceopen race condition**: The `sourceopen` event fired immediately when `videoElement.src` was set, but the code awaited `fetch()` first, so the event listener was added too late - causing the MSE player to hang forever. Fixed by registering the listener before setting `src`.
- **Content-Type charset stripping**: Servlet container appended `;charset=utf-8` to the forwarded Content-Type header, making it invalid for `MediaSource.isTypeSupported()`. Now stripped before use.

## [2.6.7] - 2026-02-10

### Fixed
- **MSE codec mismatch**: go2rtc sends fMP4 with both H.264 video and AAC audio (`avc1.640029,mp4a.40.2`), but the MSE SourceBuffer only declared video-only codec (`avc1.640029`), causing browsers to reject the init segment containing the unexpected audio track.

### Changed
- **Proxy Content-Type forwarding**: `proxyStream()` now forwards the upstream Content-Type header from go2rtc (including codec info) instead of hardcoding `video/mp4`
- **Dynamic codec detection**: MSE player reads the Content-Type from the fetch response to determine the correct codec string, with fallback to common codec combinations

## [2.6.6] - 2026-02-10

### Added
- **MSE (Media Source Extensions) player**: Browsers cannot play live fragmented MP4 via simple `<video src>` - they require the MSE JavaScript API. Implemented `playMseStream()` using `fetch()` ReadableStream to feed fMP4 chunks into an MSE SourceBuffer for real-time H.264 playback.
- **Stream loading indicator**: Shows "Connecting to camera stream..." while MSE initializes
- **Stream error display**: Error messages shown in the stream modal on failure
- **Buffer management**: Automatic trimming of old buffered data (~30s window) to prevent memory growth, with `QuotaExceededError` handling

### Changed
- `viewStream()` now uses MSE for Generic Camera + go2rtc devices instead of `<video src>`
- `closeStreamModal()` properly cleans up MSE resources (AbortController, ReadableStream reader, MediaSource)

## [2.6.5] - 2026-02-10

### Changed
- **MP4 proxy instead of MJPEG**: Switched Generic Camera streaming from go2rtc's MJPEG endpoint (`/api/stream.mjpeg`) to MP4 endpoint (`/api/stream.mp4`). MJPEG requires ffmpeg for H.264-to-JPEG transcoding (not available in Docker), while MP4 does native H.264 passthrough.
- Added `getStreamMp4Url()` to `Go2RtcManager`
- Connection Browser uses `<video>` element for generic cameras, `<img>` for ONVIF MJPEG

## [2.6.4] - 2026-02-10

### Fixed
- **go2rtc API parameter**: Stream naming used incorrect `dst` parameter instead of `name`, causing 404 errors on stream endpoints. Fixed in `Go2RtcManager.addStream()` and `removeStream()`.

## [2.6.3] - 2026-02-10

### Fixed
- **Extension point lifecycle**: `getDeviceExtensionPoints()` was called by OPC-UA framework before `setup()`, creating `GenericCameraExtensionPoint` with null `go2RtcManager`. The framework cached these instances, so devices started without go2rtc. Fixed by making `go2RtcManager` mutable with a setter and reusing existing extension point instances in `setup()`.

## [2.6.2] - 2026-02-10

### Fixed
- **go2rtc startup timing race**: go2rtc was started in `startup()` but devices initialize between `setup()` and `startup()`. Moved `go2RtcManager.start()` to `setup()` so go2rtc is available before device startup.
- **Lazy stream registration**: Added `tryRegisterGo2Rtc()` for late registration when go2rtc wasn't available during initial device startup.

## [2.6.1] - 2026-02-10

### Added
- **Synthetic media profiles for Generic Camera**: Connection Browser now generates profiles from configured URLs (RTSP Stream/H.264, HTTP Snapshot/JPEG, MJPEG Stream/MJPEG) so Generic Camera devices show profiles instead of "No media profiles available"
- **Running counter fix**: Stats bar now counts both "Running" and "Connected" device statuses

### Fixed
- **Windows go2rtc binary**: Build script was saving the ZIP archive directly as `.exe`. Now properly downloads, extracts, and renames the binary.
- **Profile display**: `renderProfile()` gracefully handles missing width/height/frameRate fields

## [2.6.0] - 2026-02-10

### Changed - Multi-Protocol Camera Driver Rebranding
- **Module Identity**: Repositioned as a multi-protocol Camera Driver module with ONVIF as one connection type (not the only one)
- **ONVIF Device Type Rename**: Device dropdown now shows "ONVIF Camera" instead of "Camera Driver" to distinguish from Generic Camera
- **Module Description**: Updated to "Multi-protocol camera driver supporting ONVIF, RTSP, MJPEG, and snapshot URL connections"
- **Documentation Overhaul**: All documentation rewritten to present ONVIF and Generic Camera as equal device types
- **CLAUDE.md**: Complete rewrite with multi-protocol architecture documentation
- **README.md**: Restructured around two device types (ONVIF Camera + Generic Camera)
- **All docs**: Updated USAGE.md, TESTING.md, SECURITY.md, CAMERA_COMPATIBILITY.md, IMPLEMENTATION_STATUS.md
- **i18n Properties**: Updated ONVIFDevice.properties descriptions to be camera-focused (not ONVIF-centric)
- **Web UI Package**: Renamed from `onvif_driver_webui` to `camera_driver_webui`
- **License Page**: Updated description to multi-protocol
- **Version**: Bumped to 2.6.0

### Unchanged (Backward Compatibility)
- Java packages remain `com.onvif.driver.*`
- Class names unchanged (ONVIFDevice, ONVIFModuleHook, ONVIFRoutes, etc.)
- Module ID remains `com.onvif.driver.opcua`
- Device type IDs and config records unchanged
- HTTP endpoint paths unchanged (`/data/camera-driver/*`)
- Existing device configurations continue to work

### Notes
- The module now clearly presents two device types in the dropdown: "ONVIF Camera" and "Generic Camera"
- Future connection types can be added as additional device types within the same module
- ONVIF-specific code (protocol layer, SOAP client, auth) retains ONVIF naming as it IS ONVIF-specific

## [2.5.0] - 2026-02-10

### Changed
- **Module Rename**: "ONVIF Driver" renamed to "Camera Driver" across all user-facing strings, labels, and log messages
- **URL Paths**: `/data/onvif-driver/*` → `/data/camera-driver/*` for all HTTP endpoints
- **Data Directory**: `{dataDir}/onvif-driver/go2rtc/` → `{dataDir}/camera-driver/go2rtc/`
- **Navigation**: Gateway Config menu now shows "Camera Driver > Connection Browser"
- **Build Output**: Module file renamed from `ONVIFDriver-{version}.modl` to `CameraDriver-{version}.modl`
- **Resource Paths**: `/res/onvif-driver/*` → `/res/camera-driver/*`
- **WWW-Authenticate Realm**: "ONVIF Driver" → "Camera Driver"
- **Health Check**: Service name in `/health` response changed to `camera-driver`
- **i18n Display Name**: Device type dropdown now shows "Camera Driver"
- **Version**: Bumped to 2.5.0

### Unchanged (Backward Compatibility)
- Java packages remain `com.onvif.driver.*`
- Class names unchanged (ONVIFDevice, ONVIFModuleHook, ONVIFRoutes, etc.)
- Module ID remains `com.onvif.driver.opcua`
- Device type IDs and config records unchanged
- Existing device configurations continue to work

### Notes
- This rename reflects the module's expanded scope: both ONVIF protocol cameras and generic RTSP/MJPEG/snapshot cameras
- GitHub repo renamed from `ignition-module-ONVIF-driver` to `ignition-module-camera-driver`
- 28 files updated, all 168 tests passing

## [2.4.0] - 2025-12-11

### Security
- **CVE-2024-43788 Fixed**: Upgraded webpack from 5.70.0 to 5.94.0 to address DOM Clobbering XSS vulnerability
- **Security Audit**: Comprehensive security review completed with no critical issues found
- **React 18.2.0 Verified Safe**: Confirmed not affected by CVE-2025-55182 (React Server Components vulnerability)

### Removed
- **Diagnostic Test Route**: Removed `/test` endpoint that was marked for production removal
- Cleaned up development-only code for production release

### Changed
- Updated IMPLEMENTATION_STATUS.md to reflect v2.4.0 production status
- Updated documentation with security audit findings

### Notes
- This is the first production-ready release after comprehensive security review
- All dependencies verified against known CVE databases
- React 18.2.0 retained (stable, secure, appropriate for client-side UI)

## [2.3.2] - 2025-11-26

### Changed
- **UI Compactness**: Reduced header and card sizes by approximately 50% for more efficient screen usage
- Smaller fonts, padding, and margins throughout the Connection Browser page
- Simplified device selector card layout

## [2.3.1] - 2025-11-26

### Fixed
- **Authentication**: Removed redundant custom authentication checks from Connection Browser routes. Now properly uses Ignition's built-in session authentication for `/data/` routes, eliminating the separate login prompt.
- **Device Selector**: Added device dropdown selector to Connection Browser page, matching the PLC Simulator File Upload pattern. Users can now select a specific device or view all devices at once.

### Changed
- Connection Browser page now includes URL parameter support (`?device=DeviceName`) for deep linking to specific devices
- Stats bar now updates dynamically based on selected device filter

## [2.3.0] - 2025-11-26

### Added - Connection Browser UI
- **Gateway Config Menu Item**: New "Camera Driver > Connection Browser" menu entry in Gateway Config under Connections
- **Connection Browser Page**: Web-based dashboard for viewing all ONVIF device connections
  - Real-time device status display (Running, Error, Connecting)
  - Device information (manufacturer, model, firmware, serial number)
  - Media profiles with resolution, frame rate, and encoding details
  - Snapshot preview capability
  - Live MJPEG stream viewer modal
  - RTSP URI copy functionality
  - Auto-refresh toggle for live status updates
- **New API Endpoints**:
  - `/data/camera-driver/devices` - List all devices with status and profiles
  - `/data/camera-driver/device/:name/status` - Detailed device status
  - `/data/camera-driver/connection-browser` - Connection browser HTML page
  - `/data/camera-driver/health` - Health check endpoint

### Added - Web UI Module
- **New web-ui subproject**: React/TypeScript component build system
  - Webpack configuration for SystemJS module output
  - TypeScript with strict mode
  - ESLint and Prettier for code quality
  - SCSS styling support
- **ConnectionBrowser React Component**: Embeds HTML page in Gateway Config UI

### Changed
- **settings.gradle.kts**: Added `:web-ui` project
- **gateway/build.gradle.kts**: Added `modlImplementation(projects.webUi)` dependency
- **ONVIFModuleHook**: Added navigation menu registration and getMountedResourceFolder()
- **ONVIFRoutes**: Added device listing and connection browser routes

### Technical Details
- Build follows same pattern as PLC Simulator project
- Uses Node.js 18.0.0 with Yarn for frontend build
- Webpack outputs SystemJS-compatible module for Ignition gateway
- HTML page served via authenticated data routes

## [2.2.0] - 2025-11-24

### Added - Production Authentication
- **Account Lockout**: 5 failed attempts triggers 15-minute lockout
- **SHA-256 Hashed API Keys**: Secure storage for API key authentication
- **Security Event Logging**: Authentication failures logged for monitoring

## [2.1.0] - 2025-11-22

### SECURITY - Authentication Implemented ✅
- **IMPLEMENTED**: HTTP endpoint authentication with session validation
- **IMPLEMENTED**: Per-IP rate limiting (10 requests/minute per IP)
- Three authentication methods supported:
  - HTTP session authentication (Ignition user sessions)
  - Basic Authentication header
  - API key query parameter for programmatic access
- Proper 401 Unauthorized responses with WWW-Authenticate header
- 429 Too Many Requests responses for rate limit violations

### Testing - Comprehensive Test Suite ✅
- **168 tests** with 100% pass rate
- JUnit 5, Mockito, and AssertJ framework configured
- Unit tests created for all core utilities:
  - **ValidationUtilTest**: 87 tests covering all validation scenarios
  - **ONVIFAuthTest**: 21 tests for WS-UsernameToken generation
  - **XmlUtilTest**: 33 tests including XXE protection verification
  - **ONVIFClientTest**: 27 tests for SSL modes and configuration
- Security-focused testing:
  - XSS injection prevention validated
  - SQL injection prevention validated
  - JNDI injection prevention validated
  - Path traversal prevention validated
  - XXE attack prevention validated
  - Billion Laughs expansion attack prevention validated

### Added
- `isAuthenticated()` method with multiple authentication strategies
- `checkRateLimit()` method with per-IP tracking
- `getClientIP()` helper with proxy header support (X-Forwarded-For, X-Real-IP)
- `sendAuthenticationRequired()` helper for proper 401 responses
- Comprehensive test suite (1,535 lines of test code)
- Test dependencies: JUnit 5.10.1, Mockito 5.8.0, AssertJ 3.25.1

### Changed
- **handleSnapshot()**: Now requires authentication and enforces rate limiting
- **handleStream()**: Now requires authentication and enforces rate limiting
- Default authentication mode: REQUIRED (configurable via `REQUIRE_AUTHENTICATION` constant)
- Request handling order: Authentication → Rate limiting → Business logic

### Fixed
- **CRITICAL**: HTTP endpoints now properly authenticated (was OPEN_ROUTE in v2.0.0)
- **HIGH**: Rate limiting now enforced per-IP (was global only in v2.0.0)
- Security gaps in endpoint access control addressed
- Test coverage increased from 0% to comprehensive utility coverage

### Security Improvements
- Authentication enforcement prevents unauthorized access to camera feeds
- Rate limiting prevents DoS attacks and resource exhaustion
- Per-IP tracking prevents single-client resource monopolization
- Proper HTTP status codes guide clients on authentication requirements
- All validation utilities now have comprehensive security test coverage

### Testing Coverage
- **ValidationUtil**: 100% method coverage with 87 tests
- **ONVIFAuth**: 100% method coverage with 21 tests
- **XmlUtil**: 100% method coverage with 33 tests
- **ONVIFClient**: Configuration and lifecycle testing with 27 tests
- Total test execution time: ~2 seconds
- All tests passing on JDK 17 and JDK 21

### Known Limitations
- **Authentication credential validation**: Currently accepts any Basic Auth header; TODO: validate against Ignition user source
- **API key validation**: Currently accepts any non-empty API key; TODO: validate against configured keys
- **Rate limiting algorithm**: Simple time-window; TODO: implement proper sliding window or token bucket
- **TRUST_FIRST_USE mode**: Still not fully implemented (planned for v2.2.0)

### Migration Notes
If upgrading from v2.0.0:
- HTTP endpoints now require authentication by default
- To disable temporarily: Set `REQUIRE_AUTHENTICATION = false` in ONVIFRoutes.java
- Rate limiting is always enabled (10 requests/minute per IP)
- Existing Ignition users with valid sessions will automatically authenticate
- For programmatic access, add `?apiKey=YOUR_KEY` to requests (key validation TBD)

## [2.0.0] - 2025-11-22

### SECURITY
- **CRITICAL**: Removed hardcoded credentials from gradle.properties
- **CRITICAL**: Implemented configurable SSL/TLS validation with three modes:
  - STRICT: Full certificate validation (recommended for production)
  - TRUST_FIRST_USE: Trust certificate on first connection (planned for future)
  - INSECURE: Accept all certificates (backward compatibility mode)
- Environment-based credential management for module signing
- Created comprehensive docs/SECURITY.md documentation
- Added ValidationUtil for centralized input validation

### BREAKING CHANGES
- **SSL validation mode now configurable**: Default remains INSECURE for backward compatibility, but STRICT mode is now available and recommended for production
- **Module signing credentials**: Must be set via environment variables (KEYSTORE_PASSWORD, CERT_PASSWORD) instead of gradle.properties
- **ONVIFClient constructor**: Added sslValidationMode parameter (existing code must be updated)

### Added
- SslValidationMode enum with STRICT, TRUST_FIRST_USE, and INSECURE options
- SSL validation configuration field in device configuration UI
- ValidationUtil class for centralized input validation (eliminates code duplication)
- CLAUDE.md for AI-assisted development
- Comprehensive docs/SECURITY.md documentation
- gradle.properties.template for secure credential setup
- CORS origin validation helper methods

### Changed
- Updated Gson dependency from 2.10.1 to 2.11.0
- Refactored validation logic to use centralized ValidationUtil
- Updated all documentation to reflect v2.0.0 status
- Streaming features now documented as IMPLEMENTED
- Improved CORS policy with origin validation
- Enhanced security documentation across all files

### Fixed
- Security vulnerability: Hardcoded credentials in gradle.properties (now use environment variables)
- Security issue: Overly permissive SSL validation (now configurable with STRICT mode)
- Code duplication: Validation logic consolidated into ValidationUtil
- Build configuration: Module signing properly configured for environment-based credentials

### Removed
- Dead servlet code files (ONVIFSnapshotServlet.java, ONVIFStreamServlet.java, ONVIFServlet.java) - ~600 lines of unused code
- Hardcoded credentials from gradle.properties

### Known Limitations
- **HTTP endpoint authentication**: Currently uses OPEN_ROUTE. Full authentication implementation planned for v2.1.0 (requires custom session validation due to Ignition SDK limitations)
- **TRUST_FIRST_USE mode**: Not fully implemented, falls back to INSECURE mode
- **CORS configuration**: Origin validation is permissive; should be made configurable in future release

### Planned for v2.1.0
- HTTP endpoint authentication with session validation
- Full TRUST_FIRST_USE implementation with certificate pinning
- Configurable CORS origins
- Per-IP rate limiting
- Comprehensive automated test suite

## [1.0.23] - 2025-11-21

### Changed
- Clean ONVIF implementation with proper error handling
- Code cleanup and refactoring for improved maintainability
- Enhanced error messages and logging

### Fixed
- Error handling edge cases in ONVIF client
- Improved robustness of ONVIF operations

## [1.0.14] - 2025-11-20

### Fixed
- HTTP route mounting with proper access control
- MIME type specification for routes
- Route registration reliability

### Changed
- Improved route configuration and registration
- Enhanced route handler error handling

## [1.0.11] - 2025-11-19

### Fixed
- HTTP route mounting with proper .type() specification
- Route type detection and content type handling

## [1.0.7] - 2025-11-18

### Added
- RTSP to MJPEG streaming capabilities
- Live video streaming through HTTP endpoints
- Frame rate control for MJPEG streams
- Resource protection with concurrent stream limits

### Changed
- Enhanced streaming performance and reliability
- Improved frame delivery consistency

## [1.0.6] - 2025-11-18

### Fixed
- Empty tags folder in OPC-UA browser
- Node registration with NodeManager
- Tag visibility in Ignition Designer

### Changed
- Improved OPC-UA node management
- Enhanced tag browser integration

## [1.0.5] - 2025-11-11

### Fixed
- Password field handling in configuration UI
- Device visibility in Tag Browser
- Configuration persistence issues

### Changed
- Improved configuration UI reliability
- Enhanced device status reporting

## [1.0.4] - 2025-11-10

### Fixed
- Property bundle loading by relocating properties file
- Service Type dropdown formatting
- Display name localization

### Changed
- Properties file moved to correct package structure: `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`

## [1.0.3] - 2025-11-10

### Added
- XmlUtil utility class for secure XML parsing and manipulation
- XXE (XML External Entity) attack protection in all XML parsing
- XML injection prevention with proper escaping of user inputs
- HTTPS certificate validation with self-signed certificate support
- Thread-safe error counting using AtomicInteger in ONVIFPoller
- Comprehensive security hardening across all network operations
- docs/IMPLEMENTATION_STATUS.md document tracking project completion
- Proper @SuppressWarnings annotations for SDK interface compliance

### Changed
- DeviceInformation converted from class to immutable record (72% code reduction)
- ONVIFClient now delegates to XmlUtil for all XML operations
- ONVIFPoller uses AtomicInteger for concurrent error counting
- All SOAP requests now escape user-controlled data to prevent XML injection
- SSL/TLS configuration added to HTTP client for secure HTTPS connections
- Updated README.md to reflect Phase 1-6 completion status
- Updated docs/TESTING.md with correct version numbers and feature list

### Fixed
- Critical XXE vulnerability in DocumentBuilderFactory configuration
- Critical XML injection vulnerability in SOAP request building
- Thread safety issue in ONVIFPoller consecutive error counter
- Raw type warnings in ONVIFDevice OPC-UA interface methods
- Documentation inconsistencies (version numbers, feature status, file paths)

### Security
- Hardened XML parser against XXE attacks with disabled external entities
- All profile tokens and user inputs now properly escaped in SOAP requests
- Added SSL context configuration for HTTPS connections
- Documented security trade-offs (SHA-1, self-signed certs, hostname verification)

## [1.0.2] - 2025-01-09

### Fixed
- Property bundle loading by moving properties file to correct package structure
- Properties file now correctly located at `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`
- Service Type dropdown formatting in designer configuration UI
- Display names now show correctly instead of "¿...?" placeholder text

### Changed
- Certificate regenerated with SHA256 signature algorithm
- Module version bumped to reflect property bundle fixes

## [1.0.1] - 2025-01-08

### Fixed
- Module signing with DER certificate format
- PKCS7 certificate chain generation for proper module validation
- Build configuration to use correct certificate encoding

### Changed
- Gradle build now uses DER-encoded certificate for signing
- Certificate conversion process updated in build documentation

## [1.0.0] - 2025-01-07

### Added
- Initial complete implementation of ONVIF driver (Phases 1-6)
- ONVIF SOAP client with HTTP/HTTPS support
- WS-UsernameToken authentication with SHA-1 digest and nonce
- GetDeviceInformation operation
- GetServices operation for dynamic service discovery
- GetMediaProfiles operation
- PTZ status retrieval and control (absolute move, stop)
- Configurable polling mechanism (ONVIFPoller)
- Auto-reconnect with exponential backoff (max 5 attempts)
- Hierarchical OPC-UA address space with DeviceInfo, MediaProfiles, PTZ, and Status nodes
- Comprehensive error handling and logging
- Designer configuration UI with validation
- Localized resource bundles for i18n support
- Module signing infrastructure
- Complete build system with Gradle

### Components
- ONVIFClient - Main SOAP client for ONVIF communication
- ONVIFAuth - WS-UsernameToken authentication generator
- ONVIFPoller - Periodic device polling mechanism
- AddressSpaceBuilder - OPC-UA node hierarchy builder
- ONVIFDevice - Device lifecycle and integration
- ONVIFDeviceConfig - Configuration record with validation
- DeviceInformation, MediaProfile, PTZStatus, ONVIFService data models

## [Unreleased]

### Planned for v2.1.0
- Event subscription support (motion detection, tampering alerts)
- ONVIF Profile G support (recording search and playback)
- Comprehensive automated test suite
- Unit tests for ONVIF protocol operations
- Integration tests with camera simulators
- Performance optimization and connection pooling

---

## Version Numbering

- **Major** (X.0.0): Breaking changes, major feature additions
- **Minor** (0.X.0): New features, non-breaking changes
- **Patch** (0.0.X): Bug fixes, security patches, documentation updates

---

## Links

- [README.md](README.md) - Project overview and quick start
- [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) - Detailed implementation status
- [TESTING.md](TESTING.md) - Testing and installation guide
- [GitHub Repository](https://github.com/Gaskony-Ignition/ignition-module-camera-driver)
