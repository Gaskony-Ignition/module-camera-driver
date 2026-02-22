package com.onvif.driver.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DeviceStatus.
 * Tests the enum constants, isActive() logic, displayName(), and toString().
 */
class DeviceStatusTest {

    // ==================== isActive() — Active Statuses ====================

    @Test
    void testIsActive_Running_ReturnsTrue() {
        assertThat(DeviceStatus.isActive("Running")).isTrue();
    }

    @Test
    void testIsActive_Connected_ReturnsTrue() {
        assertThat(DeviceStatus.isActive("Connected")).isTrue();
    }

    // ==================== isActive() — Inactive Statuses ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "Stopped",
        "Error",
        "Disabled",
        "Initializing",
        "Connecting",
        "Discovering Services",
        "Building Address Space",
        "URL-Only"
    })
    void testIsActive_InactiveStatuses_ReturnsFalse(String status) {
        assertThat(DeviceStatus.isActive(status)).isFalse();
    }

    // ==================== isActive() — Null and Empty Safety ====================

    @ParameterizedTest
    @NullAndEmptySource
    void testIsActive_NullOrEmpty_ReturnsFalse(String status) {
        assertThat(DeviceStatus.isActive(status)).isFalse();
    }

    // ==================== isActive() — Case Sensitivity ====================

    @ParameterizedTest
    @ValueSource(strings = {"running", "RUNNING", "connected", "CONNECTED", "RuNnInG", "CoNnEcTeD"})
    void testIsActive_WrongCase_ReturnsFalse(String status) {
        // isActive() is case-sensitive — only exact matches work
        assertThat(DeviceStatus.isActive(status)).isFalse();
    }

    // ==================== isActive() — Partial Matches ====================

    @ParameterizedTest
    @ValueSource(strings = {"Run", "Connect", " Running", "Running ", "Running\n"})
    void testIsActive_PartialOrPaddedStatus_ReturnsFalse(String status) {
        assertThat(DeviceStatus.isActive(status)).isFalse();
    }

    // ==================== isActive() matches enum displayNames ====================

    @Test
    void testIsActive_MatchesRunningDisplayName() {
        // isActive() should accept exactly what displayName() produces for RUNNING
        assertThat(DeviceStatus.isActive(DeviceStatus.RUNNING.displayName())).isTrue();
    }

    @Test
    void testIsActive_MatchesConnectedDisplayName() {
        // isActive() should accept exactly what displayName() produces for CONNECTED
        assertThat(DeviceStatus.isActive(DeviceStatus.CONNECTED.displayName())).isTrue();
    }

    @Test
    void testIsActive_OtherEnumDisplayNames_ReturnFalse() {
        // All non-active enum display names must return false from isActive()
        for (DeviceStatus status : DeviceStatus.values()) {
            if (status != DeviceStatus.RUNNING && status != DeviceStatus.CONNECTED) {
                assertThat(DeviceStatus.isActive(status.displayName()))
                    .as("Expected isActive() to be false for status: %s", status.displayName())
                    .isFalse();
            }
        }
    }

    // ==================== displayName() Tests ====================

    @Test
    void testDisplayName_Initializing() {
        assertThat(DeviceStatus.INITIALIZING.displayName()).isEqualTo("Initializing");
    }

    @Test
    void testDisplayName_Connecting() {
        assertThat(DeviceStatus.CONNECTING.displayName()).isEqualTo("Connecting");
    }

    @Test
    void testDisplayName_Discovering() {
        assertThat(DeviceStatus.DISCOVERING.displayName()).isEqualTo("Discovering Services");
    }

    @Test
    void testDisplayName_BuildingAddressSpace() {
        assertThat(DeviceStatus.BUILDING_ADDRESS_SPACE.displayName()).isEqualTo("Building Address Space");
    }

    @Test
    void testDisplayName_Running() {
        assertThat(DeviceStatus.RUNNING.displayName()).isEqualTo("Running");
    }

    @Test
    void testDisplayName_Connected() {
        assertThat(DeviceStatus.CONNECTED.displayName()).isEqualTo("Connected");
    }

    @Test
    void testDisplayName_UrlOnly() {
        assertThat(DeviceStatus.URL_ONLY.displayName()).isEqualTo("URL-Only");
    }

    @Test
    void testDisplayName_Stopped() {
        assertThat(DeviceStatus.STOPPED.displayName()).isEqualTo("Stopped");
    }

    @Test
    void testDisplayName_Disabled() {
        assertThat(DeviceStatus.DISABLED.displayName()).isEqualTo("Disabled");
    }

    @Test
    void testDisplayName_Error() {
        assertThat(DeviceStatus.ERROR.displayName()).isEqualTo("Error");
    }

    @Test
    void testDisplayName_NeverNullOrEmpty() {
        for (DeviceStatus status : DeviceStatus.values()) {
            assertThat(status.displayName())
                .as("displayName() must not be null or empty for %s", status.name())
                .isNotNull()
                .isNotEmpty();
        }
    }

    // ==================== toString() Tests ====================

    @Test
    void testToString_MatchesDisplayName_ForAllValues() {
        for (DeviceStatus status : DeviceStatus.values()) {
            assertThat(status.toString())
                .as("toString() must equal displayName() for %s", status.name())
                .isEqualTo(status.displayName());
        }
    }

    @Test
    void testToString_Running() {
        assertThat(DeviceStatus.RUNNING.toString()).isEqualTo("Running");
    }

    @Test
    void testToString_Connected() {
        assertThat(DeviceStatus.CONNECTED.toString()).isEqualTo("Connected");
    }

    // ==================== Enum Completeness Tests ====================

    @Test
    void testAllEnumValues_CountIsCorrect() {
        // There are exactly 10 status values defined in the enum
        assertThat(DeviceStatus.values()).hasSize(10);
    }

    @Test
    void testAllEnumValues_ContainsExpectedConstants() {
        assertThat(DeviceStatus.values()).containsExactlyInAnyOrder(
            DeviceStatus.INITIALIZING,
            DeviceStatus.CONNECTING,
            DeviceStatus.DISCOVERING,
            DeviceStatus.BUILDING_ADDRESS_SPACE,
            DeviceStatus.RUNNING,
            DeviceStatus.CONNECTED,
            DeviceStatus.URL_ONLY,
            DeviceStatus.STOPPED,
            DeviceStatus.DISABLED,
            DeviceStatus.ERROR
        );
    }

    @Test
    void testActiveCount_ExactlyTwoActiveStatuses() {
        // Only RUNNING and CONNECTED are active
        long activeCount = 0;
        for (DeviceStatus status : DeviceStatus.values()) {
            if (DeviceStatus.isActive(status.displayName())) {
                activeCount++;
            }
        }
        assertThat(activeCount).isEqualTo(2);
    }
}
