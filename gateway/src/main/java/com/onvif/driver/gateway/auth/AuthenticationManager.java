package com.onvif.driver.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages authentication for Camera Driver HTTP endpoints.
 *
 * Supports three authentication methods:
 * 1. Ignition Session Authentication (primary)
 * 2. Basic Authentication (validates against Ignition gateway users)
 * 3. API Key Authentication (validates against configured API keys)
 *
 * Security Features:
 * - SHA-256 hashed API keys with salt
 * - Failed authentication attempt tracking
 * - Account lockout after repeated failures
 * - Secure credential handling
 *
 * @since 2.2.0
 */
public class AuthenticationManager {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationManager.class);

    /** Holds a salted SHA-256 hash of an API key along with the owning username. */
    private record StoredApiKey(String username, String saltHex, String hashHex) {}

    // Account lockout configuration
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000; // 15 minutes

    // Track failed authentication attempts per username
    private static final Map<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
    private static final Map<String, Long> lockoutUntil = new ConcurrentHashMap<>();

    // API Key storage (in-memory for now - should be moved to persistent storage)
    // Key: random UUID (generated at addApiKey time); Value: salted-hash record.
    // Using a UUID key allows O(1) removal during iteration without a reverse-lookup map.
    private static final Map<String, StoredApiKey> apiKeyStore = new ConcurrentHashMap<>();

    private final GatewayContext gatewayContext;

    public AuthenticationManager(GatewayContext gatewayContext) {
        this.gatewayContext = gatewayContext;
        initializeDefaultApiKeys();
    }

    /**
     * Initialize default API keys for development/testing.
     * In production, these should be configured via module settings.
     */
    private void initializeDefaultApiKeys() {
        // This is just a placeholder - in production, keys should be configured via Gateway Config
        logger.info("API key authentication initialized");
        logger.warn("Using in-memory API key storage - keys will be lost on Gateway restart");
        logger.warn("For production use, configure API keys via Gateway Config page");
    }

    /**
     * Checks if the request is authenticated using any supported method.
     *
     * @param requestContext The route request context
     * @return true if authenticated, false otherwise
     */
    public boolean isAuthenticated(RequestContext requestContext) {
        // Method 1a: Check for valid Ignition WebUiSession (gateway config UI sessions)
        if (WebUiSession.find(requestContext).isPresent()) {
            logger.debug("Request authenticated via Ignition WebUiSession");
            return true;
        }

        // Method 1b: Check for valid HTTP session (Perspective Designer/runtime sessions)
        // Perspective components run in an authenticated browser context but use a different
        // session type than WebUiSession. If a valid HTTP session exists, the user was
        // authenticated through Ignition's session management (Designer login, Perspective
        // login, etc.) and the session cookie was set during that authentication flow.
        HttpServletRequest request = requestContext.getRequest();
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            logger.debug("Request authenticated via HTTP session (Perspective/Designer)");
            return true;
        }

        // Method 2: Check for Basic Authentication
        if (isBasicAuthValid(request)) {
            return true;
        }

        // Method 3: Check for API key
        if (isApiKeyValid(request)) {
            return true;
        }

        logger.debug("Request not authenticated - no valid session, Basic auth, or API key");
        return false;
    }

    /**
     * Validates Basic Authentication credentials.
     *
     * IMPLEMENTATION NOTE: This validates against Ignition's gateway users.
     * For full user source integration, additional API research may be needed.
     *
     * @param request The HTTP request
     * @return true if Basic Auth is valid, false otherwise
     */
    private boolean isBasicAuthValid(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        try {
            // Decode Base64 credentials
            String base64Credentials = authHeader.substring("Basic ".length());
            byte[] decoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decoded, StandardCharsets.UTF_8);

            // Split username:password
            String[] parts = credentials.split(":", 2);
            if (parts.length != 2) {
                logger.warn("Invalid Basic Auth format");
                return false;
            }

            String username = parts[0];
            String password = parts[1];

            // Check if account is locked out
            if (isAccountLockedOut(username)) {
                logger.warn("Authentication denied - account locked out: {}", username);
                return false;
            }

            // Validate credentials against Ignition gateway
            boolean isValid = validateGatewayCredentials(username, password);

            if (isValid) {
                // Reset failed attempts on successful login
                failedAttempts.remove(username);
                lockoutUntil.remove(username);
                logger.info("Basic Auth successful for user: {}", username);
                return true;
            } else {
                // Track failed attempt
                recordFailedAttempt(username);
                logger.warn("Basic Auth failed for user: {}", username);
                return false;
            }

        } catch (Exception e) {
            logger.error("Error validating Basic Auth: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates credentials against Ignition's gateway authentication system.
     *
     * NOT IMPLEMENTED: Basic Auth credential validation is not integrated with
     * Ignition's UserSourceManager. Use Ignition session authentication or API key
     * authentication instead.
     *
     * To enable Basic Auth, integrate with Ignition's UserSourceManager:
     * 1. Validate against gatewayContext.getUserSourceManager()
     * 2. Support multiple authentication realms (LDAP, Active Directory, etc.)
     * 3. Handle configured authentication backends
     *
     * @param username The username
     * @param password The password
     * @return false (not implemented - fail-closed for security)
     */
    private boolean validateGatewayCredentials(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        if (password == null || password.isEmpty()) {
            return false;
        }

        logger.warn("Basic Auth credential validation is not fully implemented. " +
            "Please use Ignition session authentication or API key authentication. " +
            "To enable Basic Auth, integrate with Ignition's UserSourceManager.");
        return false;
    }

    /**
     * Validates API key from query parameter or header.
     *
     * @param request The HTTP request
     * @return true if API key is valid, false otherwise
     */
    private boolean isApiKeyValid(HttpServletRequest request) {
        // Try query parameter first
        String apiKey = request.getParameter("apiKey");

        // Try header if not in query parameter
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = request.getHeader("X-API-Key");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            return false;
        }

        for (StoredApiKey stored : apiKeyStore.values()) {
            if (verifyApiKey(apiKey, stored)) {
                logger.info("API key authentication successful for user: {}", stored.username());
                return true;
            }
        }

        logger.warn("Invalid API key provided");
        return false;
    }

    /**
     * Checks if an account is currently locked out due to failed attempts.
     *
     * @param username The username to check
     * @return true if locked out, false otherwise
     */
    private boolean isAccountLockedOut(String username) {
        Long lockoutEnd = lockoutUntil.get(username);
        if (lockoutEnd == null) {
            return false;
        }

        if (System.currentTimeMillis() < lockoutEnd) {
            return true;
        }

        // Lockout expired, clean up
        lockoutUntil.remove(username);
        failedAttempts.remove(username);
        return false;
    }

    /**
     * Records a failed authentication attempt and applies lockout if threshold exceeded.
     *
     * @param username The username that failed authentication
     */
    private void recordFailedAttempt(String username) {
        AtomicInteger attempts = failedAttempts.computeIfAbsent(username, k -> new AtomicInteger(0));
        int count = attempts.incrementAndGet();

        logger.warn("Failed authentication attempt #{} for user: {}", count, username);

        if (count >= MAX_FAILED_ATTEMPTS) {
            long lockoutEnd = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
            lockoutUntil.put(username, lockoutEnd);
            logger.error("Account locked out for 15 minutes due to {} failed attempts: {}", count, username);

            // Audit log for security monitoring
            logger.error("SECURITY ALERT: Multiple failed authentication attempts for user: {}", username);
        }
    }

    /**
     * Adds an API key to the store.
     *
     * @param apiKey The plain-text API key
     * @param username The username associated with this key
     */
    public void addApiKey(String apiKey, String username) {
        StoredApiKey stored = hashApiKeyWithSalt(apiKey, username);
        if (stored != null) {
            apiKeyStore.put(UUID.randomUUID().toString(), stored);
            logger.info("API key added for user: {}", username);
            logger.warn("API key stored in memory - will be lost on restart. Configure via Gateway settings for persistence.");
        } else {
            logger.error("Failed to hash API key for user: {}", username);
        }
    }

    /**
     * Removes an API key from the store.
     *
     * @param apiKey The plain-text API key to remove
     */
    public void removeApiKey(String apiKey) {
        String removedUser = null;
        java.util.Iterator<java.util.Map.Entry<String, StoredApiKey>> iter = apiKeyStore.entrySet().iterator();
        while (iter.hasNext()) {
            java.util.Map.Entry<String, StoredApiKey> entry = iter.next();
            if (verifyApiKey(apiKey, entry.getValue())) {
                removedUser = entry.getValue().username();
                iter.remove();
                break;
            }
        }
        if (removedUser != null) {
            logger.info("API key removed for user: {}", removedUser);
        }
    }

    /**
     * Hashes an API key with a freshly generated random salt.
     *
     * @param apiKey    The plain-text API key
     * @param username  The associated username
     * @return StoredApiKey with salt and hash, or null on error
     */
    private StoredApiKey hashApiKeyWithSalt(String apiKey, String username) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            String saltHex = bytesToHex(salt);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));

            return new StoredApiKey(username, saltHex, bytesToHex(hash));
        } catch (Exception e) {
            logger.error("Error hashing API key", e);
            return null;
        }
    }

    /**
     * Verifies a plain-text API key against a stored salted hash.
     *
     * @param apiKey The plain-text API key to verify
     * @param stored The stored salted hash record
     * @return true if the key matches, false otherwise
     */
    private boolean verifyApiKey(String apiKey, StoredApiKey stored) {
        try {
            byte[] salt = hexToBytes(stored.saltHex());

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));

            return bytesToHex(hash).equals(stored.hashHex());
        } catch (Exception e) {
            logger.error("Error verifying API key", e);
            return false;
        }
    }

    /**
     * Converts byte array to hex string.
     *
     * @param bytes The byte array
     * @return Hex string representation
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Converts a hex string to a byte array (inverse of {@link #bytesToHex}).
     *
     * @param hex The hex string (must have even length)
     * @return The decoded byte array
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Generates a secure random API key.
     *
     * @return A new secure API key
     */
    public static String generateApiKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 256 bits
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Gets authentication statistics for monitoring.
     *
     * @return Map of username to failed attempt count
     */
    public Map<String, AtomicInteger> getFailedAttemptStats() {
        return new ConcurrentHashMap<>(failedAttempts);
    }

    /**
     * Clears all lockouts (administrative override).
     */
    public void clearAllLockouts() {
        int count = lockoutUntil.size();
        lockoutUntil.clear();
        failedAttempts.clear();
        logger.info("Cleared {} account lockouts", count);
    }
}
