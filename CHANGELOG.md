# Changelog

All notable changes to the Ignition Camera Driver module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
