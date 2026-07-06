package com.gaskony.camera.gateway.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Go2RtcManager.
 *
 * Tests cover the parts that do not require a live subprocess or network calls:
 *  - Constructor and initial state
 *  - URL-formatting methods (getStreamMp4Url, getStreamMjpegUrl)
 *  - isAvailable() before start() is called
 *  - getPort() accessor
 *  - getProcessInfo() structural shape when the process has never been started
 *  - addStream() / removeStream() return false when not available
 *  - Stream name encoding edge cases
 */
class Go2RtcManagerTest {

    private Path tempDataDir;
    private Go2RtcManager manager;

    @BeforeEach
    void setUp() throws IOException {
        tempDataDir = Files.createTempDirectory("go2rtc-test-");
        // Use default port (1984)
        manager = new Go2RtcManager(tempDataDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Do NOT call manager.stop() — start() was never called; stop() would
        // NPE on httpClient.close() since httpClient is null.
        // Just clean up the temp directory.
        deleteRecursively(tempDataDir);
    }

    // -----------------------------------------------------------------------
    // Constructor / initial state
    // -----------------------------------------------------------------------

    @Test
    void testConstructor_DefaultPort_IsSetCorrectly() {
        assertThat(manager.getPort()).isEqualTo(1984);
    }

    @Test
    void testConstructor_CustomPort_IsSetCorrectly() {
        Go2RtcManager custom = new Go2RtcManager(tempDataDir, 9999);
        assertThat(custom.getPort()).isEqualTo(9999);
    }

    @Test
    void testIsAvailable_BeforeStart_ReturnsFalse() {
        assertThat(manager.isAvailable()).isFalse();
    }

    // -----------------------------------------------------------------------
    // getStreamMp4Url
    // -----------------------------------------------------------------------

    @Test
    void testGetStreamMp4Url_SimpleStreamName_ReturnsCorrectUrl() {
        String url = manager.getStreamMp4Url("camera1");
        // Video-only (video=h264,h265, no audio) to avoid MSE A/V-intersection stalls.
        assertThat(url).isEqualTo("http://127.0.0.1:1984/api/stream.mp4?src=camera1&video=h264,h265");
    }

    @Test
    void testGetStreamMp4Url_RequestsVideoOnly_NoAudioTrack() {
        String url = manager.getStreamMp4Url("cam");
        assertThat(url).contains("video=h264,h265");
        assertThat(url).doesNotContain("audio");
    }

    @Test
    void testGetStreamMp4Url_ContainsHost_AndPort() {
        String url = manager.getStreamMp4Url("cam");
        assertThat(url).startsWith("http://127.0.0.1:1984/");
    }

    @Test
    void testGetStreamMp4Url_ContainsMp4Path() {
        String url = manager.getStreamMp4Url("cam");
        assertThat(url).contains("/api/stream.mp4");
    }

    @Test
    void testGetStreamMp4Url_ContainsSrcParam() {
        String url = manager.getStreamMp4Url("myCam");
        assertThat(url).contains("src=myCam");
    }

    @Test
    void testGetStreamMp4Url_StreamNameWithSpaces_IsUrlEncoded() {
        String url = manager.getStreamMp4Url("my camera");
        String encoded = URLEncoder.encode("my camera", StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
        assertThat(url).doesNotContain("src=my camera");
    }

    @Test
    void testGetStreamMp4Url_StreamNameWithSpecialChars_IsUrlEncoded() {
        String name = "camera/front+door";
        String url = manager.getStreamMp4Url(name);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
    }

    @Test
    void testGetStreamMp4Url_CustomPort_UsesCorrectPort() {
        Go2RtcManager custom = new Go2RtcManager(tempDataDir, 5000);
        String url = custom.getStreamMp4Url("cam");
        assertThat(url).startsWith("http://127.0.0.1:5000/");
    }

    // -----------------------------------------------------------------------
    // getStreamMjpegUrl
    // -----------------------------------------------------------------------

    @Test
    void testGetStreamMjpegUrl_SimpleStreamName_ReturnsCorrectUrl() {
        String url = manager.getStreamMjpegUrl("camera1");
        assertThat(url).isEqualTo("http://127.0.0.1:1984/api/stream.mjpeg?src=camera1");
    }

    @Test
    void testGetStreamMjpegUrl_ContainsHost_AndPort() {
        String url = manager.getStreamMjpegUrl("cam");
        assertThat(url).startsWith("http://127.0.0.1:1984/");
    }

    @Test
    void testGetStreamMjpegUrl_ContainsMjpegPath() {
        String url = manager.getStreamMjpegUrl("cam");
        assertThat(url).contains("/api/stream.mjpeg");
    }

    @Test
    void testGetStreamMjpegUrl_ContainsSrcParam() {
        String url = manager.getStreamMjpegUrl("myCam");
        assertThat(url).contains("src=myCam");
    }

    @Test
    void testGetStreamMjpegUrl_StreamNameWithSpaces_IsUrlEncoded() {
        String url = manager.getStreamMjpegUrl("my camera");
        String encoded = URLEncoder.encode("my camera", StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
        assertThat(url).doesNotContain("src=my camera");
    }

    @Test
    void testGetStreamMjpegUrl_StreamNameWithSpecialChars_IsUrlEncoded() {
        String name = "cam&name=evil";
        String url = manager.getStreamMjpegUrl(name);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
    }

    @Test
    void testGetStreamMjpegUrl_CustomPort_UsesCorrectPort() {
        Go2RtcManager custom = new Go2RtcManager(tempDataDir, 7777);
        String url = custom.getStreamMjpegUrl("cam");
        assertThat(url).startsWith("http://127.0.0.1:7777/");
    }

    // -----------------------------------------------------------------------
    // URL format symmetry: Mp4 vs Mjpeg
    // -----------------------------------------------------------------------

    @Test
    void testMp4AndMjpegUrls_DifferOnlyInPath_ForSameStreamName() {
        String streamName = "frontdoor";
        String mp4  = manager.getStreamMp4Url(streamName);
        String mjpeg = manager.getStreamMjpegUrl(streamName);

        // Both should share the same host, port, and src param
        assertThat(mp4).startsWith("http://127.0.0.1:1984/");
        assertThat(mjpeg).startsWith("http://127.0.0.1:1984/");
        assertThat(mp4).contains("src=" + streamName);   // mp4 also carries &video=… (video-only)
        assertThat(mjpeg).endsWith("src=" + streamName);

        // Paths differ
        assertThat(mp4).contains("stream.mp4");
        assertThat(mjpeg).contains("stream.mjpeg");
        assertThat(mp4).isNotEqualTo(mjpeg);
    }

    // -----------------------------------------------------------------------
    // addStream / removeStream — no process, should return false immediately
    // -----------------------------------------------------------------------

    @Test
    void testAddStream_WhenNotAvailable_ReturnsFalse() {
        // No process started — isAvailable() is false, so addStream must short-circuit
        boolean result = manager.addStream("cam1", "rtsp://192.168.1.10:554/stream");
        assertThat(result).isFalse();
    }

    @Test
    void testRemoveStream_WhenNotAvailable_ReturnsFalse() {
        boolean result = manager.removeStream("cam1");
        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // getProcessInfo — shape / defaults before start
    // -----------------------------------------------------------------------

    @Test
    void testGetProcessInfo_BeforeStart_AliveIsFalse() {
        var info = manager.getProcessInfo();
        assertThat(info.has("alive")).isTrue();
        assertThat(info.optBoolean("alive", true)).isFalse();
    }

    @Test
    void testGetProcessInfo_BeforeStart_PortMatchesConstructorPort() {
        var info = manager.getProcessInfo();
        assertThat(info.optInt("port", -1)).isEqualTo(1984);
    }

    @Test
    void testGetProcessInfo_BeforeStart_RestartCountIsZero() {
        var info = manager.getProcessInfo();
        assertThat(info.optInt("restartCount", -1)).isZero();
    }

    @Test
    void testGetProcessInfo_BeforeStart_UptimeMsIsZero() {
        var info = manager.getProcessInfo();
        assertThat(info.optLong("uptimeMs", -1L)).isZero();
    }

    @Test
    void testGetProcessInfo_CustomPort_ReflectedInInfo() {
        Go2RtcManager custom = new Go2RtcManager(tempDataDir, 3000);
        var info = custom.getProcessInfo();
        assertThat(info.optInt("port", -1)).isEqualTo(3000);
    }

    // -----------------------------------------------------------------------
    // Parameterised: same encoding rule applies to both URL methods
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "simple",
        "camera-1",
        "front_door",
        "camera.building.a",
        "Cam With Spaces",
        "cam&name=x",
        "cam/subpath",
        "cam+extra"
    })
    void testStreamUrls_StreamNameAlwaysAppearsInSrcParam(String name) {
        String mp4   = manager.getStreamMp4Url(name);
        String mjpeg = manager.getStreamMjpegUrl(name);

        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);

        assertThat(mp4).contains("src=" + encoded);
        assertThat(mjpeg).contains("src=" + encoded);
    }

    // -----------------------------------------------------------------------
    // getWebRtcSignalingUrl
    // -----------------------------------------------------------------------

    @Test
    void testGetWebRtcSignalingUrl_SimpleStreamName_ReturnsCorrectUrl() {
        String url = manager.getWebRtcSignalingUrl("camera1");
        assertThat(url).isEqualTo("http://127.0.0.1:1984/api/webrtc?src=camera1");
    }

    @Test
    void testGetWebRtcSignalingUrl_ContainsHost_AndPort() {
        String url = manager.getWebRtcSignalingUrl("cam");
        assertThat(url).startsWith("http://127.0.0.1:1984/");
    }

    @Test
    void testGetWebRtcSignalingUrl_ContainsWebrtcPath() {
        String url = manager.getWebRtcSignalingUrl("cam");
        assertThat(url).contains("/api/webrtc");
    }

    @Test
    void testGetWebRtcSignalingUrl_StreamNameWithSpaces_IsUrlEncoded() {
        String url = manager.getWebRtcSignalingUrl("my camera");
        String encoded = URLEncoder.encode("my camera", StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
        assertThat(url).doesNotContain("src=my camera");
    }

    @Test
    void testGetWebRtcSignalingUrl_StreamNameWithSpecialChars_IsUrlEncoded() {
        String name = "camera/front+door";
        String url = manager.getWebRtcSignalingUrl(name);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        assertThat(url).contains("src=" + encoded);
    }

    @Test
    void testGetWebRtcSignalingUrl_CustomPort_UsesCorrectApiPort() {
        Go2RtcManager custom = new Go2RtcManager(tempDataDir, 5000);
        String url = custom.getWebRtcSignalingUrl("cam");
        assertThat(url).startsWith("http://127.0.0.1:5000/");
    }

    // -----------------------------------------------------------------------
    // Generated go2rtc.yaml — webrtc block + candidates
    // -----------------------------------------------------------------------

    /**
     * Invokes the private generateConfig(Path) via start()'s effect is not
     * practical here (start() also launches a real subprocess and requires a
     * platform binary). Instead we call the package-private config generation
     * indirectly through reflection, matching this test class's existing
     * approach of exercising only the parts that don't require a live
     * subprocess or network calls.
     */
    private String generateConfigYaml(Go2RtcManager mgr, Path configDir) throws Exception {
        var method = Go2RtcManager.class.getDeclaredMethod("generateConfig", Path.class);
        method.setAccessible(true);
        // generateConfig() reads the instance's apiPassword field, which is only
        // set by start(). Set it directly via reflection so we can generate a
        // realistic config without launching a real go2rtc subprocess.
        var passwordField = Go2RtcManager.class.getDeclaredField("apiPassword");
        passwordField.setAccessible(true);
        passwordField.set(mgr, "test-password");

        Path configPath = (Path) method.invoke(mgr, configDir);
        assertThat(configPath).isNotNull();
        return Files.readString(configPath, StandardCharsets.UTF_8);
    }

    @Test
    void testGeneratedYaml_ContainsWebRtcListenBlock() throws Exception {
        Path configDir = tempDataDir.resolve("go2rtc");
        String yaml = generateConfigYaml(manager, configDir);

        assertThat(yaml).contains("webrtc:");
        assertThat(yaml).contains("listen: \":8555\"");
    }

    @Test
    void testGeneratedYaml_NoCandidatesFile_FallsBackToAutoDetectSection() throws Exception {
        Path configDir = tempDataDir.resolve("go2rtc");
        String yaml = generateConfigYaml(manager, configDir);

        // Either real candidates are auto-detected (site-local IPv4 present on
        // the test host) or the block is explicitly empty - either way the
        // "candidates:" key must always be present under webrtc:.
        assertThat(yaml).contains("candidates:");

        // "ice_servers: []" (disabling go2rtc's default Google STUN server) must
        // track candidate presence exactly: it is only emitted once at least one
        // explicit candidate is advertised (STUN would otherwise be redundant
        // for a LAN-only deployment - see buildWebRtcCandidatesYaml). Whether the
        // test host itself has a site-local IPv4 interface is non-deterministic,
        // so assert the invariant rather than one specific outcome.
        if (yaml.contains("candidates: []")) {
            assertThat(yaml).doesNotContain("ice_servers");
        } else {
            assertThat(yaml).contains("ice_servers: []");
        }
    }

    @Test
    void testGeneratedYaml_CandidatesFilePresent_IsHonoured() throws Exception {
        Path configDir = tempDataDir.resolve("go2rtc");
        Files.createDirectories(configDir);
        Path candidatesFile = configDir.resolve("webrtc-candidates.txt");
        Files.writeString(candidatesFile,
            "# comment line, should be ignored\n"
            + "\n"
            + "203.0.113.10:8555\n"
            + "  203.0.113.11:8555  \n",
            StandardCharsets.UTF_8);

        String yaml = generateConfigYaml(manager, configDir);

        assertThat(yaml).contains("- \"203.0.113.10:8555\"");
        assertThat(yaml).contains("- \"203.0.113.11:8555\"");
        // The auto-detect fallback marker text must not appear when the file was used.
        assertThat(yaml).doesNotContain("auto-detected");
        // Explicit candidates are configured, so go2rtc's default STUN server
        // (stun.l.google.com:19302) must be disabled - it adds nothing here and,
        // per live-system reproduction, can stall ICE gathering past
        // WebRtcHandler's 10s signaling read timeout.
        assertThat(yaml).contains("ice_servers: []");
    }

    @Test
    void testGeneratedYaml_EmptyCandidatesFile_ProducesEmptyCandidatesList() throws Exception {
        Path configDir = tempDataDir.resolve("go2rtc");
        Files.createDirectories(configDir);
        Path candidatesFile = configDir.resolve("webrtc-candidates.txt");
        Files.writeString(candidatesFile, "# only comments\n\n", StandardCharsets.UTF_8);

        String yaml = generateConfigYaml(manager, configDir);

        assertThat(yaml).contains("candidates: []");
        // No explicit candidates configured - leave go2rtc's default STUN server
        // in place (it's still the only way to get any server-reflexive
        // candidate in this case), so no "ice_servers" override is emitted.
        assertThat(yaml).doesNotContain("ice_servers");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
    }
}
