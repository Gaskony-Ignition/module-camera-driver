package com.onvif.driver.gateway.device;

import com.onvif.driver.gateway.onvif.ONVIFClient;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    // ======================================================================
    // C10 regression — PTZ writable nodes were never wired
    //
    // Pre-Sprint-1: AddressSpaceBuilder declared SetPan/SetTilt/SetZoom as
    // READ_WRITE via the helper `addWritableNode(... Consumer<Object>
    // writeHandler)`, but the captured lambda was never invoked because no
    // Milo AttributeFilter was installed — writes silently no-op'd.
    //
    // Sprint 1: dropped to READ_ONLY (option b in the original report) so
    // clients received Bad_NotWritable rather than a silent success.
    //
    // Sprint 2 (C10-followup): restored READ_WRITE with a real Milo
    // `AttributeFilters.setValue(...)` filter that translates writes into
    // SOAP AbsoluteMove calls. The READ_ONLY placeholder helper is gone;
    // its replacement is `addPtzWritableNode`.
    //
    // See /modules/.review/FINAL_REVIEW.md §4 C10 and the
    // /modules/.review/fixes/C10-followup.md report.
    // ======================================================================

    @Test
    void testC10_AddWritableNode_HelperRemoved() {
        // The Sprint 1 dead-code helper that captured a never-invoked
        // writeHandler lambda must remain deleted. If it returns, the
        // pre-Sprint-1 bug pattern (silent-no-op writable node) is back.
        boolean foundDeadHelper = Arrays.stream(AddressSpaceBuilder.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("addWritableNode"));

        assertThat(foundDeadHelper)
                .as("addWritableNode() must remain deleted — see /modules/.review/FINAL_REVIEW.md §4 C10")
                .isFalse();
    }

    @Test
    void testC10_PtzWritableNode_HelperPresent() throws NoSuchMethodException {
        // The Sprint 2 helper must exist with the (UaFolderNode parent,
        // String name, PtzAxis axis, String profileToken) signature used by
        // buildPTZ. Look up by name + arg-count rather than full signature
        // (UaFolderNode requires Milo on the test classpath).
        Method writable = Arrays.stream(AddressSpaceBuilder.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("addPtzWritableNode"))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("addPtzWritableNode"));

        assertThat(writable.getParameterCount())
                .as("addPtzWritableNode must take (UaFolderNode, String, PtzAxis, String)")
                .isEqualTo(4);
        assertThat(writable.getParameterTypes()[1])
                .as("Second parameter must be String (the node name)")
                .isEqualTo(String.class);
        assertThat(writable.getParameterTypes()[3])
                .as("Fourth parameter must be String (the profile token)")
                .isEqualTo(String.class);
    }

    @Test
    void testC10_PtzReadOnlyPlaceholder_HelperRemoved() {
        // The Sprint 1 placeholder helper is replaced by addPtzWritableNode in
        // Sprint 2 — must remain gone so a regression to READ_ONLY is caught.
        boolean stillThere = Arrays.stream(AddressSpaceBuilder.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("addPtzReadOnlyPlaceholder"));

        assertThat(stillThere)
                .as("addPtzReadOnlyPlaceholder() must remain deleted — replaced by "
                    + "addPtzWritableNode (C10-followup, FINAL_REVIEW §4 / SPRINT2_PLAN.md)")
                .isFalse();
    }

    @Test
    void testC10_PtzAxisEnum_Defined() {
        Class<?>[] inner = AddressSpaceBuilder.class.getDeclaredClasses();
        boolean foundPtzAxis = Arrays.stream(inner)
                .anyMatch(c -> c.isEnum() && c.getSimpleName().equals("PtzAxis"));

        assertThat(foundPtzAxis)
                .as("PtzAxis enum must be defined for SetPan/SetTilt/SetZoom routing")
                .isTrue();
    }

    // ======================================================================
    // PTZ value clamping — pure helper, easy to verify.
    // ======================================================================

    @Test
    void testClampPtz_WithinRange_ReturnedUnchanged() {
        assertThat(AddressSpaceBuilder.clampPtz(0.5)).isEqualTo(0.5);
        assertThat(AddressSpaceBuilder.clampPtz(-0.5)).isEqualTo(-0.5);
        assertThat(AddressSpaceBuilder.clampPtz(0.0)).isEqualTo(0.0);
    }

    @Test
    void testClampPtz_AbovePositiveOne_ClampedToOne() {
        assertThat(AddressSpaceBuilder.clampPtz(1.5)).isEqualTo(1.0);
        assertThat(AddressSpaceBuilder.clampPtz(100.0)).isEqualTo(1.0);
        assertThat(AddressSpaceBuilder.clampPtz(Double.POSITIVE_INFINITY)).isEqualTo(1.0);
    }

    @Test
    void testClampPtz_BelowNegativeOne_ClampedToNegativeOne() {
        assertThat(AddressSpaceBuilder.clampPtz(-1.5)).isEqualTo(-1.0);
        assertThat(AddressSpaceBuilder.clampPtz(-100.0)).isEqualTo(-1.0);
        assertThat(AddressSpaceBuilder.clampPtz(Double.NEGATIVE_INFINITY)).isEqualTo(-1.0);
    }

    @Test
    void testClampPtz_NaN_NormalisedToZero() {
        assertThat(AddressSpaceBuilder.clampPtz(Double.NaN)).isEqualTo(0.0);
    }

    @Test
    void testClampPtz_BoundariesExact() {
        assertThat(AddressSpaceBuilder.clampPtz(1.0)).isEqualTo(1.0);
        assertThat(AddressSpaceBuilder.clampPtz(-1.0)).isEqualTo(-1.0);
    }

    // ======================================================================
    // C10-followup behavioural tests — handlePtzWrite drives AbsoluteMove
    //
    // The Sprint 2 C10-followup wires PTZ Set* nodes via a Milo
    // AttributeFilters.setValue(...) filter; the lambda delegates to the
    // package-private static method `handlePtzWrite(...)` so the SOAP
    // dispatch logic is testable without a live Milo server.
    //
    // See /modules/.review/fixes/C10-followup.md.
    // ======================================================================

    @Test
    void testHandlePtzWrite_PanWrite_InvokesAbsoluteMoveWithPanValue() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", 0.5, client, state);

        verify(client).absoluteMove("Profile_1", 0.5, 0.0, 0.0);
        // Cache updated
        assertThat(state.get()).containsExactly(0.5, 0.0, 0.0);
    }

    @Test
    void testHandlePtzWrite_TiltWrite_InvokesAbsoluteMoveWithTiltValue() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.1, 0.0, 0.2});

        AddressSpaceBuilder.handlePtzWrite(
            "SetTilt", AddressSpaceBuilder.PtzAxis.TILT, "Profile_1", -0.3, client, state);

        verify(client).absoluteMove("Profile_1", 0.1, -0.3, 0.2);
        assertThat(state.get()).containsExactly(0.1, -0.3, 0.2);
    }

    @Test
    void testHandlePtzWrite_ZoomWrite_InvokesAbsoluteMoveWithZoomValue() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetZoom", AddressSpaceBuilder.PtzAxis.ZOOM, "Profile_1", 0.7, client, state);

        verify(client).absoluteMove("Profile_1", 0.0, 0.0, 0.7);
    }

    @Test
    void testHandlePtzWrite_ValueAboveOne_ClampedBeforeDispatch() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", 1.5, client, state);

        // 1.5 should be clamped to 1.0
        verify(client).absoluteMove("Profile_1", 1.0, 0.0, 0.0);
    }

    @Test
    void testHandlePtzWrite_ValueBelowNegativeOne_ClampedBeforeDispatch() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetTilt", AddressSpaceBuilder.PtzAxis.TILT, "Profile_1", -2.0, client, state);

        verify(client).absoluteMove("Profile_1", 0.0, -1.0, 0.0);
    }

    @Test
    void testHandlePtzWrite_NonNumericValue_AbsoluteMoveNotInvoked() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", "not a number", client, state);

        verify(client, never()).absoluteMove(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void testHandlePtzWrite_NullValue_AbsoluteMoveNotInvoked() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", null, client, state);

        verify(client, never()).absoluteMove(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void testHandlePtzWrite_NullClient_AbsoluteMoveNotInvoked() throws Exception {
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        // No exception, just no-op.
        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", 0.5, null, state);

        // Still updates the per-axis cache (so a future writable-with-client
        // wire-up doesn't lose the value).
        assertThat(state.get()).containsExactly(0.5, 0.0, 0.0);
    }

    @Test
    void testHandlePtzWrite_NullProfileToken_AbsoluteMoveNotInvoked() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, null, 0.5, client, state);

        verify(client, never()).absoluteMove(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void testHandlePtzWrite_EmptyProfileToken_AbsoluteMoveNotInvoked() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "", 0.5, client, state);

        verify(client, never()).absoluteMove(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void testHandlePtzWrite_AbsoluteMoveThrows_ExceptionSwallowed() throws Exception {
        // The filter must not propagate exceptions to the OPC-UA stack —
        // a SOAP failure is logged but doesn't break the address space.
        ONVIFClient client = mock(ONVIFClient.class);
        doThrow(new IOException("camera offline"))
            .when(client).absoluteMove(anyString(), anyDouble(), anyDouble(), anyDouble());
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", 0.5, client, state);

        // No assertion needed — the test passes if no exception escapes.
        // Verify the call was attempted.
        verify(client, times(1)).absoluteMove("Profile_1", 0.5, 0.0, 0.0);
    }

    @Test
    void testHandlePtzWrite_SequentialWritesAcrossAxes_ProduceCoherentTriple() throws Exception {
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetPan", AddressSpaceBuilder.PtzAxis.PAN, "Profile_1", 0.4, client, state);
        AddressSpaceBuilder.handlePtzWrite(
            "SetTilt", AddressSpaceBuilder.PtzAxis.TILT, "Profile_1", -0.3, client, state);
        AddressSpaceBuilder.handlePtzWrite(
            "SetZoom", AddressSpaceBuilder.PtzAxis.ZOOM, "Profile_1", 0.6, client, state);

        // Final triple must reflect all three writes.
        ArgumentCaptor<Double> panCap = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> tiltCap = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> zoomCap = ArgumentCaptor.forClass(Double.class);
        verify(client, times(3)).absoluteMove(eq("Profile_1"), panCap.capture(), tiltCap.capture(), zoomCap.capture());

        // Last call must have the final triple
        assertThat(panCap.getAllValues()).containsExactly(0.4, 0.4, 0.4);
        assertThat(tiltCap.getAllValues()).containsExactly(0.0, -0.3, -0.3);
        assertThat(zoomCap.getAllValues()).containsExactly(0.0, 0.0, 0.6);
        assertThat(state.get()).containsExactly(0.4, -0.3, 0.6);
    }

    @Test
    void testHandlePtzWrite_IntegerValue_ConvertedToDouble() throws Exception {
        // OPC-UA clients may send Int32 instead of Double — must still work.
        ONVIFClient client = mock(ONVIFClient.class);
        AtomicReference<double[]> state = new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

        AddressSpaceBuilder.handlePtzWrite(
            "SetZoom", AddressSpaceBuilder.PtzAxis.ZOOM, "Profile_1", 1, client, state);

        verify(client).absoluteMove("Profile_1", 0.0, 0.0, 1.0);
    }
}
