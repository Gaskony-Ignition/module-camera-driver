package com.onvif.driver.gateway.onvif;

/**
 * ONVIF Service information.
 * Represents an available ONVIF service endpoint.
 */
public class ONVIFService {
    private String namespace;
    private String xAddr;
    private String version;

    public ONVIFService() {
    }

    public ONVIFService(String namespace, String xAddr, String version) {
        this.namespace = namespace;
        this.xAddr = xAddr;
        this.version = version;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getXAddr() {
        return xAddr;
    }

    public void setXAddr(String xAddr) {
        this.xAddr = xAddr;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getServiceName() {
        if (namespace == null) {
            return "Unknown";
        }
        // Extract service name from namespace
        // e.g., "http://www.onvif.org/ver10/device/wsdl" -> "Device"
        String[] parts = namespace.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty() && !parts[i].equals("wsdl")) {
                return capitalize(parts[i]);
            }
        }
        return "Unknown";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public String toString() {
        return String.format("%s Service (v%s): %s", getServiceName(), version, xAddr);
    }
}
