package com.gaskony.camera.gateway.device;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic thread-safe device registry.
 * Replaces duplicated static ConcurrentHashMap boilerplate in extension points.
 *
 * @param <T> The device type (e.g. ONVIFDevice, GenericCameraDevice)
 */
public class DeviceRegistry<T> {

    private final Map<String, T> devices = new ConcurrentHashMap<>();

    /** Registers a device under the given name. Replaces any existing entry. */
    public void register(String name, T device) {
        devices.put(name, device);
    }

    /** Removes a device by name. No-op if not found. */
    public void unregister(String name) {
        devices.remove(name);
    }

    /** Returns the device with the given name, or null if not found. */
    public T get(String name) {
        return devices.get(name);
    }

    /** Returns an unmodifiable snapshot of all registered devices. */
    public Map<String, T> getAll() {
        return Collections.unmodifiableMap(devices);
    }

    /** Returns the number of registered devices. */
    public int size() {
        return devices.size();
    }
}
