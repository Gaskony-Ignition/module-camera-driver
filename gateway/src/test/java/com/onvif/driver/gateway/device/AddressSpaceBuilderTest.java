package com.onvif.driver.gateway.device;

import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure-logic static helper methods extracted from AddressSpaceBuilder.
 *
 * The OPC-UA node-wiring code (folder/variable creation, reference linking) requires a
 * live Milo server and is not covered here. These tests focus exclusively on the three
 * side-effect-free static methods that contain independently verifiable business logic:
 *
 *   urlEncode(String)              — URL-encodes a parameter value
 *   buildSnapshotUrl(String, String) — builds the Ignition snapshot endpoint URL
 *   buildStreamUrl(String, String, int) — builds the Ignition stream endpoint URL
 *   selectDataType(Object)         — maps a Java type to the correct OPC-UA NodeId
 */
class AddressSpaceBuilderTest {

    // ======================================================================
    // urlEncode
    // ======================================================================

    @Test
    void testUrlEncode_PlainText_ReturnedUnchanged() {
        assertThat(AddressSpaceBuilder.urlEncode("Camera1")).isEqualTo("Camera1");
    }

    @Test
    void testUrlEncode_Spaces_EncodedAsPlus() {
        // java.net.URLEncoder encodes spaces as '+' (application/x-www-form-urlencoded)
        assertThat(AddressSpaceBuilder.urlEncode("Front Camera")).isEqualTo("Front+Camera");
    }

    @Test
    void testUrlEncode_Ampersand_Encoded() {
        assertThat(AddressSpaceBuilder.urlEncode("a&b")).isEqualTo("a%26b");
    }

    @Test
    void testUrlEncode_Equals_Encoded() {
        assertThat(AddressSpaceBuilder.urlEncode("k=v")).isEqualTo("k%3Dv");
    }

    @Test
    void testUrlEncode_Slash_Encoded() {
        assertThat(AddressSpaceBuilder.urlEncode("path/to")).isEqualTo("path%2Fto");
    }

    @Test
    void testUrlEncode_EmptyString_ReturnsEmpty() {
        assertThat(AddressSpaceBuilder.urlEncode("")).isEmpty();
    }

    @Test
    void testUrlEncode_MultipleSpecialChars_AllEncoded() {
        // '&', '=' and a space must all be encoded
        String encoded = AddressSpaceBuilder.urlEncode("a b&c=d");
        assertThat(encoded)
            .doesNotContain(" ")
            .doesNotContain("&")
            .doesNotContain("=")
            .isEqualTo("a+b%26c%3Dd");
    }

    // ======================================================================
    // buildSnapshotUrl
    // ======================================================================

    @Test
    void testBuildSnapshotUrl_ContainsDeviceAndProfile() {
        String url = AddressSpaceBuilder.buildSnapshotUrl("MyCam", "Profile_1");
        assertThat(url)
            .contains("device=MyCam")
            .contains("profile=Profile_1");
    }

    @Test
    void testBuildSnapshotUrl_StartsWithExpectedPath() {
        String url = AddressSpaceBuilder.buildSnapshotUrl("cam", "tok");
        assertThat(url).startsWith("/main/data/camera-driver/snapshot");
    }

    @Test
    void testBuildSnapshotUrl_EncodesSpacesInDeviceName() {
        String url = AddressSpaceBuilder.buildSnapshotUrl("Front Camera", "tok");
        // space encoded as '+' by URLEncoder (application/x-www-form-urlencoded)
        assertThat(url).contains("device=Front+Camera");
        assertThat(url).doesNotContain("device=Front Camera");
    }

    @Test
    void testBuildSnapshotUrl_EncodesAmpersandInDeviceName() {
        String url = AddressSpaceBuilder.buildSnapshotUrl("A&B", "tok");
        assertThat(url).contains("device=A%26B");
    }

    @Test
    void testBuildSnapshotUrl_EncodesProfileToken() {
        // Profile tokens with slashes (unusual but must be safe)
        String url = AddressSpaceBuilder.buildSnapshotUrl("cam", "tok/sub");
        assertThat(url).contains("profile=tok%2Fsub");
    }

    @Test
    void testBuildSnapshotUrl_ExactFormat() {
        String url = AddressSpaceBuilder.buildSnapshotUrl("cam1", "000");
        assertThat(url).isEqualTo("/main/data/camera-driver/snapshot?device=cam1&profile=000");
    }

    // ======================================================================
    // buildStreamUrl
    // ======================================================================

    @Test
    void testBuildStreamUrl_StartsWithExpectedPath() {
        String url = AddressSpaceBuilder.buildStreamUrl("cam", "tok", 10);
        assertThat(url).startsWith("/main/data/camera-driver/stream");
    }

    @Test
    void testBuildStreamUrl_ContainsFps() {
        String url = AddressSpaceBuilder.buildStreamUrl("cam", "tok", 15);
        assertThat(url).contains("fps=15");
    }

    @Test
    void testBuildStreamUrl_ContainsDeviceAndProfile() {
        String url = AddressSpaceBuilder.buildStreamUrl("MyCam", "Profile_1", 10);
        assertThat(url)
            .contains("device=MyCam")
            .contains("profile=Profile_1");
    }

    @Test
    void testBuildStreamUrl_EncodesDeviceName() {
        String url = AddressSpaceBuilder.buildStreamUrl("Side Camera", "tok", 10);
        assertThat(url).contains("device=Side+Camera");
    }

    @Test
    void testBuildStreamUrl_EncodesProfileToken() {
        String url = AddressSpaceBuilder.buildStreamUrl("cam", "a=b", 10);
        assertThat(url).contains("profile=a%3Db");
    }

    @Test
    void testBuildStreamUrl_ExactFormat() {
        String url = AddressSpaceBuilder.buildStreamUrl("cam1", "000", 10);
        assertThat(url).isEqualTo("/main/data/camera-driver/stream?device=cam1&profile=000&fps=10");
    }

    @Test
    void testBuildStreamUrl_ZeroFps_AllowedInUrl() {
        String url = AddressSpaceBuilder.buildStreamUrl("cam", "tok", 0);
        assertThat(url).contains("fps=0");
    }

    // ======================================================================
    // selectDataType
    // ======================================================================

    @Test
    void testSelectDataType_String_ReturnsStringNodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType("hello");
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_Integer_ReturnsInt32NodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType(42);
        assertThat(result).isEqualTo(Identifiers.Int32);
    }

    @Test
    void testSelectDataType_Long_ReturnsInt64NodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType(123L);
        assertThat(result).isEqualTo(Identifiers.Int64);
    }

    @Test
    void testSelectDataType_Double_ReturnsDoubleNodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType(3.14);
        assertThat(result).isEqualTo(Identifiers.Double);
    }

    @Test
    void testSelectDataType_Boolean_ReturnsBooleanNodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType(true);
        assertThat(result).isEqualTo(Identifiers.Boolean);
    }

    @ParameterizedTest
    @ValueSource(strings = {"some string"})  // gives us a non-null value in the right type
    void testSelectDataType_String_ReturnsStringParameterized(String value) {
        assertThat(AddressSpaceBuilder.selectDataType(value)).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_UnknownType_DefaultsToString() {
        // Object types not in the handled set should fall back to String
        NodeId result = AddressSpaceBuilder.selectDataType(new Object());
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_FloatIsNotDoubleOrInt_DefaultsToString() {
        // Float is deliberately not in the handled set; defaults to String
        NodeId result = AddressSpaceBuilder.selectDataType(1.5f);
        assertThat(result).isEqualTo(Identifiers.String);
    }

    @Test
    void testSelectDataType_IntegerBoxedAndUnboxed_BothInt32() {
        Integer boxed = Integer.valueOf(7);
        NodeId result = AddressSpaceBuilder.selectDataType(boxed);
        assertThat(result).isEqualTo(Identifiers.Int32);
    }

    @Test
    void testSelectDataType_LongBoxedAndUnboxed_BothInt64() {
        Long boxed = Long.valueOf(9_000_000_000L);
        NodeId result = AddressSpaceBuilder.selectDataType(boxed);
        assertThat(result).isEqualTo(Identifiers.Int64);
    }

    @Test
    void testSelectDataType_BooleanFalse_ReturnsBooleanNodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType(false);
        assertThat(result).isEqualTo(Identifiers.Boolean);
    }

    @Test
    void testSelectDataType_EmptyString_ReturnsStringNodeId() {
        NodeId result = AddressSpaceBuilder.selectDataType("");
        assertThat(result).isEqualTo(Identifiers.String);
    }
}
