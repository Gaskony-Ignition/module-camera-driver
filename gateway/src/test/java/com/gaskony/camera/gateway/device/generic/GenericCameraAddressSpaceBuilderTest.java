package com.gaskony.camera.gateway.device.generic;

import com.gaskony.camera.common.CameraDriverPaths;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure-logic static helper methods extracted from
 * GenericCameraAddressSpaceBuilder.
 *
 * The OPC-UA node-wiring code (folder/variable creation, reference linking) requires a
 * live Milo server and is not covered here. These tests focus exclusively on the
 * side-effect-free static methods:
 *
 *   urlEncode(String)              — URL-encodes a parameter value
 *   buildSnapshotEndpoint(String)  — builds the Ignition snapshot gateway endpoint URL
 *   buildStreamEndpoint(String, int) — builds the Ignition stream gateway endpoint URL
 *   selectDataType(Object)         — maps a Java type to the correct OPC-UA NodeId
 */
class GenericCameraAddressSpaceBuilderTest {

    // ======================================================================
    // urlEncode
    // ======================================================================

    @Test
    void testUrlEncode_PlainText_ReturnedUnchanged() {
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("Camera1")).isEqualTo("Camera1");
    }

    @Test
    void testUrlEncode_Spaces_EncodedAsPlus() {
        // java.net.URLEncoder encodes spaces as '+' (application/x-www-form-urlencoded)
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("Lobby Camera"))
            .isEqualTo("Lobby+Camera");
    }

    @Test
    void testUrlEncode_Ampersand_Encoded() {
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("a&b")).isEqualTo("a%26b");
    }

    @Test
    void testUrlEncode_Equals_Encoded() {
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("k=v")).isEqualTo("k%3Dv");
    }

    @Test
    void testUrlEncode_Slash_Encoded() {
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("path/to")).isEqualTo("path%2Fto");
    }

    @Test
    void testUrlEncode_EmptyString_ReturnsEmpty() {
        assertThat(GenericCameraAddressSpaceBuilder.urlEncode("")).isEmpty();
    }

    @Test
    void testUrlEncode_MultipleSpecialChars_AllEncoded() {
        String encoded = GenericCameraAddressSpaceBuilder.urlEncode("a b&c=d");
        assertThat(encoded)
            .doesNotContain(" ")
            .doesNotContain("&")
            .doesNotContain("=")
            .isEqualTo("a+b%26c%3Dd");
    }

    // ======================================================================
    // buildSnapshotEndpoint
    // ======================================================================

    @Test
    void testBuildSnapshotEndpoint_ContainsDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("MyCam");
        assertThat(url).contains("device=MyCam");
    }

    @Test
    void testBuildSnapshotEndpoint_UsesCorrectBasePath() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("cam");
        assertThat(url).startsWith(CameraDriverPaths.DATA_BASE + CameraDriverPaths.ROUTE_SNAPSHOT);
    }

    @Test
    void testBuildSnapshotEndpoint_ExactFormat() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("cam1");
        assertThat(url).isEqualTo("/data/camera-driver/snapshot?device=cam1");
    }

    @Test
    void testBuildSnapshotEndpoint_EncodesSpacesInDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("Lobby Camera");
        assertThat(url).contains("device=Lobby+Camera");
        assertThat(url).doesNotContain("device=Lobby Camera");
    }

    @Test
    void testBuildSnapshotEndpoint_EncodesAmpersandInDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("A&B");
        assertThat(url).contains("device=A%26B");
    }

    @Test
    void testBuildSnapshotEndpoint_EncodesEqualsInDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildSnapshotEndpoint("k=v");
        assertThat(url).contains("device=k%3Dv");
    }

    // ======================================================================
    // buildStreamEndpoint
    // ======================================================================

    @Test
    void testBuildStreamEndpoint_ContainsDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("MyCam", 15);
        assertThat(url).contains("device=MyCam");
    }

    @Test
    void testBuildStreamEndpoint_ContainsFps() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("cam", 15);
        assertThat(url).contains("fps=15");
    }

    @Test
    void testBuildStreamEndpoint_UsesCorrectBasePath() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("cam", 10);
        assertThat(url).startsWith(CameraDriverPaths.DATA_BASE + CameraDriverPaths.ROUTE_STREAM);
    }

    @Test
    void testBuildStreamEndpoint_ExactFormat() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("cam1", 15);
        assertThat(url).isEqualTo("/data/camera-driver/stream?device=cam1&fps=15");
    }

    @Test
    void testBuildStreamEndpoint_EncodesDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("Side Camera", 10);
        assertThat(url).contains("device=Side+Camera");
        assertThat(url).doesNotContain("device=Side Camera");
    }

    @Test
    void testBuildStreamEndpoint_EncodesAmpersandInDeviceName() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("A&B", 10);
        assertThat(url).contains("device=A%26B");
    }

    @Test
    void testBuildStreamEndpoint_ZeroFps_AllowedInUrl() {
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("cam", 0);
        assertThat(url).contains("fps=0");
    }

    @Test
    void testBuildStreamEndpoint_DefaultFps_MatchesConfigDefault() {
        // Config default is 15 per @DefaultValue in GenericCameraConfig.StreamSettings
        String url = GenericCameraAddressSpaceBuilder.buildStreamEndpoint("cam", 15);
        assertThat(url).contains("fps=15");
    }

    // ======================================================================
    // selectDataType
    // ======================================================================

    @Test
    void testSelectDataType_String_ReturnsStringNodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType("hello");
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_Integer_ReturnsInt32NodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(42);
        assertThat(result).isEqualTo(Identifiers.Int32);
    }

    @Test
    void testSelectDataType_Long_ReturnsInt64NodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(123L);
        assertThat(result).isEqualTo(Identifiers.Int64);
    }

    @Test
    void testSelectDataType_Double_ReturnsDoubleNodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(3.14);
        assertThat(result).isEqualTo(Identifiers.Double);
    }

    @Test
    void testSelectDataType_Boolean_ReturnsBooleanNodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(true);
        assertThat(result).isEqualTo(Identifiers.Boolean);
    }

    @Test
    void testSelectDataType_BooleanFalse_ReturnsBooleanNodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(false);
        assertThat(result).isEqualTo(Identifiers.Boolean);
    }

    @Test
    void testSelectDataType_UnknownType_DefaultsToString() {
        // Object types not in the handled set should fall back to String
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(new Object());
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_FloatIsNotHandled_DefaultsToString() {
        // Float is deliberately not in the handled set; defaults to String
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(1.5f);
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_EmptyString_ReturnsStringNodeId() {
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType("");
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_LongBoxed_ReturnsInt64NodeId() {
        Long boxed = Long.valueOf(9_000_000_000L);
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(boxed);
        assertThat(result).isEqualTo(Identifiers.Int64);
    }

    @Test
    void testSelectDataType_IntegerBoxed_ReturnsInt32NodeId() {
        Integer boxed = Integer.valueOf(0);
        NodeId result = GenericCameraAddressSpaceBuilder.selectDataType(boxed);
        assertThat(result).isEqualTo(Identifiers.Int32);
    }
}
