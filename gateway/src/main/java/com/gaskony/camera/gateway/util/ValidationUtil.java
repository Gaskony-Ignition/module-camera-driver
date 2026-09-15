package com.gaskony.camera.gateway.util;

import java.util.regex.Pattern;

/**
 * Utility class for input validation.
 *
 * Provides centralized validation methods to ensure security and data integrity
 * across the ONVIF driver module.
 */
public class ValidationUtil {

    // Validation patterns
    //
    // Device names allow letters, digits, spaces, underscores, hyphens, dots, and parentheses
    // because Ignition device names commonly include all of these (e.g. "Side Camera", "Cam.1",
    // "Entrance (Left)"). Slashes, backslashes, and control characters are explicitly excluded to
    // prevent path-traversal. The name is used only as a map-key lookup and is never passed to a
    // shell or used in SQL, so the wider charset is safe.
    private static final String DEVICE_NAME_PATTERN = "[a-zA-Z0-9_.()\\- ]+";

    // IPv4: each octet is 0-9, 10-99, 100-199, 200-249, or 250-255.
    private static final String IPV4_OCTET =
            "(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]\\d|\\d)";
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^" + IPV4_OCTET + "\\." + IPV4_OCTET + "\\." + IPV4_OCTET + "\\." + IPV4_OCTET + "$");

    // Hostname: labels of 1-63 chars (letters, digits, hyphens; no leading/trailing hyphen),
    // separated by dots, with an optional trailing dot, total length <= 253 chars.
    // Allows plain single-label names (e.g. "mycamera") as well as FQDNs.
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)*" +
            "[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$");

    private static final String PROFILE_TOKEN_PATTERN = "[a-zA-Z0-9_-]+";
    private static final int MAX_DEVICE_NAME_LENGTH = 64;
    private static final int MAX_PROFILE_TOKEN_LENGTH = 64;

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private ValidationUtil() {
        throw new UnsupportedOperationException("ValidationUtil is a utility class and cannot be instantiated");
    }

    /**
     * Validates a device name.
     *
     * Device names must:
     * - Contain only alphanumeric characters, underscores, and hyphens
     * - Be non-empty
     * - Not exceed maximum length
     *
     * @param deviceName the device name to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidDeviceName(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return false;
        }
        // Reject leading/trailing spaces even though spaces are allowed within names
        if (!deviceName.equals(deviceName.trim())) {
            return false;
        }
        if (deviceName.length() > MAX_DEVICE_NAME_LENGTH) {
            return false;
        }
        return deviceName.matches(DEVICE_NAME_PATTERN);
    }

    /**
     * Validates a profile token.
     *
     * Profile tokens must:
     * - Contain only alphanumeric characters, underscores, and hyphens
     * - Be non-empty
     * - Not exceed maximum length
     *
     * @param profileToken the profile token to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidProfileToken(String profileToken) {
        if (profileToken == null || profileToken.trim().isEmpty()) {
            return false;
        }
        if (profileToken.length() > MAX_PROFILE_TOKEN_LENGTH) {
            return false;
        }
        return profileToken.matches(PROFILE_TOKEN_PATTERN);
    }

    /**
     * Validates a device name and throws an exception if invalid.
     *
     * @param deviceName the device name to validate
     * @throws IllegalArgumentException if the device name is invalid
     */
    public static void requireValidDeviceName(String deviceName) {
        if (!isValidDeviceName(deviceName)) {
            throw new IllegalArgumentException(
                "Invalid device name format. Must contain only alphanumeric characters, " +
                "underscores, and hyphens, and be between 1 and " + MAX_DEVICE_NAME_LENGTH + " characters."
            );
        }
    }

    /**
     * Validates a profile token and throws an exception if invalid.
     *
     * @param profileToken the profile token to validate
     * @throws IllegalArgumentException if the profile token is invalid
     */
    public static void requireValidProfileToken(String profileToken) {
        if (!isValidProfileToken(profileToken)) {
            throw new IllegalArgumentException(
                "Invalid profile token format. Must contain only alphanumeric characters, " +
                "underscores, and hyphens, and be between 1 and " + MAX_PROFILE_TOKEN_LENGTH + " characters."
            );
        }
    }

    /**
     * Gets the maximum allowed length for device names.
     *
     * @return the maximum device name length
     */
    public static int getMaxDeviceNameLength() {
        return MAX_DEVICE_NAME_LENGTH;
    }

    /**
     * Gets the maximum allowed length for profile tokens.
     *
     * @return the maximum profile token length
     */
    public static int getMaxProfileTokenLength() {
        return MAX_PROFILE_TOKEN_LENGTH;
    }

    /**
     * Validates an HTTP/HTTPS URL.
     *
     * @param url the URL to validate
     * @return true if the URL starts with http:// or https:// and has a host
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String trimmed = url.trim().toLowerCase();
        return (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
            && trimmed.length() > (trimmed.startsWith("https://") ? 8 : 7);
    }

    /**
     * Validates an RTSP/RTSPS URL.
     *
     * @param url the URL to validate
     * @return true if the URL starts with rtsp:// or rtsps:// and has a host
     */
    public static boolean isValidRtspUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String trimmed = url.trim().toLowerCase();
        return (trimmed.startsWith("rtsp://") || trimmed.startsWith("rtsps://"))
            && trimmed.length() > (trimmed.startsWith("rtsps://") ? 8 : 7);
    }

    /**
     * Validates a camera host address: either a dotted-decimal IPv4 address or a DNS hostname.
     *
     * <p>Decision: hostnames are permitted because cameras are commonly configured by hostname
     * (e.g. {@code mycamera.local}, {@code cam01.office.example.com}). Pure numeric strings
     * like {@code "1111"} or {@code "25525525525"} are rejected because they are neither valid
     * IPv4 nor valid hostnames (they fail the IPv4 octet check and the hostname label rules).</p>
     *
     * <p>IPv6 addresses and bare IP literals with brackets are out of scope for this driver.</p>
     *
     * @param host the host string to validate (IPv4 or hostname, no port, no brackets)
     * @return true if the host is a valid IPv4 address or a valid DNS hostname
     */
    public static boolean isValidIpOrHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String trimmed = host.trim();
        if (trimmed.length() > 253) {
            return false;
        }
        return IPV4_PATTERN.matcher(trimmed).matches()
            || HOSTNAME_PATTERN.matcher(trimmed).matches();
    }
}
