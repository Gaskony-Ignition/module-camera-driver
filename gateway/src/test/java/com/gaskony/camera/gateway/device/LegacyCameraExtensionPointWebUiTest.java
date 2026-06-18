package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ExtensionPoint.ComponentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the legacy device-type editor behaviour: a device left on the
 * pre-v3.0.0 {@code com.onvif.driver.Camera} type must stay EDITABLE (so an
 * upgrade never strands a device on "Web UI Component type not found"), while
 * the legacy type remains hidden from the "Add Device" dropdown.
 */
class LegacyCameraExtensionPointWebUiTest {

    @Test
    void addForm_isHiddenFromAddDeviceDropdown() {
        LegacyCameraExtensionPoint ep = new LegacyCameraExtensionPoint();
        assertThat(ep.getWebUiComponent(ComponentType.ADD_FORM))
            .as("legacy type must NOT be creatable from the Add Device dropdown")
            .isEmpty();
    }

    @Test
    void editForm_returnsEditorSoLegacyDevicesStayEditable() {
        LegacyCameraExtensionPoint ep = new LegacyCameraExtensionPoint();
        assertThat(ep.getWebUiComponent(ComponentType.EDIT_FORM))
            .as("existing legacy-typed devices must remain editable after upgrade")
            .isPresent();
    }
}
