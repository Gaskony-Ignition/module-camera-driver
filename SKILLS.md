# Camera Driver - Skills

Knowledge base for the Camera Driver module. Reference this to understand patterns and conventions.

## Architecture
- Two device types in one module: ONVIF Camera (SOAP protocol) and Generic Camera (direct URL)
- OPC-UA device driver built on `AbstractDeviceModuleHook` / `DeviceExtensionPoint`
- go2rtc bundled for RTSP-to-MJPEG transcoding; ffmpeg for snapshot extraction
- HTTP endpoints via `ONVIFRoutes` servlet for snapshots, streaming, and device listing
- Shared `common/` subproject; main logic lives in `gateway/`

## Key Patterns
- Resource bundles MUST be registered with `BundleUtil.get().addBundle()` in `ModuleHook.startup()` — otherwise display names show as `?...?`
- `DeviceExtensionPoint` constructor takes i18n resource bundle keys, NOT literal text
- Properties files must match exact package path (e.g. `com/onvif/driver/gateway/device/ONVIFDevice.properties`)
- `FormFieldType.FILE` is a file upload button, not a server-side file browser — use `TEXT` for path input
- XML parsing must use `XmlUtil` for XXE protection; never use raw `DocumentBuilderFactory`
- HTTP endpoints enforce session/Basic/API-key auth plus per-IP rate limiting (10 req/min)

## Build System
- Root `build.gradle.kts` with `io.ia.sdk.modl` plugin; subprojects: `common`, `designer`, `gateway`, `web-ui`
- `downloadGo2Rtc` and `downloadFfmpeg` tasks fetch platform binaries (optional; graceful fallback)
- Frontend built with webpack via `npm run build` in `web-ui/`; output bundled into gateway resources
- Gson is `compileOnly` (Ignition bundles it); HTTP components are `modlImplementation`
- Java package is `com.onvif.driver.*`; module ID is `com.onvif.driver.opcua`

## Testing
- 168 tests across 4 classes: ValidationUtil (87), ONVIFAuth (21), XmlUtil (33), ONVIFClient (27)
- JUnit 5 + Mockito + AssertJ; security tests cover XSS, SQL injection, XXE, path traversal
- JaCoCo coverage threshold: 10% (line coverage)
- Frontend tests: `cd web-ui && npm test` (Vitest)

## Common Mistakes to Avoid
- Do not pass literal strings to `DeviceExtensionPoint` constructor — use i18n keys
- Do not forget `BundleUtil.addBundle()` — creating the `.properties` file alone is not enough
- Do not bundle Gson via `modlImplementation` — Ignition provides it at runtime (`compileOnly`)
- Acronyms must be ALL CAPS in display names: ONVIF, RTSP, MJPEG, PTZ, OPC-UA
- Always validate and escape user input before embedding in XML (use `XmlUtil`)
