package com.gaskony.camera.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the Ignition Maker Edition opt-in.
 *
 * <p>{@code AbstractDeviceModuleHook} (via {@code AbstractGatewayModuleHook}) defaults
 * {@code isMakerEditionCompatible()} to {@code false}; without an explicit override
 * Maker silently refuses to start the module. This guards against the override being
 * lost in a future refactor.
 */
class CameraModuleHookMakerEditionTest {

    @Test
    @DisplayName("isMakerEditionCompatible() returns true so Maker Edition will start the module")
    void isMakerEditionCompatible_returnsTrue() {
        CameraModuleHook hook = new CameraModuleHook();

        assertThat(hook.isMakerEditionCompatible())
            .as("Module must opt in to Maker Edition or it will silently refuse to start")
            .isTrue();
    }
}
