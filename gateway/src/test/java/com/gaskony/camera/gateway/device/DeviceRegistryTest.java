package com.gaskony.camera.gateway.device;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DeviceRegistryTest {

    private DeviceRegistry<String> registry; // String as a simple stand-in for a device type

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry<>();
    }

    @Test
    void testRegisterAndGet_ReturnsDevice() {
        registry.register("cam1", "CameraA");
        assertThat(registry.get("cam1")).isEqualTo("CameraA");
    }

    @Test
    void testGet_UnknownName_ReturnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void testUnregister_RemovesDevice() {
        registry.register("cam1", "CameraA");
        registry.unregister("cam1");
        assertThat(registry.get("cam1")).isNull();
    }

    @Test
    void testUnregister_NonExistent_DoesNotThrow() {
        assertThatCode(() -> registry.unregister("missing")).doesNotThrowAnyException();
    }

    @Test
    void testGetAll_ReturnsAllDevices() {
        registry.register("cam1", "CameraA");
        registry.register("cam2", "CameraB");
        Map<String, String> all = registry.getAll();
        assertThat(all).hasSize(2).containsEntry("cam1", "CameraA").containsEntry("cam2", "CameraB");
    }

    @Test
    void testGetAll_IsUnmodifiable() {
        registry.register("cam1", "CameraA");
        Map<String, String> all = registry.getAll();
        assertThatThrownBy(() -> all.put("cam2", "CameraB"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testSize_ReflectsRegistrations() {
        assertThat(registry.size()).isZero();
        registry.register("cam1", "CameraA");
        assertThat(registry.size()).isEqualTo(1);
        registry.register("cam2", "CameraB");
        assertThat(registry.size()).isEqualTo(2);
        registry.unregister("cam1");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void testRegister_Overwrite_ReplacesExistingEntry() {
        registry.register("cam1", "CameraA");
        registry.register("cam1", "CameraB_Updated");
        assertThat(registry.get("cam1")).isEqualTo("CameraB_Updated");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void testGetAll_InitiallyEmpty() {
        assertThat(registry.getAll()).isEmpty();
    }
}
