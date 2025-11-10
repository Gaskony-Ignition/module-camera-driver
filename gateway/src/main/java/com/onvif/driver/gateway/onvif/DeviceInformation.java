package com.onvif.driver.gateway.onvif;

/**
 * ONVIF Device Information data model.
 * Contains basic device information returned from GetDeviceInformation request.
 * Immutable record for thread-safe access to device metadata.
 */
public record DeviceInformation(
    String manufacturer,
    String model,
    String firmwareVersion,
    String serialNumber,
    String hardwareId
) {
    @Override
    public String toString() {
        return String.format("%s %s (FW: %s, SN: %s)",
            manufacturer, model, firmwareVersion, serialNumber);
    }
}
