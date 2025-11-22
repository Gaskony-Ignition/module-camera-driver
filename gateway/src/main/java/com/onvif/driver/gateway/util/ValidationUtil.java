package com.onvif.driver.gateway.util;

/**
 * Utility class for input validation.
 *
 * Provides centralized validation methods to ensure security and data integrity
 * across the ONVIF driver module.
 */
public class ValidationUtil {

    // Validation patterns
    private static final String DEVICE_NAME_PATTERN = "[a-zA-Z0-9_-]+";
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
}
