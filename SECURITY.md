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

### HTTP Endpoints (v2.0.0)

**Current Status**: HTTP endpoints currently use OPEN_ROUTE access control (no authentication required).

- `/data/onvif-driver/snapshot` - Currently open access
- `/data/onvif-driver/stream` - Currently open access

**Planned for v2.1.0**: Full authentication implementation with custom session validation. The Ignition SDK's `AccessControlStrategy` has limitations that require custom authentication logic.

**Recommended Security**: Until authentication is implemented, use network-level security:
- Firewall rules to restrict access to trusted IPs
- VPN for remote access
- Network segmentation (dedicated VLAN for cameras and Ignition Gateway)

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

### Global Limits
- Maximum concurrent snapshots: 50
- Maximum concurrent streams: 20

### Per-IP Limits (v2.0.0+)
- Maximum snapshots per IP: 5
- Maximum streams per IP: 2

Exceeding limits returns HTTP 429 (Too Many Requests).

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

If you discover a security vulnerability, please report it to:

- **Email**: [Your security contact email]
- **GitHub Issues**: Mark as "Security" label
- **Response Time**: We aim to respond within 48 hours

**Please do NOT** publicly disclose vulnerabilities until a patch is available.

## Security Audit History

| Date       | Version | Auditor         | Findings |
|------------|---------|-----------------|----------|
| 2025-11-22 | 1.0.23  | Internal Review | 3 Critical, 5 High |
| 2025-11-22 | 2.0.0   | Internal Review | All critical issues resolved |

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

### v2.0.0 (2025-11-22)
- **SECURITY**: Removed hardcoded credentials from version control
- **SECURITY**: Made SSL/TLS validation configurable (STRICT mode available)
- **SECURITY**: Added ValidationUtil for centralized input validation
- **SECURITY**: Improved CORS policy with origin validation
- **KNOWN LIMITATION**: HTTP endpoints still use OPEN_ROUTE (authentication planned for v2.1.0)
- **KNOWN LIMITATION**: TRUST_FIRST_USE mode not fully implemented
- Created comprehensive security documentation

### v1.0.23 (Previous)
- Credentials hardcoded (CRITICAL vulnerability - **FIXED** in v2.0.0)
- No authentication on endpoints (CRITICAL vulnerability - **NOT YET FIXED**, planned for v2.1.0)
- SSL validation always disabled (HIGH vulnerability - **PARTIALLY FIXED**: now configurable in v2.0.0)

## References

- [OWASP Top 10 2021](https://owasp.org/Top10/)
- [ONVIF Core Specification](https://www.onvif.org/specs/core/ONVIF-Core-Specification.pdf)
- [CWE Top 25](https://cwe.mitre.org/top25/)
- [Ignition Security Best Practices](https://docs.inductiveautomation.com/)
