package com.gaskony.camera.gateway.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CredentialUtil.embedCredentials().
 * resolvePassword() requires GatewayContext and is not tested here.
 */
class CredentialUtilTest {

    // ==================== Constructor Test ====================

    @Test
    void testConstructor_ThrowsAssertionError() {
        // CredentialUtil is a utility class — it throws AssertionError on instantiation
        assertThatThrownBy(() -> {
            var constructor = CredentialUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        }).hasCauseInstanceOf(AssertionError.class);
    }

    // ==================== embedCredentials — RTSP Protocol ====================

    @Test
    void testEmbedCredentials_RtspProtocol_EmbedsBothCredentials() {
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin", "pass");
        assertThat(result).isEqualTo("rtsp://admin:pass@camera/stream");
    }

    @Test
    void testEmbedCredentials_RtspProtocol_WithPort() {
        String result = CredentialUtil.embedCredentials("rtsp://192.168.1.50:554/stream1", "admin", "secret");
        assertThat(result).isEqualTo("rtsp://admin:secret@192.168.1.50:554/stream1");
    }

    // ==================== embedCredentials — RTSPS Protocol ====================

    @Test
    void testEmbedCredentials_RtspsProtocol_EmbedsBothCredentials() {
        String result = CredentialUtil.embedCredentials("rtsps://camera.example.com/stream", "admin", "pass");
        assertThat(result).isEqualTo("rtsps://admin:pass@camera.example.com/stream");
    }

    // ==================== embedCredentials — HTTP Protocol ====================

    @Test
    void testEmbedCredentials_HttpProtocol_EmbedsBothCredentials() {
        String result = CredentialUtil.embedCredentials("http://camera/snapshot", "user", "secret");
        assertThat(result).isEqualTo("http://user:secret@camera/snapshot");
    }

    @Test
    void testEmbedCredentials_HttpProtocol_WithPort() {
        String result = CredentialUtil.embedCredentials("http://192.168.1.50:80/snapshot.jpg", "viewer", "cam123");
        assertThat(result).isEqualTo("http://viewer:cam123@192.168.1.50:80/snapshot.jpg");
    }

    // ==================== embedCredentials — HTTPS Protocol ====================

    @Test
    void testEmbedCredentials_HttpsProtocol_EmbedsBothCredentials() {
        String result = CredentialUtil.embedCredentials("https://camera.example.com/snapshot", "user", "secret");
        assertThat(result).isEqualTo("https://user:secret@camera.example.com/snapshot");
    }

    // ==================== embedCredentials — Null/Empty URL ====================

    @Test
    void testEmbedCredentials_NullUrl_ReturnsNull() {
        String result = CredentialUtil.embedCredentials(null, "admin", "pass");
        assertThat(result).isNull();
    }

    @Test
    void testEmbedCredentials_EmptyUrl_ReturnsEmpty() {
        String result = CredentialUtil.embedCredentials("", "admin", "pass");
        assertThat(result).isEqualTo("");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testEmbedCredentials_NullOrEmptyUrl_ReturnsOriginal(String url) {
        String result = CredentialUtil.embedCredentials(url, "admin", "pass");
        assertThat(result).isEqualTo(url);
    }

    // ==================== embedCredentials — Null/Empty Username ====================

    @ParameterizedTest
    @NullAndEmptySource
    void testEmbedCredentials_NullOrEmptyUsername_ReturnsOriginalUrl(String username) {
        String url = "rtsp://camera/stream";
        String result = CredentialUtil.embedCredentials(url, username, "pass");
        assertThat(result).isEqualTo(url);
    }

    @Test
    void testEmbedCredentials_WhitespaceUsername_ReturnsOriginalUrl() {
        // A username consisting only of whitespace is treated as empty
        String url = "rtsp://camera/stream";
        String result = CredentialUtil.embedCredentials(url, "   ", "pass");
        assertThat(result).isEqualTo(url);
    }

    // ==================== embedCredentials — Empty/Null Password ====================

    @Test
    void testEmbedCredentials_EmptyPassword_EmbedsUsernameOnly() {
        // Empty password → no colon, just username
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin", "");
        assertThat(result).isEqualTo("rtsp://admin@camera/stream");
    }

    @Test
    void testEmbedCredentials_NullPassword_EmbedsUsernameOnly() {
        // Null password → no colon, just username
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin", null);
        assertThat(result).isEqualTo("rtsp://admin@camera/stream");
    }

    @Test
    void testEmbedCredentials_EmptyPassword_HttpUrl_EmbedsUsernameOnly() {
        String result = CredentialUtil.embedCredentials("http://camera/snapshot", "viewer", "");
        assertThat(result).isEqualTo("http://viewer@camera/snapshot");
    }

    // ==================== embedCredentials — Already Has Credentials ====================

    @Test
    void testEmbedCredentials_RtspAlreadyHasCredentials_ReturnsOriginal() {
        String withCreds = "rtsp://existing:creds@camera/stream";
        String result = CredentialUtil.embedCredentials(withCreds, "admin", "pass");
        assertThat(result).isEqualTo(withCreds);
    }

    @Test
    void testEmbedCredentials_HttpAlreadyHasCredentials_ReturnsOriginal() {
        String withCreds = "http://user:password@camera/snapshot";
        String result = CredentialUtil.embedCredentials(withCreds, "admin", "newpass");
        assertThat(result).isEqualTo(withCreds);
    }

    @Test
    void testEmbedCredentials_HttpsAlreadyHasCredentials_ReturnsOriginal() {
        String withCreds = "https://user:password@camera.example.com/snapshot";
        String result = CredentialUtil.embedCredentials(withCreds, "admin", "newpass");
        assertThat(result).isEqualTo(withCreds);
    }

    @Test
    void testEmbedCredentials_RtspsAlreadyHasCredentials_ReturnsOriginal() {
        String withCreds = "rtsps://user:password@camera/stream";
        String result = CredentialUtil.embedCredentials(withCreds, "admin", "newpass");
        assertThat(result).isEqualTo(withCreds);
    }

    // ==================== embedCredentials — Unsupported Protocol ====================

    @Test
    void testEmbedCredentials_FtpProtocol_ReturnsOriginal() {
        String ftp = "ftp://camera/file";
        String result = CredentialUtil.embedCredentials(ftp, "admin", "pass");
        assertThat(result).isEqualTo(ftp);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://camera/file",
        "sftp://camera/file",
        "file:///local/path",
        "not-a-url",
        "//camera/stream",
        "camera/stream"
    })
    void testEmbedCredentials_UnsupportedProtocols_ReturnOriginal(String url) {
        String result = CredentialUtil.embedCredentials(url, "admin", "pass");
        assertThat(result).isEqualTo(url);
    }

    // ==================== embedCredentials — Result Structure ====================

    @Test
    void testEmbedCredentials_ResultPreservesPath() {
        // The path and query portions of the URL must be preserved exactly
        String result = CredentialUtil.embedCredentials(
            "rtsp://192.168.1.50:554/Streaming/Channels/101", "admin", "secret");
        assertThat(result).isEqualTo("rtsp://admin:secret@192.168.1.50:554/Streaming/Channels/101");
    }

    @Test
    void testEmbedCredentials_ResultStartsWithOriginalProtocol() {
        for (String protocol : new String[]{"rtsp://", "rtsps://", "http://", "https://"}) {
            String url = protocol + "camera/stream";
            String result = CredentialUtil.embedCredentials(url, "admin", "pass");
            assertThat(result)
                .as("Result for protocol '%s' must retain the same protocol prefix", protocol)
                .startsWith(protocol);
        }
    }

    @Test
    void testEmbedCredentials_ResultContainsAtSign() {
        // Whenever credentials are embedded the @ separator must appear
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin", "pass");
        assertThat(result).contains("@");
    }

    // ==================== embedCredentials — Known Limitation (Special Chars) ====================

    @Test
    void testEmbedCredentials_PasswordWithAtSign_DocumentedBehavior() {
        // NOTE: CredentialUtil does NOT URL-encode credentials.
        // A password containing '@' will produce a URL where the @ in the password
        // is indistinguishable from the credential separator.
        // This is a known limitation — documented here for awareness.
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin", "p@ss");
        // The implementation embeds without encoding:
        assertThat(result).isEqualTo("rtsp://admin:p@ss@camera/stream");
    }

    @Test
    void testEmbedCredentials_UsernameWithSpecialChars_DocumentedBehavior() {
        // Usernames with colons produce malformed authority sections — known limitation.
        String result = CredentialUtil.embedCredentials("rtsp://camera/stream", "admin:extra", "pass");
        assertThat(result).isEqualTo("rtsp://admin:extra:pass@camera/stream");
    }
}
