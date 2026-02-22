package com.onvif.driver.gateway.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AuthenticationManager.
 *
 * Tests cover:
 * - API key generation (uniqueness, length, non-null)
 * - Salted hashing (same key → different hash each time)
 * - Key verification (correct/wrong key)
 * - addApiKey / removeApiKey lifecycle
 * - Account lockout stats and clearAllLockouts
 *
 * Note: GatewayContext is a compileOnly Ignition dependency unavailable on the test
 * classpath. AuthenticationManager's constructor only uses GatewayContext in
 * validateGatewayCredentials (Basic Auth, untested here), so null is passed safely.
 */
class AuthenticationManagerTest {

    AuthenticationManager authManager;

    // -----------------------------------------------------------------------
    // Lifecycle helpers
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        // GatewayContext is compileOnly; passing null is safe because the constructor
        // only logs in initializeDefaultApiKeys() and never dereferences the field there.
        authManager = new AuthenticationManager(null);
        // Clear the shared static state so tests do not interfere with each other.
        clearStaticApiKeyStore();
        authManager.clearAllLockouts();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearStaticApiKeyStore();
        authManager.clearAllLockouts();
    }

    /** Empties the static apiKeyStore via reflection. */
    private static void clearStaticApiKeyStore() throws Exception {
        Field field = AuthenticationManager.class.getDeclaredField("apiKeyStore");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(null);
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
        Field field = AuthenticationManager.class.getDeclaredField("apiKeyStore");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(null);

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

        Field field = AuthenticationManager.class.getDeclaredField("apiKeyStore");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(null);

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

        Field field = AuthenticationManager.class.getDeclaredField("apiKeyStore");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(null);

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

        Field field = AuthenticationManager.class.getDeclaredField("apiKeyStore");
        field.setAccessible(true);
        Map<?, ?> store = (Map<?, ?>) field.get(null);

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
        // Manually inject a lockout entry into the static maps via reflection
        Field failedAttemptsField = AuthenticationManager.class.getDeclaredField("failedAttempts");
        failedAttemptsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap = (Map<String, AtomicInteger>) failedAttemptsField.get(null);
        failedMap.put("testuser", new AtomicInteger(5));

        Field lockoutUntilField = AuthenticationManager.class.getDeclaredField("lockoutUntil");
        lockoutUntilField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> lockoutMap = (Map<String, Long>) lockoutUntilField.get(null);
        lockoutMap.put("testuser", System.currentTimeMillis() + 999_999L);

        // Verify they are populated before clearing
        assertThat(authManager.getFailedAttemptStats()).isNotEmpty();

        authManager.clearAllLockouts();

        assertThat(authManager.getFailedAttemptStats()).isEmpty();
        assertThat(lockoutMap).isEmpty();
    }

    @Test
    void testGetFailedAttemptStats_ReturnsDefensiveCopy() throws Exception {
        // Populate the static map directly
        Field field = AuthenticationManager.class.getDeclaredField("failedAttempts");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AtomicInteger> failedMap = (Map<String, AtomicInteger>) field.get(null);
        failedMap.put("alice", new AtomicInteger(3));

        Map<String, AtomicInteger> stats = authManager.getFailedAttemptStats();
        assertThat(stats).containsKey("alice");
        assertThat(stats.get("alice").get()).isEqualTo(3);

        // Modifying the returned copy must not affect the original
        stats.clear();
        assertThat(failedMap).containsKey("alice");
    }
}
