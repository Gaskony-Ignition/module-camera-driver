package com.onvif.driver.common;

/**
 * Known device status values used across ONVIF and Generic Camera devices.
 *
 * Centralises the status strings so that:
 *  - Typos are caught at compile time.
 *  - Comparisons (e.g. isActive()) live in one place rather than being
 *    repeated across ONVIFDevice, GenericCameraDevice and ONVIFRoutes.
 *
 * The devices still store status as a String (to allow dynamic error messages
 * like "Error: connection timeout"), but all *known* status assignments
 * should reference these constants via displayName().
 */
public enum DeviceStatus {

    INITIALIZING("Initializing"),
    CONNECTING("Connecting"),
    DISCOVERING("Discovering Services"),
    BUILDING_ADDRESS_SPACE("Building Address Space"),
    RUNNING("Running"),
    CONNECTED("Connected"),
    URL_ONLY("URL-Only"),
    STOPPED("Stopped"),
    DISABLED("Disabled"),
    ERROR("Error");

    private final String displayName;

    DeviceStatus(String displayName) {
        this.displayName = displayName;
    }

    /** The human-readable status string as shown in the Gateway UI and returned by getStatus(). */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns true if the given raw status string represents an active (streaming-ready) device.
     * Centralises the "Running" || "Connected" check that previously appeared in 3+ places.
     *
     * @param status the raw status string from device.getStatus()
     */
    public static boolean isActive(String status) {
        if (status == null) return false;
        return RUNNING.displayName.equals(status) || CONNECTED.displayName.equals(status);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
