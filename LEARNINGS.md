# Camera Driver - Learnings

Lessons learned during development. Reference this to avoid repeating past mistakes.

## Build System
- go2rtc binary download is optional; build succeeds without binaries (graceful fallback)
- Module signing auto-skips when keystore is unavailable
- Web UI (React) builds as part of the Gradle build via web-ui subproject
- JVM heap may need increasing for module signing in CI (use -Xmx flags)

## ONVIF Protocol
- ONVIF SOAP requires WS-UsernameToken authentication (not Basic Auth)
- XML parsing must use XXE-protected DocumentBuilderFactory
- Camera discovery can be slow; use timeouts on all network calls

## Ignition Integration
- Perspective component icons must use BufferedImage.setRGB() directly, not ImageIO (ServiceLoader classloader issues)
- Gateway config pages use React UMD bundles served from /res/ path
- Device driver OPC-UA nodes must be registered in AddressSpaceBuilder
- Resource bundles must be registered with BundleUtil or display names show as "?...?"

## Security
- CORS origin checking must use exact prefix matching, not contains()
- Rate limiting should be per-IP AND per-user
- API responses must strip credentials from device URIs
- Private/internal IP auth is needed for Docker deployments

## Common Mistakes to Avoid
- Never commit gradle.properties (contains signing credentials)
- Always use parameterised SLF4J logging (no string concatenation)
- Properties files must be in exact package structure for i18n
- ESLint warnings in web-ui should be fixed before release builds
