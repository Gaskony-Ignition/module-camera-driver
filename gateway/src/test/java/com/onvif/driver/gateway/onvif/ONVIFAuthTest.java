package com.onvif.driver.gateway.onvif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ONVIFAuth.
 * Tests WS-UsernameToken generation for ONVIF authentication.
 */
class ONVIFAuthTest {

    // ==================== Basic Token Generation Tests ====================

    @Test
    void testGenerateUsernameToken_ReturnsValidXml() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();

        // Should contain WS-Security namespace
        assertThat(token).contains("xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"");
        assertThat(token).contains("xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\"");

        // Should contain required elements
        assertThat(token).contains("<wsse:Security");
        assertThat(token).contains("<wsse:UsernameToken>");
        assertThat(token).contains("</wsse:UsernameToken>");
        assertThat(token).contains("</wsse:Security>");
    }

    @Test
    void testGenerateUsernameToken_ContainsUsername() {
        String token = ONVIFAuth.generateUsernameToken("testuser", "testpass");

        assertThat(token).contains("<wsse:Username>testuser</wsse:Username>");
    }

    @Test
    void testGenerateUsernameToken_ContainsPasswordDigest() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Should contain Password element with PasswordDigest type
        assertThat(token).contains("<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">");
        assertThat(token).contains("</wsse:Password>");

        // Extract password digest value
        Pattern pattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");
        Matcher matcher = pattern.matcher(token);
        assertThat(matcher.find()).isTrue();

        String digestValue = matcher.group(1);
        assertThat(digestValue).isNotEmpty();

        // Password digest should be Base64-encoded (SHA-1 produces 20 bytes -> 28 chars when base64 encoded)
        assertThat(digestValue).hasSize(28);
        assertThatCode(() -> Base64.getDecoder().decode(digestValue)).doesNotThrowAnyException();
    }

    @Test
    void testGenerateUsernameToken_ContainsNonce() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Should contain Nonce element with Base64Binary encoding type
        assertThat(token).contains("<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">");
        assertThat(token).contains("</wsse:Nonce>");

        // Extract nonce value
        Pattern pattern = Pattern.compile("<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>");
        Matcher matcher = pattern.matcher(token);
        assertThat(matcher.find()).isTrue();

        String nonceValue = matcher.group(1);
        assertThat(nonceValue).isNotEmpty();

        // Nonce should be Base64-encoded (16 bytes -> 24 chars when base64 encoded)
        assertThat(nonceValue).hasSize(24);
        assertThatCode(() -> Base64.getDecoder().decode(nonceValue)).doesNotThrowAnyException();

        // Decoded nonce should be 16 bytes
        byte[] decodedNonce = Base64.getDecoder().decode(nonceValue);
        assertThat(decodedNonce).hasSize(16);
    }

    @Test
    void testGenerateUsernameToken_ContainsCreatedTimestamp() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Should contain Created element
        assertThat(token).contains("<wsu:Created>");
        assertThat(token).contains("</wsu:Created>");

        // Extract timestamp value
        Pattern pattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");
        Matcher matcher = pattern.matcher(token);
        assertThat(matcher.find()).isTrue();

        String timestamp = matcher.group(1);
        assertThat(timestamp).isNotEmpty();

        // Timestamp should be in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSS'Z')
        assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

        // Timestamp should end with 'Z' (UTC)
        assertThat(timestamp).endsWith("Z");
    }

    // ==================== Uniqueness Tests ====================

    @Test
    void testGenerateUsernameToken_NonceIsUnique() {
        String token1 = ONVIFAuth.generateUsernameToken("admin", "password");
        String token2 = ONVIFAuth.generateUsernameToken("admin", "password");

        // Extract nonce from both tokens
        Pattern pattern = Pattern.compile("<wsse:Nonce[^>]*>([^<]+)</wsse:Nonce>");

        Matcher matcher1 = pattern.matcher(token1);
        Matcher matcher2 = pattern.matcher(token2);

        assertThat(matcher1.find()).isTrue();
        assertThat(matcher2.find()).isTrue();

        String nonce1 = matcher1.group(1);
        String nonce2 = matcher2.group(1);

        // Nonces should be different (random)
        assertThat(nonce1).isNotEqualTo(nonce2);
    }

    @Test
    void testGenerateUsernameToken_TimestampIsRecent() throws InterruptedException {
        String token1 = ONVIFAuth.generateUsernameToken("admin", "password");
        // intentional fixed delay — testing that two tokens generated at different
        // wall-clock instants have observably different timestamps. The whole point is
        // that real time advanced; Awaitility would defeat the test's purpose.
        Thread.sleep(100);
        String token2 = ONVIFAuth.generateUsernameToken("admin", "password");

        // Extract timestamps
        Pattern pattern = Pattern.compile("<wsu:Created>([^<]+)</wsu:Created>");

        Matcher matcher1 = pattern.matcher(token1);
        Matcher matcher2 = pattern.matcher(token2);

        assertThat(matcher1.find()).isTrue();
        assertThat(matcher2.find()).isTrue();

        String timestamp1 = matcher1.group(1);
        String timestamp2 = matcher2.group(1);

        // Timestamps should be different (token2 should be later)
        assertThat(timestamp1).isNotEqualTo(timestamp2);
    }

    @Test
    void testGenerateUsernameToken_PasswordDigestIsUnique() {
        // Same credentials but different nonce/timestamp should produce different digest
        String token1 = ONVIFAuth.generateUsernameToken("admin", "password");
        String token2 = ONVIFAuth.generateUsernameToken("admin", "password");

        // Extract password digests
        Pattern pattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");

        Matcher matcher1 = pattern.matcher(token1);
        Matcher matcher2 = pattern.matcher(token2);

        assertThat(matcher1.find()).isTrue();
        assertThat(matcher2.find()).isTrue();

        String digest1 = matcher1.group(1);
        String digest2 = matcher2.group(1);

        // Digests should be different (due to different nonce/timestamp)
        assertThat(digest1).isNotEqualTo(digest2);
    }

    // ==================== Different Credentials Tests ====================

    @ParameterizedTest
    @CsvSource({
        "admin, password",
        "user, pass123",
        "camera1, secret",
        "onvif, onvif123",
        "test, test"
    })
    void testGenerateUsernameToken_DifferentCredentials(String username, String password) {
        String token = ONVIFAuth.generateUsernameToken(username, password);

        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        assertThat(token).contains("<wsse:Username>" + username + "</wsse:Username>");
        assertThat(token).contains("<wsse:Password");
        assertThat(token).contains("<wsse:Nonce");
        assertThat(token).contains("<wsu:Created>");
    }

    // ==================== Special Characters Tests ====================

    @Test
    void testGenerateUsernameToken_UsernameWithSpecialCharacters() {
        // ONVIF should handle usernames with special characters
        String token = ONVIFAuth.generateUsernameToken("admin@example.com", "password");

        assertThat(token).contains("<wsse:Username>admin@example.com</wsse:Username>");
    }

    @Test
    void testGenerateUsernameToken_PasswordWithSpecialCharacters() {
        // Password with special characters should work
        String token = ONVIFAuth.generateUsernameToken("admin", "p@ssw0rd!#$%");

        assertThat(token).isNotNull();
        assertThat(token).contains("<wsse:Username>admin</wsse:Username>");

        // Password digest should still be generated (even with special chars)
        Pattern pattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");
        Matcher matcher = pattern.matcher(token);
        assertThat(matcher.find()).isTrue();
    }

    // ==================== Empty/Null Input Tests ====================

    @Test
    void testGenerateUsernameToken_EmptyUsername() {
        String token = ONVIFAuth.generateUsernameToken("", "password");

        assertThat(token).contains("<wsse:Username></wsse:Username>");
    }

    @Test
    void testGenerateUsernameToken_EmptyPassword() {
        String token = ONVIFAuth.generateUsernameToken("admin", "");

        assertThat(token).contains("<wsse:Username>admin</wsse:Username>");
        // Should still have a password digest (based on empty password)
        assertThat(token).contains("<wsse:Password");
    }

    // ==================== XML Structure Tests ====================

    @Test
    void testGenerateUsernameToken_WellFormedXml() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Count opening and closing tags
        int securityOpen = countOccurrences(token, "<wsse:Security");
        int securityClose = countOccurrences(token, "</wsse:Security>");
        assertThat(securityOpen).isEqualTo(securityClose);

        int usernameTokenOpen = countOccurrences(token, "<wsse:UsernameToken>");
        int usernameTokenClose = countOccurrences(token, "</wsse:UsernameToken>");
        assertThat(usernameTokenOpen).isEqualTo(usernameTokenClose);

        int usernameOpen = countOccurrences(token, "<wsse:Username>");
        int usernameClose = countOccurrences(token, "</wsse:Username>");
        assertThat(usernameOpen).isEqualTo(usernameClose);

        int passwordOpen = countOccurrences(token, "<wsse:Password");
        int passwordClose = countOccurrences(token, "</wsse:Password>");
        assertThat(passwordOpen).isEqualTo(passwordClose);

        int nonceOpen = countOccurrences(token, "<wsse:Nonce");
        int nonceClose = countOccurrences(token, "</wsse:Nonce>");
        assertThat(nonceOpen).isEqualTo(nonceClose);

        int createdOpen = countOccurrences(token, "<wsu:Created>");
        int createdClose = countOccurrences(token, "</wsu:Created>");
        assertThat(createdOpen).isEqualTo(createdClose);
    }

    @Test
    void testGenerateUsernameToken_CorrectElementOrder() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Elements should appear in correct order according to ONVIF spec
        int usernamePos = token.indexOf("<wsse:Username>");
        int passwordPos = token.indexOf("<wsse:Password");
        int noncePos = token.indexOf("<wsse:Nonce");
        int createdPos = token.indexOf("<wsu:Created>");

        assertThat(usernamePos).isLessThan(passwordPos);
        assertThat(passwordPos).isLessThan(noncePos);
        assertThat(noncePos).isLessThan(createdPos);
    }

    // ==================== Security Tests ====================

    @Test
    void testGenerateUsernameToken_NoPlaintextPassword() {
        String token = ONVIFAuth.generateUsernameToken("admin", "supersecret");

        // The plaintext password should NOT appear in the token
        assertThat(token).doesNotContain("supersecret");
    }

    @Test
    void testGenerateUsernameToken_UsesSHA1Digest() {
        String token = ONVIFAuth.generateUsernameToken("admin", "password");

        // Extract password digest
        Pattern pattern = Pattern.compile("<wsse:Password[^>]*>([^<]+)</wsse:Password>");
        Matcher matcher = pattern.matcher(token);
        assertThat(matcher.find()).isTrue();

        String digestValue = matcher.group(1);
        byte[] decodedDigest = Base64.getDecoder().decode(digestValue);

        // SHA-1 produces 20 bytes
        assertThat(decodedDigest).hasSize(20);
    }

    // ==================== Helper Methods ====================

    private int countOccurrences(String str, String substr) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substr, index)) != -1) {
            count++;
            index += substr.length();
        }
        return count;
    }
}
