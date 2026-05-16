package com.gaskony.camera.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthenticationManager.
 *
 * Tests cover:
 * - API key generation (uniqueness, length, non-null)
 * - Salted hashing (same key → different hash each time)
 * - Key verification (correct/wrong key)
 * - addApiKey / removeApiKey lifecycle
 * - Account lockout stats and clearAllLockouts
 * - IP-based brute-force lockout via API key failures
 *
 * Note: GatewayContext is a compileOnly Ignition dependency unavailable on the test
 * classpath. AuthenticationManager's constructor only uses GatewayContext to resolve
 * the ApiKeyStore file path (which safely returns null when context is null),
 * so null is passed safely.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationManagerTest {

    AuthenticationManager authManager;

    // -----------------------------------------------------------------------
    // Lifecycle helpers
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        // GatewayContext is compileOnly; passing null is safe — ApiKeyStore.resolveStoreFile
        // catches the NullPointerException and returns null (falls back to in-memory only).
        authManager = new AuthenticationManager(null);
        // Clear instance state so tests do not interfere with each other.
        clearApiKeys();
        authManager.clearAllLockouts();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearApiKeys();
        authManager.clearAllLockouts();
    }

    /** Empties the instance apiKeys map via reflection. */
    private void clearApiKeys() throws Exception {
        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(authManager);
        store.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers for reflection-based access to the private record type
    // -----------------------------------------------------------------------

    /**
     * Returns the Class object for the private inner record StoredApiKey.
     */
    private static Class<?> storedApiKeyClass() {
        for (Class<?> inner : AuthenticationManager.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("StoredApiKey")) {
                return inner;
            }
        }
        throw new IllegalStateException("StoredApiKey inner record not found in AuthenticationManager");
    }

    /**
     * Invokes the private hashApiKeyWithSalt(String, String) method and returns the result.
     */
    private Object invokeHashApiKeyWithSalt(String apiKey, String username) throws Exception {
        Method method = AuthenticationManager.class.getDeclaredMethod(
                "hashApiKeyWithSalt", String.class, String.class);
        method.setAccessible(true);
        return method.invoke(authManager, apiKey, username);
    }

    /**
     * Invokes the private verifyApiKey(String, StoredApiKey) method.
     */
    private boolean invokeVerifyApiKey(String apiKey, Object storedApiKey) throws Exception {
        Method method = AuthenticationManager.class.getDeclaredMethod(
                "verifyApiKey", String.class, storedApiKeyClass());
        method.setAccessible(true);
        return (boolean) method.invoke(authManager, apiKey, storedApiKey);
    }

    /**
     * Reads a record accessor (e.g. saltHex(), hashHex()) from a StoredApiKey instance.
     */
    private String readRecordField(Object storedApiKey, String accessorName) throws Exception {
        Method accessor = storedApiKey.getClass().getDeclaredMethod(accessorName);
        accessor.setAccessible(true);
        return (String) accessor.invoke(storedApiKey);
    }

    // -----------------------------------------------------------------------
    // generateApiKey() Tests
    // -----------------------------------------------------------------------

    @Test
    void testGenerateApiKey_IsNotNullOrEmpty() {
        String key = AuthenticationManager.generateApiKey();
        assertThat(key)
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void testGenerateApiKey_HasSufficientLength() {
        // 32 random bytes → 43 chars in base64url-without-padding
        String key = AuthenticationManager.generateApiKey();
        assertThat(key.length()).isGreaterThan(20);
    }

    @Test
    void testGenerateApiKey_ProducesUniqueKeys() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(AuthenticationManager.generateApiKey());
        }
        // All 100 keys must be distinct
        assertThat(keys).hasSize(100);
    }

    @Test
    void testGenerateApiKey_ContainsNoWhitespace() {
        for (int i = 0; i < 20; i++) {
            String key = AuthenticationManager.generateApiKey();
            assertThat(key).doesNotContainAnyWhitespaces();
        }
    }

    // -----------------------------------------------------------------------
    // hashApiKeyWithSalt() Tests (via reflection)
    // -----------------------------------------------------------------------

    @Test
    void testHashApiKeyWithSalt_ReturnsNonNull() throws Exception {
        Object stored = invokeHashApiKeyWithSalt("test-api-key", "user1");
        assertThat(stored).isNotNull();
    }

    @Test
    void testHashApiKeyWithSalt_SameKeyProducesDifferentSalts() throws Exception {
        String key = "same-key-value";

        Object stored1 = invokeHashApiKeyWithSalt(key, "user1");
        Object stored2 = invokeHashApiKeyWithSalt(key, "user2");

        String salt1 = readRecordField(stored1, "saltHex");
        String salt2 = readRecordField(stored2, "saltHex");

        // Random 16-byte salts must differ (probability of collision is negligible)
        assertThat(salt1).isNotEqualTo(salt2);
    }

    @Test
    void testHashApiKeyWithSalt_SameKeyProducesDifferentHashes() throws Exception {
        String key = "same-key-value";

        Object stored1 = invokeHashApiKeyWithSalt(key, "user1");
        Object stored2 = invokeHashApiKeyWithSalt(key, "user2");

        String hash1 = readRecordField(stored1, "hashHex");
        String hash2 = readRecordField(stored2, "hashHex");

        // Different salts must produce different final hashes
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void testHashApiKeyWithSalt_SaltHexHasCorrectLength() throws Exception {
        // 16-byte salt → 32 hex characters
        Object stored = invokeHashApiKeyWithSalt("any-key", "user");
        String saltHex = readRecordField(stored, "saltHex");
        assertThat(saltHex).hasSize(32);
    }

    @Test
    void testHashApiKeyWithSalt_HashHexHasCorrectLength() throws Exception {
        // SHA-256 → 32 bytes → 64 hex characters
        Object stored = invokeHashApiKeyWithSalt("any-key", "user");
        String hashHex = readRecordField(stored, "hashHex");
        assertThat(hashHex).hasSize(64);
    }

    // -----------------------------------------------------------------------
    // verifyApiKey() Tests (via reflection)
    // -----------------------------------------------------------------------

    @Test
    void testVerifyApiKey_CorrectKey_ReturnsTrue() throws Exception {
        String key = "correct-key-abc";
        Object stored = invokeHashApiKeyWithSalt(key, "user1");

        boolean result = invokeVerifyApiKey(key, stored);

        assertThat(result).isTrue();
    }

    @Test
    void testVerifyApiKey_WrongKey_ReturnsFalse() throws Exception {
        String key = "correct-key";
        Object stored = invokeHashApiKeyWithSalt(key, "user1");

        boolean result = invokeVerifyApiKey("wrong-key", stored);

        assertThat(result).isFalse();
    }

    @Test
    void testVerifyApiKey_EmptyKey_ReturnsFalse() throws Exception {
        String key = "real-key";
        Object stored = invokeHashApiKeyWithSalt(key, "user1");

        boolean result = invokeVerifyApiKey("", stored);

        assertThat(result).isFalse();
    }

    @Test
    void testVerifyApiKey_SimilarKey_ReturnsFalse() throws Exception {
        String key = "key123";
        Object stored = invokeHashApiKeyWithSalt(key, "user1");

        // A key that differs by only one character must not match
        boolean result = invokeVerifyApiKey("key124", stored);

        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // addApiKey() / removeApiKey() public API tests
    // -----------------------------------------------------------------------

    @Test
    void testAddApiKey_DoesNotThrow() {
        String key = AuthenticationManager.generateApiKey();
        assertThatCode(() -> authManager.addApiKey(key, "testuser"))
                .doesNotThrowAnyException();
    }

    @Test
    void testAddApiKey_PopulatesStore() throws Exception {
        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(authManager);

        assertThat(store).isEmpty();

        authManager.addApiKey(AuthenticationManager.generateApiKey(), "user1");
        assertThat(store).hasSize(1);

        authManager.addApiKey(AuthenticationManager.generateApiKey(), "user2");
        assertThat(store).hasSize(2);
    }

    @Test
    void testAddSameKeyTwice_CreatesTwoStoreEntries() throws Exception {
        // Same plain-text key stored twice → two different salted entries
        String key = "shared-key";
        authManager.addApiKey(key, "user1");
        authManager.addApiKey(key, "user2");

        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(authManager);

        // Both entries must be present (different UUID keys, different salts)
        assertThat(store).hasSize(2);
    }

    @Test
    void testRemoveApiKey_DoesNotThrow() {
        String key = AuthenticationManager.generateApiKey();
        authManager.addApiKey(key, "testuser");
        assertThatCode(() -> authManager.removeApiKey(key))
                .doesNotThrowAnyException();
    }

    @Test
    void testRemoveApiKey_EmptiesStore() throws Exception {
        String key = AuthenticationManager.generateApiKey();
        authManager.addApiKey(key, "user1");

        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(authManager);

        assertThat(store).hasSize(1);
        authManager.removeApiKey(key);
        assertThat(store).isEmpty();
    }

    @Test
    void testRemoveApiKey_NonExistentKey_DoesNotThrow() {
        String key = AuthenticationManager.generateApiKey();
        // Key was never added — removing it must be a no-op
        assertThatCode(() -> authManager.removeApiKey(key))
                .doesNotThrowAnyException();
    }

    @Test
    void testRemoveApiKey_OnlyRemovesMatchingEntry() throws Exception {
        String key1 = AuthenticationManager.generateApiKey();
        String key2 = AuthenticationManager.generateApiKey();
        authManager.addApiKey(key1, "user1");
        authManager.addApiKey(key2, "user2");

        authManager.removeApiKey(key1);

        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(authManager);

        // Only key2 entry must remain
        assertThat(store).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Account lockout / stats tests
    // -----------------------------------------------------------------------

    @Test
    void testGetFailedAttemptStats_InitiallyEmpty() {
        assertThat(authManager.getFailedAttemptStats()).isEmpty();
    }

    @Test
    void testClearAllLockouts_WorksOnFreshInstance() {
        assertThatCode(() -> authManager.clearAllLockouts())
                .doesNotThrowAnyException();
    }

    @Test
    void testClearAllLockouts_ResetsState() throws Exception {
        // Manually inject a lockout entry into the instance maps via reflection
        Field failedAttemptsField = AuthenticationManager.class.getDeclaredField("failedAttempts");
        failedAttemptsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap = (Map<String, AtomicInteger>) failedAttemptsField.get(authManager);
        failedMap.put("testuser", new AtomicInteger(5));

        Field lockoutUntilField = AuthenticationManager.class.getDeclaredField("lockoutUntil");
        lockoutUntilField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> lockoutMap = (Map<String, Long>) lockoutUntilField.get(authManager);
        lockoutMap.put("testuser", System.currentTimeMillis() + 999_999L);

        // Verify they are populated before clearing
        assertThat(authManager.getFailedAttemptStats()).isNotEmpty();

        authManager.clearAllLockouts();

        assertThat(authManager.getFailedAttemptStats()).isEmpty();
        assertThat(lockoutMap).isEmpty();
    }

    @Test
    void testGetFailedAttemptStats_ReturnsDefensiveCopy() throws Exception {
        // Populate the instance map directly
        Field field = AuthenticationManager.class.getDeclaredField("failedAttempts");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap = (Map<String, AtomicInteger>) field.get(authManager);
        failedMap.put("alice", new AtomicInteger(3));

        Map<String, AtomicInteger> stats = authManager.getFailedAttemptStats();
        assertThat(stats).containsKey("alice");
        assertThat(stats.get("alice").get()).isEqualTo(3);

        // Modifying the returned copy must not affect the original
        stats.clear();
        assertThat(failedMap).containsKey("alice");
    }

    // -----------------------------------------------------------------------
    // Instance isolation tests (verifies the static → instance fix)
    // -----------------------------------------------------------------------

    @Test
    void testTwoInstances_HaveIsolatedApiKeyStores() throws Exception {
        AuthenticationManager manager1 = new AuthenticationManager(null);
        AuthenticationManager manager2 = new AuthenticationManager(null);

        // Add a key only to manager1
        manager1.addApiKey("only-in-manager1", "user1");

        Field field = AuthenticationManager.class.getDeclaredField("apiKeys");
        field.setAccessible(true);

        Map<?, ?> store1 = (Map<?, ?>) field.get(manager1);
        Map<?, ?> store2 = (Map<?, ?>) field.get(manager2);

        assertThat(store1).hasSize(1);
        assertThat(store2).isEmpty();
    }

    @Test
    void testTwoInstances_HaveIsolatedFailedAttempts() throws Exception {
        AuthenticationManager manager1 = new AuthenticationManager(null);
        AuthenticationManager manager2 = new AuthenticationManager(null);

        // Inject a failed attempt into manager1 only
        Field field = AuthenticationManager.class.getDeclaredField("failedAttempts");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap1 = (Map<String, AtomicInteger>) field.get(manager1);
        failedMap1.put("user", new AtomicInteger(3));

        // manager2 must see its own empty map
        assertThat(manager2.getFailedAttemptStats()).isEmpty();
        // manager1 must see its own populated map
        assertThat(manager1.getFailedAttemptStats()).containsKey("user");
    }

    // -----------------------------------------------------------------------
    // API key brute-force / IP lockout tests
    // -----------------------------------------------------------------------

    /**
     * Builds a mock HttpServletRequest that supplies an API key and returns the
     * specified IP from getRemoteAddr(). No proxy headers are present so
     * getClientIP() falls through to getRemoteAddr().
     */
    private HttpServletRequest buildMockRequest(String apiKey, String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        // getClientIP() checks X-Forwarded-For then X-Real-IP then getRemoteAddr().
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        // isApiKeyValid() tries the "apiKey" query param first, then the header.
        when(req.getParameter("apiKey")).thenReturn(null);
        when(req.getHeader("X-API-Key")).thenReturn(apiKey);
        return req;
    }

    /**
     * Calls the private isApiKeyValid(HttpServletRequest) method directly via reflection.
     * This avoids WebUiSession.find() (which requires a real GatewayContext) while still
     * exercising the full lockout logic wired inside isApiKeyValid.
     */
    private boolean invokeIsApiKeyValid(HttpServletRequest req) throws Exception {
        Method method = AuthenticationManager.class.getDeclaredMethod(
                "isApiKeyValid", HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(authManager, req);
    }

    @Test
    void testApiKeyLockout_AfterMaxFailedAttempts() throws Exception {
        // Set up a request with an invalid API key coming from a specific IP.
        String clientIP = "192.168.1.50";
        HttpServletRequest req = buildMockRequest("bad-key", clientIP);

        // Submit MAX_FAILED_ATTEMPTS (5) invalid API key requests.
        // Each call goes through isApiKeyValid() → recordFailedAttempt().
        for (int i = 0; i < 5; i++) {
            boolean result = invokeIsApiKeyValid(req);
            assertThat(result).isFalse();
        }

        // At this point the IP should be locked out.
        // Add a valid key — the next request should still be rejected due to lockout.
        String validKey = AuthenticationManager.generateApiKey();
        authManager.addApiKey(validKey, "legituser");

        HttpServletRequest reqWithValidKey = buildMockRequest(validKey, clientIP);

        boolean result = invokeIsApiKeyValid(reqWithValidKey);
        assertThat(result)
                .as("IP should be locked out even with a valid API key after 5 failed attempts")
                .isFalse();

        // The failed-attempt counter must be present for this IP.
        assertThat(authManager.getFailedAttemptStats()).containsKey(clientIP);
    }

    @Test
    void testApiKeyLockout_ClearsAfterExpiry() throws Exception {
        String clientIP = "10.0.0.1";

        // Manually place an already-expired lockout entry for this IP.
        Field lockoutField = AuthenticationManager.class.getDeclaredField("lockoutUntil");
        lockoutField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> lockoutMap = (Map<String, Long>) lockoutField.get(authManager);
        lockoutMap.put(clientIP, System.currentTimeMillis() - 1L); // expired 1 ms ago

        // Add a valid API key.
        String validKey = AuthenticationManager.generateApiKey();
        authManager.addApiKey(validKey, "user1");

        // A request with a valid key from the IP whose lockout has expired must succeed.
        HttpServletRequest req = buildMockRequest(validKey, clientIP);

        boolean result = invokeIsApiKeyValid(req);
        assertThat(result)
                .as("Expired lockout must not block a valid API key")
                .isTrue();

        // After a successful auth the lockout entry must be cleaned up.
        assertThat(lockoutMap).doesNotContainKey(clientIP);
    }

    // -----------------------------------------------------------------------
    // B1 regression — isAuthenticated() must require a real "user" attribute
    // on the HTTP session, not merely a non-null session.
    // See /modules/.review/FINAL_REVIEW.md §3 (B1).
    // -----------------------------------------------------------------------

    /**
     * Builds a mock RequestContext returning an HttpServletRequest whose session
     * is configured per the supplied callback. The mock has no API-key headers
     * and no proxy headers, so isAuthenticated() falls through to the session
     * check (Method 1b) and then to API-key (Method 2 — returns false).
     *
     * WebUiSession.find() (Method 1a) throws inside the try/catch when called
     * with a bare mock; the implementation logs a trace and proceeds — so this
     * harness exercises Method 1b end-to-end.
     */
    private RequestContext buildMockRequestContext(HttpSession session) {
        // Use lenient strictness: depending on the path through isAuthenticated()
        // (Method 1a may short-circuit, or the API-key path may not be reached),
        // some of these stubs are unused — that's expected and not a test bug.
        HttpServletRequest req = mock(HttpServletRequest.class);
        lenient().when(req.getSession(false)).thenReturn(session);
        // No API key — query param and header both null.
        lenient().when(req.getParameter("apiKey")).thenReturn(null);
        lenient().when(req.getHeader("X-API-Key")).thenReturn(null);
        lenient().when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(req.getHeader("X-Real-IP")).thenReturn(null);
        lenient().when(req.getRemoteAddr()).thenReturn("192.168.1.10");
        lenient().when(req.getLocalAddr()).thenReturn("192.168.1.1");

        RequestContext ctx = mock(RequestContext.class);
        lenient().when(ctx.getRequest()).thenReturn(req);
        return ctx;
    }

    @Test
    void testIsAuthenticated_SessionWithNoUserAttribute_RejectedB1Regression() {
        // REGRESSION: Pre-fix, the code returned `true` for any non-null session
        // (`request.getSession(false) != null`). An attacker could mint a JSESSIONID
        // by hitting any other Gateway servlet and reuse the cookie. Post-fix,
        // the session must additionally carry a "user" attribute populated by the
        // Ignition login flow.
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn(null);

        RequestContext ctx = buildMockRequestContext(session);

        boolean result = authManager.isAuthenticated(ctx);

        assertThat(result)
                .as("A session without a 'user' attribute (e.g. anonymous JSESSIONID minted by another servlet) must NOT be treated as authenticated")
                .isFalse();
    }

    @Test
    void testIsAuthenticated_SessionWithUserAttribute_Accepted() {
        HttpSession session = mock(HttpSession.class);
        // Any non-null user attribute represents an authenticated Ignition session.
        when(session.getAttribute("user")).thenReturn("alice");

        RequestContext ctx = buildMockRequestContext(session);

        boolean result = authManager.isAuthenticated(ctx);

        assertThat(result)
                .as("A session with a populated 'user' attribute must be accepted")
                .isTrue();
    }

    @Test
    void testIsAuthenticated_NoSession_Rejected() {
        // No session at all — must be rejected.
        RequestContext ctx = buildMockRequestContext(null);

        boolean result = authManager.isAuthenticated(ctx);

        assertThat(result)
                .as("A request with no session and no API key must be rejected")
                .isFalse();
    }

    @Test
    void testIsAuthenticated_PrivateIpWithEmptySession_Rejected() {
        // REGRESSION (deleted Method 1c): Pre-fix, a request from any RFC1918
        // address with any active Perspective session anywhere on the gateway
        // was treated as authenticated. That heuristic has been deleted entirely.
        HttpSession session = mock(HttpSession.class);
        lenient().when(session.getAttribute("user")).thenReturn(null);

        HttpServletRequest req = mock(HttpServletRequest.class);
        lenient().when(req.getSession(false)).thenReturn(session);
        lenient().when(req.getParameter("apiKey")).thenReturn(null);
        lenient().when(req.getHeader("X-API-Key")).thenReturn(null);
        lenient().when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(req.getHeader("X-Real-IP")).thenReturn(null);
        // RFC1918 source — used to be enough under the old Method 1c.
        lenient().when(req.getRemoteAddr()).thenReturn("10.0.0.5");
        lenient().when(req.getLocalAddr()).thenReturn("10.0.0.1");

        RequestContext ctx = mock(RequestContext.class);
        lenient().when(ctx.getRequest()).thenReturn(req);

        boolean result = authManager.isAuthenticated(ctx);

        assertThat(result)
                .as("A private-IP request with no authenticated session must NOT be authenticated (Method 1c deleted)")
                .isFalse();
    }

    @Test
    void testApiKeyLockout_SuccessResetsCounter() throws Exception {
        String clientIP = "172.16.0.5";

        // Manually seed some (non-lockout-threshold) failed attempts for this IP.
        Field failedField = AuthenticationManager.class.getDeclaredField("failedAttempts");
        failedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap = (Map<String, AtomicInteger>) failedField.get(authManager);
        failedMap.put(clientIP, new AtomicInteger(3));

        // Add a valid API key and authenticate successfully.
        String validKey = AuthenticationManager.generateApiKey();
        authManager.addApiKey(validKey, "user1");

        HttpServletRequest req = buildMockRequest(validKey, clientIP);

        boolean result = invokeIsApiKeyValid(req);
        assertThat(result)
                .as("Valid API key must succeed when IP has partial failures but is not locked out")
                .isTrue();

        // Successful authentication must clear the IP's failure counter.
        assertThat(failedMap)
                .as("failedAttempts must be cleared for the IP after a successful API key auth")
                .doesNotContainKey(clientIP);
    }
}
