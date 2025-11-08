package com.onvif.driver.gateway.onvif;

/**
 * ONVIF Device Information data model.
 * Contains basic device information returned from GetDeviceInformation request.
 */
public class DeviceInformation {
    private String manufacturer;
    private String model;
    private String firmwareVersion;
    private String serialNumber;
    private String hardwareId;

    public DeviceInformation() {
    }

    public DeviceInformation(String manufacturer, String model, String firmwareVersion,
                           String serialNumber, String hardwareId) {
        this.manufacturer = manufacturer;
        this.model = model;
        this.firmwareVersion = firmwareVersion;
        this.serialNumber = serialNumber;
        this.hardwareId = hardwareId;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
    }

    @Override
    public String toString() {
        return String.format("%s %s (FW: %s, SN: %s)",
            manufacturer, model, firmwareVersion, serialNumber);
    }
}
