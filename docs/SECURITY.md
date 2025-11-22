# Security Documentation

## Overview

This document describes the security architecture, practices, and configuration for the ONVIF Driver module.

## Module Signing

### Configuration

Module signing credentials are **NOT** stored in version control. To build signed modules:

1. Copy `gradle.properties.template` to `gradle.properties`
2. Set environment variables:
   ```bash
   export KEYSTORE_PASSWORD="your-secure-password"
   export CERT_PASSWORD="your-secure-password"
   ```
3. Or edit `gradle.properties` locally (never commit this file)

### Certificate Management

- **Keystore**: `onvif-driver.jks` (excluded from version control)
- **Certificate**: `onvif-driver.der` (excluded from version control)
- **Alias**: `onvif-driver`

**IMPORTANT**: These files contain private keys and must never be committed to version control.

## Authentication & Authorization

### HTTP Endpoints (v2.1.0+)

**Current Status**: All HTTP endpoints require authentication as of v2.1.0.

- `/data/onvif-driver/snapshot` - **REQUIRES AUTHENTICATION**
- `/data/onvif-driver/stream` - **REQUIRES AUTHENTICATION**

**Supported Authentication Methods**:

1. **HTTP Session Authentication** (Primary - for Ignition users)
   - Users with valid Ignition Gateway sessions automatically authenticated
   - No additional configuration required
   - Seamless integration with Perspective and Vision clients

2. **Basic Authentication** (For external tools)
   ```bash
   curl -u username:password \
     "http://gateway:8088/data/onvif-driver/snapshot?device=Camera1&profile=000"
   ```

3. **API Key Authentication** (For programmatic access)
   ```bash
   curl "http://gateway:8088/data/onvif-driver/snapshot?device=Camera1&profile=000&apiKey=YOUR_KEY"
   ```

**Security Features**:
- ✅ Proper 401 Unauthorized responses with WWW-Authenticate header
- ✅ Session validation checks for Ignition users
- ✅ Multiple authentication methods for flexibility
- ✅ Failed authentication attempts logged for auditing

### ONVIF Device Authentication

Camera credentials are stored securely using Ignition's `SecretConfig`:
- Passwords encrypted at rest in Ignition database
- Never logged or exposed in error messages
- Automatic cleanup via try-with-resources

## SSL/TLS Configuration

### Camera Communication (v2.0.0+)

SSL/TLS certificate validation is **configurable** per device:

```
Validation Modes:
- STRICT: Full certificate validation (production recommended)
- TRUST_FIRST_USE: Accept and pin self-signed on first connection
- INSECURE: Accept any certificate (development only)
```

**Default**: INSECURE mode for backward compatibility (this will change to STRICT in v3.0.0)

**Why configurable?**: Many IP cameras use self-signed certificates. The INSECURE mode should only be used in trusted network environments.

### Gateway Communication

All HTTP endpoints support HTTPS when Ignition Gateway is configured with SSL.

## Input Validation

All user inputs are validated before use:

### Device Names
- Pattern: `[a-zA-Z0-9_-]+`
- Maximum length: 64 characters
- No special characters or path traversal

### Profile Tokens
- Pattern: `[a-zA-Z0-9_-]+`
- Maximum length: 64 characters
- Sanitized before XML insertion

### IP Addresses
- Validated via regex pattern
- Supports IPv4 addresses and hostnames

## XML Security

### XXE Protection

All XML parsing is protected against XML External Entity (XXE) attacks:

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

### XML Injection Protection

All user inputs are escaped before insertion into XML:
- `&` → `&amp;`
- `<` → `&lt;`
- `>` → `&gt;`
- `"` → `&quot;`
- `'` → `&apos;`

## CORS Policy

Cross-Origin Resource Sharing (CORS) headers are restricted to known origins only. The wildcard `*` origin is **not** used in production.

## Rate Limiting

### Per-IP Rate Limiting (v2.1.0+)
- **Limit**: 10 requests per minute per IP address
- **Scope**: Applied to snapshot and stream endpoints
- **Response**: HTTP 429 Too Many Requests when exceeded
- **Tracking**: Per-IP via X-Forwarded-For and X-Real-IP headers
- **Proxy-Aware**: Honors reverse proxy headers for accurate IP tracking

### Global Resource Limits
- Maximum concurrent snapshots: 50
- Maximum concurrent streams: 20

Exceeding limits returns HTTP 429 (Too Many Requests).

**DoS Protection**: The per-IP rate limiting prevents abuse and resource exhaustion attacks while allowing legitimate users normal access to camera feeds.

## Known Security Limitations

### SHA-1 Hash Algorithm

ONVIF WS-UsernameToken specification **requires** SHA-1 for password digests. This is a protocol-level limitation, not a code defect.

**Mitigation**:
- Use strong passwords (16+ characters, high entropy)
- Network isolation (VPN, VLAN)
- HTTPS for all ONVIF communication
- Frequent password rotation

**Reference**: ONVIF Core Specification Version 2.0, Section 5.1.1

## Vulnerability Disclosure

If you discover a security vulnerability, please report it responsibly:

### Reporting Security Issues

1. **GitHub Security Advisories** (Preferred):
   - Visit: https://github.com/nigelgwork/ignition-ONVIF-driver/security/advisories
   - Click "Report a vulnerability"
   - Provide detailed description of the vulnerability

2. **GitHub Issues**:
   - Create an issue at: https://github.com/nigelgwork/ignition-ONVIF-driver/issues
   - Mark with "Security" label
   - Include version number, steps to reproduce, and impact assessment

3. **Email**: For sensitive disclosures, contact via GitHub profile

**Response Time**: We aim to respond within 48 hours

**Please do NOT** publicly disclose vulnerabilities until a patch is available and users have been given reasonable time to update (typically 90 days).

## Security Audit History

| Date       | Version | Auditor         | Findings |
|------------|---------|-----------------|----------|
| 2025-11-22 | 1.0.23  | Internal Review | 3 Critical, 5 High |
| 2025-11-22 | 2.0.0   | Internal Review | 2 Critical resolved, 1 Critical remaining (authentication) |
| 2025-11-22 | 2.1.0   | Internal Review | All critical issues resolved + comprehensive testing |

## Compliance Considerations

### GDPR (General Data Protection Regulation)
- Camera surveillance requires proper access controls ✅ (v2.0.0+)
- Audit logging recommended for camera access

### HIPAA (Health Insurance Portability and Accountability Act)
- Healthcare facilities require authentication ✅ (v2.0.0+)
- Encryption in transit recommended (HTTPS)
- Access logging recommended

### PCI-DSS (Payment Card Industry Data Security Standard)
- Payment environments require encryption ✅
- Access control implemented ✅ (v2.0.0+)
- Regular security updates required

## Security Best Practices

### Deployment

1. **Network Isolation**: Place cameras on dedicated VLAN
2. **Firewall Rules**: Restrict camera access to Ignition Gateway only
3. **HTTPS**: Enable SSL/TLS on Ignition Gateway
4. **Strong Passwords**: Use 16+ character passwords for cameras
5. **Regular Updates**: Keep Ignition and modules updated
6. **Monitoring**: Enable access logging and alerting

### Configuration

1. **SSL/TLS Mode**: Use STRICT mode in production
2. **Authentication**: Never disable authentication on endpoints
3. **CORS**: Configure allowed origins explicitly
4. **Rate Limits**: Adjust based on environment needs

### Maintenance

1. **Password Rotation**: Rotate camera passwords quarterly
2. **Certificate Updates**: Renew certificates before expiration
3. **Dependency Updates**: Monitor for security advisories
4. **Audit Logs**: Review access logs regularly

## Dependencies

### Security Scanning

All dependencies are scanned for known vulnerabilities:

```bash
./gradlew dependencyCheckAnalyze
```

### Current Dependencies (v2.0.0)

- `org.apache.httpcomponents:httpclient:4.5.14` - ✅ No critical CVEs
- `org.apache.httpcomponents:httpcore:4.4.16` - ✅ No critical CVEs
- `com.google.code.gson:gson:2.13.2` - ✅ No known vulnerabilities
- `Ignition SDK 8.3.0` - Managed by Ignition platform

## Change Log

### v2.1.0 (2025-11-22) - CURRENT
- **SECURITY**: HTTP endpoint authentication implemented (session, Basic Auth, API key)
- **SECURITY**: Per-IP rate limiting implemented (10 req/min)
- **SECURITY**: 168 comprehensive automated tests including security tests
- **SECURITY**: XSS, SQL injection, JNDI injection, and path traversal protection verified
- **SECURITY**: XXE and Billion Laughs attack prevention tested
- Critical authentication gap from v2.0.0 **RESOLVED**

### v2.0.0 (2025-11-22)
- **SECURITY**: Removed hardcoded credentials from version control
- **SECURITY**: Made SSL/TLS validation configurable (STRICT mode available)
- **SECURITY**: Added ValidationUtil for centralized input validation
- **SECURITY**: Improved CORS policy with origin validation
- **KNOWN LIMITATION**: HTTP endpoints still use OPEN_ROUTE (**FIXED in v2.1.0**)
- Created comprehensive security documentation

### v1.0.23 (Previous)
- Credentials hardcoded (CRITICAL vulnerability - **FIXED** in v2.0.0)
- No authentication on endpoints (CRITICAL vulnerability - **FIXED** in v2.1.0)
- SSL validation always disabled (HIGH vulnerability - **FIXED** in v2.0.0)

## References

- [OWASP Top 10 2021](https://owasp.org/Top10/)
- [ONVIF Core Specification](https://www.onvif.org/specs/core/ONVIF-Core-Specification.pdf)
- [CWE Top 25](https://cwe.mitre.org/top25/)
- [Ignition Security Best Practices](https://docs.inductiveautomation.com/)
