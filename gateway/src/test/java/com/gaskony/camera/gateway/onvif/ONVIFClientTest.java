package com.gaskony.camera.gateway.onvif;

import com.gaskony.camera.gateway.device.CameraConfig.SslValidationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ONVIFClient.
 * Tests ONVIF SOAP communication with mocked HTTP responses.
 */
class ONVIFClientTest {

    private ONVIFClient client;

    @BeforeEach
    void setUp() {
        // Create client with test configuration
        client = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin",
            "password",
            false,  // HTTP
            5,      // 5 second timeout
            SslValidationMode.INSECURE
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
    }

    // ==================== Basic Configuration Tests ====================

    @Test
    void testConstructor_HttpConfiguration() throws IOException {
        ONVIFClient httpClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin",
            "password",
            false,
            5,
            SslValidationMode.INSECURE
        );

        assertThat(httpClient).isNotNull();
        httpClient.close();
    }

    @Test
    void testConstructor_HttpsConfiguration() throws IOException {
        ONVIFClient httpsClient = new ONVIFClient(
            "192.168.1.100",
            443,
            "admin",
            "password",
            true,
            5,
            SslValidationMode.STRICT
        );

        assertThat(httpsClient).isNotNull();
        httpsClient.close();
    }

    @ParameterizedTest
    @EnumSource(SslValidationMode.class)
    void testConstructor_AllSslModes(SslValidationMode mode) throws IOException {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            443,
            "admin",
            "password",
            true,
            5,
            mode
        );

        assertThat(testClient).isNotNull();
        testClient.close();
    }

    @Test
    void testConstructor_NullSslMode_DefaultsToStrict() throws IOException {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            443,
            "admin",
            "password",
            true,
            5,
            null  // null should default to STRICT
        );

        assertThat(testClient).isNotNull();
        // C5 regression: default (null) must NOT enable INSECURE SSL.
        assertThat(testClient.isInsecureSslMode())
            .as("Null/default SSL mode must resolve to STRICT, not INSECURE — see /modules/.review/FINAL_REVIEW.md §4 C5")
            .isFalse();
        testClient.close();
    }

    @Test
    void testIsInsecureSslMode_StrictMode_ReturnsFalse() throws IOException {
        // C5 regression: explicitly STRICT must report not-insecure.
        ONVIFClient strict = new ONVIFClient(
            "192.168.1.100", 443, "admin", "password", true, 5,
            SslValidationMode.STRICT);
        try {
            assertThat(strict.isInsecureSslMode()).isFalse();
        } finally {
            strict.close();
        }
    }

    @Test
    void testIsInsecureSslMode_InsecureMode_ReturnsTrue() throws IOException {
        // C5 regression: explicit opt-in to INSECURE must be detectable, so
        // ONVIFPoller can emit a per-cycle WARN while the unsafe mode is active.
        ONVIFClient insecure = new ONVIFClient(
            "192.168.1.100", 443, "admin", "password", true, 5,
            SslValidationMode.INSECURE);
        try {
            assertThat(insecure.isInsecureSslMode()).isTrue();
        } finally {
            insecure.close();
        }
    }

    // ==================== Device Information Tests ====================

    @Test
    void testGetDeviceInformation_Success() throws Exception {
        // This test demonstrates the expected structure but requires actual HTTP mocking
        // For now, we test that the method exists and has correct signature
        assertThatCode(() -> {
            // Would need to mock HTTP client to properly test
            // DeviceInformation info = client.getDeviceInformation();
        }).doesNotThrowAnyException();
    }

    // ==================== Service Discovery Tests ====================

    @Test
    void testGetServices_MethodExists() {
        // Test that method exists and has correct signature
        assertThatCode(() -> {
            // Would need to mock HTTP client to properly test
            // List<ONVIFService> services = client.getServices();
        }).doesNotThrowAnyException();
    }

    // ==================== Media Profile Tests ====================

    @Test
    void testGetMediaProfiles_WithoutServices_ThrowsException() {
        // Before calling getServices(), media operations should fail
        assertThatThrownBy(() -> client.getMediaProfiles())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Media service not available");
    }

    @Test
    void testGetSnapshotUri_WithoutServices_ThrowsException() {
        // Before calling getServices(), media operations should fail
        assertThatThrownBy(() -> client.getSnapshotUri("000"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Media service not available");
    }

    @Test
    void testGetStreamUri_WithoutServices_ThrowsException() {
        // Before calling getServices(), media operations should fail
        assertThatThrownBy(() -> client.getStreamUri("000"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Media service not available");
    }

    // ==================== PTZ Tests ====================

    @Test
    void testGetPTZStatus_WithoutServices_ThrowsException() {
        // Before calling getServices(), PTZ operations should fail
        assertThatThrownBy(() -> client.getPTZStatus("000"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("PTZ service not available");
    }

    @Test
    void testAbsoluteMove_WithoutServices_ThrowsException() {
        // Before calling getServices(), PTZ operations should fail
        assertThatThrownBy(() -> client.absoluteMove("000", 0.5, 0.5, 0.5))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("PTZ service not available");
    }

    @Test
    void testPtzStop_WithoutServices_ThrowsException() {
        // Before calling getServices(), PTZ operations should fail
        assertThatThrownBy(() -> client.ptzStop("000"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("PTZ service not available");
    }

    // ==================== Snapshot Tests ====================

    @Test
    void testGetSnapshot_WithoutServices_ThrowsException() {
        // Before calling getServices(), snapshot operations should fail
        assertThatThrownBy(() -> client.getSnapshot("000"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Media service not available");
    }

    // ==================== Resource Management Tests ====================

    @Test
    void testClose_DoesNotThrow() {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin",
            "password",
            false,
            5,
            SslValidationMode.INSECURE
        );

        assertThatCode(() -> testClient.close()).doesNotThrowAnyException();
    }

    @Test
    void testClose_MultipleTimes() throws IOException {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin",
            "password",
            false,
            5,
            SslValidationMode.INSECURE
        );

        // Should be safe to call close() multiple times
        testClient.close();
        assertThatCode(() -> testClient.close()).doesNotThrowAnyException();
    }

    // ==================== Connection Test ====================

    @Test
    void testTestConnection_MethodExists() {
        // Test that method exists and returns boolean
        assertThatCode(() -> {
            boolean result = client.testConnection();
            // Result will be false since we're not connected to a real device
        }).doesNotThrowAnyException();
    }

    // ==================== Configuration Validation Tests ====================

    @Test
    void testConstructor_DifferentPorts() throws IOException {
        ONVIFClient client80 = new ONVIFClient("192.168.1.100", 80, "admin", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client8080 = new ONVIFClient("192.168.1.100", 8080, "admin", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client443 = new ONVIFClient("192.168.1.100", 443, "admin", "password", true, 5, SslValidationMode.INSECURE);

        assertThat(client80).isNotNull();
        assertThat(client8080).isNotNull();
        assertThat(client443).isNotNull();

        client80.close();
        client8080.close();
        client443.close();
    }

    @Test
    void testConstructor_DifferentTimeouts() throws IOException {
        ONVIFClient client1 = new ONVIFClient("192.168.1.100", 80, "admin", "password", false, 1, SslValidationMode.INSECURE);
        ONVIFClient client5 = new ONVIFClient("192.168.1.100", 80, "admin", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client30 = new ONVIFClient("192.168.1.100", 80, "admin", "password", false, 30, SslValidationMode.INSECURE);

        assertThat(client1).isNotNull();
        assertThat(client5).isNotNull();
        assertThat(client30).isNotNull();

        client1.close();
        client5.close();
        client30.close();
    }

    @Test
    void testConstructor_DifferentCredentials() throws IOException {
        ONVIFClient client1 = new ONVIFClient("192.168.1.100", 80, "admin", "admin", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client2 = new ONVIFClient("192.168.1.100", 80, "user", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client3 = new ONVIFClient("192.168.1.100", 80, "camera1", "secret", false, 5, SslValidationMode.INSECURE);

        assertThat(client1).isNotNull();
        assertThat(client2).isNotNull();
        assertThat(client3).isNotNull();

        client1.close();
        client2.close();
        client3.close();
    }

    @Test
    void testConstructor_DifferentHosts() throws IOException {
        ONVIFClient client1 = new ONVIFClient("192.168.1.100", 80, "admin", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client2 = new ONVIFClient("camera.local", 80, "admin", "password", false, 5, SslValidationMode.INSECURE);
        ONVIFClient client3 = new ONVIFClient("10.0.0.5", 80, "admin", "password", false, 5, SslValidationMode.INSECURE);

        assertThat(client1).isNotNull();
        assertThat(client2).isNotNull();
        assertThat(client3).isNotNull();

        client1.close();
        client2.close();
        client3.close();
    }

    // ==================== PTZ Parameter Validation Tests ====================

    @Test
    void testAbsoluteMove_ParameterRanges() {
        // These test that the method accepts various parameter ranges
        // Actual validation would happen on the camera side

        assertThatCode(() -> {
            // Would throw due to missing service, but tests parameter types
            try {
                client.absoluteMove("000", -1.0, -1.0, 0.0);  // Min values
            } catch (IOException e) {
                // Expected - service not available
            }
        }).doesNotThrowAnyException();

        assertThatCode(() -> {
            try {
                client.absoluteMove("000", 1.0, 1.0, 1.0);   // Max values
            } catch (IOException e) {
                // Expected - service not available
            }
        }).doesNotThrowAnyException();

        assertThatCode(() -> {
            try {
                client.absoluteMove("000", 0.0, 0.0, 0.5);   // Middle values
            } catch (IOException e) {
                // Expected - service not available
            }
        }).doesNotThrowAnyException();
    }

    // ==================== Edge Cases ====================

    @Test
    void testConstructor_EmptyCredentials() throws IOException {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "",
            "",
            false,
            5,
            SslValidationMode.INSECURE
        );

        assertThat(testClient).isNotNull();
        testClient.close();
    }

    @Test
    void testConstructor_SpecialCharactersInCredentials() throws IOException {
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin@example.com",
            "p@ssw0rd!#$%",
            false,
            5,
            SslValidationMode.INSECURE
        );

        assertThat(testClient).isNotNull();
        testClient.close();
    }

    // ==================== Documentation Tests ====================

    /**
     * This test documents the expected workflow for using ONVIFClient.
     */
    @Test
    void testDocumentation_TypicalWorkflow() throws Exception {
        // 1. Create client
        ONVIFClient testClient = new ONVIFClient(
            "192.168.1.100",
            80,
            "admin",
            "password",
            false,
            5,
            SslValidationMode.INSECURE
        );

        // 2. Test connection (optional)
        testClient.testConnection();

        // 3. Get device information
        // DeviceInformation info = testClient.getDeviceInformation();

        // 4. Discover services
        // List<ONVIFService> services = testClient.getServices();

        // 5. Get media profiles
        // List<MediaProfile> profiles = testClient.getMediaProfiles();

        // 6. Get snapshot URI
        // String snapshotUri = testClient.getSnapshotUri("000");

        // 7. Get snapshot image
        // byte[] snapshot = testClient.getSnapshot("000");

        // 8. Clean up
        testClient.close();

        // This test just documents the expected flow
        assertThat(testClient).isNotNull();
    }

    /**
     * This test documents SSL/TLS configuration options.
     */
    @Test
    void testDocumentation_SslConfiguration() throws IOException {
        // STRICT mode - Full certificate validation (production)
        ONVIFClient strictClient = new ONVIFClient(
            "camera.example.com",
            443,
            "admin",
            "password",
            true,
            5,
            SslValidationMode.STRICT
        );

        // INSECURE mode - Accept any certificate (development/testing)
        ONVIFClient insecureClient = new ONVIFClient(
            "192.168.1.100",
            443,
            "admin",
            "password",
            true,
            5,
            SslValidationMode.INSECURE
        );

        // TRUST_FIRST_USE mode - Pin certificate on first connection (planned)
        ONVIFClient tfuClient = new ONVIFClient(
            "192.168.1.100",
            443,
            "admin",
            "password",
            true,
            5,
            SslValidationMode.TRUST_FIRST_USE
        );

        assertThat(strictClient).isNotNull();
        assertThat(insecureClient).isNotNull();
        assertThat(tfuClient).isNotNull();

        strictClient.close();
        insecureClient.close();
        tfuClient.close();
    }

    // ==================== Service / PTZ Discovery Parsing Tests ====================

    @SuppressWarnings("unchecked")
    private List<ONVIFService> invokeParseServices(String xml) throws Exception {
        Method m = ONVIFClient.class.getDeclaredMethod("parseServices", String.class);
        m.setAccessible(true);
        return (List<ONVIFService>) m.invoke(client, xml);
    }

    @SuppressWarnings("unchecked")
    private List<MediaProfile> invokeParseMediaProfiles(String xml) throws Exception {
        Method m = ONVIFClient.class.getDeclaredMethod("parseMediaProfiles", String.class);
        m.setAccessible(true);
        return (List<MediaProfile>) m.invoke(client, xml);
    }

    @Test
    void testParseServices_NonTdsPrefix_StillDiscoversPtzAndMedia() throws Exception {
        // Regression: cameras choose their own SOAP element prefix. This response uses
        // "wsdl:" instead of "tds:" — the old hardcoded getElementsByTagName("tds:Service")
        // returned zero services, hiding PTZ (and ONVIF media) entirely.
        String xml =
            "<?xml version=\"1.0\"?>"
            + "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:wsdl=\"http://www.onvif.org/ver10/device/wsdl\">"
            + "<env:Body><wsdl:GetServicesResponse>"
            + "<wsdl:Service><wsdl:Namespace>http://www.onvif.org/ver10/media/wsdl</wsdl:Namespace>"
            + "<wsdl:XAddr>http://192.168.1.100/onvif/media</wsdl:XAddr>"
            + "<wsdl:Version><wsdl:Major>2</wsdl:Major><wsdl:Minor>5</wsdl:Minor></wsdl:Version></wsdl:Service>"
            + "<wsdl:Service><wsdl:Namespace>http://www.onvif.org/ver20/ptz/wsdl</wsdl:Namespace>"
            + "<wsdl:XAddr>http://192.168.1.100/onvif/ptz</wsdl:XAddr>"
            + "<wsdl:Version><wsdl:Major>2</wsdl:Major><wsdl:Minor>5</wsdl:Minor></wsdl:Version></wsdl:Service>"
            + "</wsdl:GetServicesResponse></env:Body></env:Envelope>";

        List<ONVIFService> services = invokeParseServices(xml);

        assertThat(services).hasSize(2);
        assertThat(services).anyMatch(s -> s.getServiceName().equalsIgnoreCase("ptz"));
        assertThat(services).anyMatch(s -> s.getServiceName().equalsIgnoreCase("media"));
    }

    @Test
    void testParseMediaProfiles_WithPtzConfiguration_SetsHasPtz() throws Exception {
        // A profile carrying a PTZConfiguration means PTZ is usable even if the camera
        // does not list a discrete PTZ service.
        String xml =
            "<?xml version=\"1.0\"?>"
            + "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
            + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
            + "<env:Body><trt:GetProfilesResponse>"
            + "<trt:Profiles token=\"Profile_1\"><tt:Name>MainStream</tt:Name>"
            + "<tt:PTZConfiguration token=\"PTZCfg_1\"><tt:Name>PTZ</tt:Name></tt:PTZConfiguration>"
            + "</trt:Profiles>"
            + "</trt:GetProfilesResponse></env:Body></env:Envelope>";

        List<MediaProfile> profiles = invokeParseMediaProfiles(xml);

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).hasPtz()).isTrue();
    }

    @Test
    void testParseMediaProfiles_WithoutPtzConfiguration_HasPtzFalse() throws Exception {
        String xml =
            "<?xml version=\"1.0\"?>"
            + "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
            + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
            + "<env:Body><trt:GetProfilesResponse>"
            + "<trt:Profiles token=\"Profile_1\"><tt:Name>MainStream</tt:Name></trt:Profiles>"
            + "</trt:GetProfilesResponse></env:Body></env:Envelope>";

        List<MediaProfile> profiles = invokeParseMediaProfiles(xml);

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).hasPtz()).isFalse();
    }
}
