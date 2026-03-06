package com.onvif.driver.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages authentication for Camera Driver HTTP endpoints.
 *
 * Supports two authentication methods:
 * 1. Ignition Session Authentication (primary)
 * 2. API Key Authentication (validates against configured API keys)
 *
 * Security Features:
 * - SHA-256 hashed API keys with salt
 * - Secure credential handling
 *
 * @since 2.2.0
 */
public class AuthenticationManager {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationManager.class);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Maximum number of consecutive invalid API-key submissions before an IP is locked out. */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** How long (ms) a locked-out IP must wait before it can attempt again (15 minutes). */
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L;

    /** Holds a salted SHA-256 hash of an API key along with the owning username. */
    private record StoredApiKey(String username, String saltHex, String hashHex) {}

    // Per-IP brute-force protection for API key submissions.
    private final Map<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockoutUntil = new ConcurrentHashMap<>();

    // API Key storage: in-memory map, populated from persistent store on startup.
    // Key: random UUID (generated at addApiKey time); Value: salted-hash record.
    // Using a UUID key allows O(1) removal during iteration without a reverse-lookup map.
    private final Map<String, StoredApiKey> apiKeys = new ConcurrentHashMap<>();

    private final GatewayContext gatewayContext;
    private final ApiKeyStore keyStore;

    public AuthenticationManager(GatewayContext gatewayContext) {
        this.gatewayContext = gatewayContext;
        this.keyStore = new ApiKeyStore(gatewayContext);
        // Load persisted keys into the in-memory map on startup
        Map<String, ApiKeyStore.PersistedKey> persisted = this.keyStore.load();
        for (Map.Entry<String, ApiKeyStore.PersistedKey> e : persisted.entrySet()) {
            apiKeys.put(e.getKey(), new StoredApiKey(e.getValue().username(), e.getValue().saltHex(), e.getValue().hashHex()));
        }
        initializeDefaultApiKeys();
    }

    /**
     * Initialize default API keys for development/testing.
     * In production, these should be configured via module settings.
     */
    private void initializeDefaultApiKeys() {
        // Keys are loaded from persistent storage in the constructor (via ApiKeyStore).
        // Additional keys can be added at runtime via addApiKey().
        logger.info("API key authentication initialized ({} key(s) loaded)", apiKeys.size());
    }

    /**
     * Checks if the request is authenticated using any supported method.
     *
     * @param requestContext The route request context
     * @return true if authenticated, false otherwise
     */
    public boolean isAuthenticated(RequestContext requestContext) {
        // Method 1a: Check for valid Ignition WebUiSession (gateway config UI sessions)
        try {
            if (WebUiSession.find(requestContext).isPresent()) {
                logger.debug("Request authenticated via Ignition WebUiSession");
                return true;
            }
        } catch (Throwable t) {
            logger.trace("WebUiSession check failed: {}", t.getMessage());
        }

        // Method 1b: Check for valid HTTP session (Perspective Designer/runtime sessions)
        // Perspective components run in an authenticated browser context but use a different
        // session type than WebUiSession. If a valid HTTP session exists, the user was
        // authenticated through Ignition's session management (Designer login, Perspective
        // login, etc.) and the session cookie was set during that authentication flow.
        HttpServletRequest request = requestContext.getRequest();
        try {
            HttpSession httpSession = request.getSession(false);
            if (httpSession != null) {
                logger.debug("Request authenticated via HTTP session (Perspective/Designer)");
                return true;
            }
        } catch (Throwable t) {
            logger.trace("HTTP session check failed: {}", t.getMessage());
        }

        // Method 1c: Check for Perspective/Designer sessions from private/local networks.
        // The Designer connects to the gateway from the local network. In Docker deployments,
        // the request comes from the Docker bridge IP (e.g. 172.17.0.1), not loopback.
        // If the request originates from a private/internal IP AND there are active Perspective
        // sessions, it's a Designer or Perspective runtime component request.
        try {
            String remoteAddr = request.getRemoteAddr();
            logger.debug("Method 1c: remoteAddr={}, localAddr={}", remoteAddr, request.getLocalAddr());
            if (isPrivateOrLocalIP(remoteAddr, request.getLocalAddr())) {
                PerspectiveContext pCtx = PerspectiveContext.get(gatewayContext);
                if (pCtx != null && !pCtx.getSessionMonitor().getSessionInfos().isEmpty()) {
                    logger.debug("Request authenticated via private/local IP ({}) + active Perspective session", remoteAddr);
                    return true;
                }
            }
        } catch (Throwable t) {
            // Perspective module not loaded — skip this auth method
            logger.trace("Perspective session check skipped: {}", t.getMessage());
        }

        // Method 2: Check for API key
        if (isApiKeyValid(request)) {
            return true;
        }

        logger.debug("Request not authenticated - no valid session or API key");
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

        // Extract the client IP and check whether it is currently locked out.
        String clientIP = getClientIP(request);
        if (isIPLockedOut(clientIP)) {
            logger.warn("API key request rejected — IP is locked out: {}", clientIP);
            return false;
        }

        for (StoredApiKey stored : apiKeys.values()) {
            if (verifyApiKey(apiKey, stored)) {
                // Successful authentication: clear any accumulated failure state for this IP.
                failedAttempts.remove(clientIP);
                lockoutUntil.remove(clientIP);
                logger.info("API key authentication successful for user: {}", stored.username());
                return true;
            }
        }

        // No matching key found — record the failure and potentially trigger a lockout.
        recordFailedAttempt(clientIP);
        logger.warn("Invalid API key from IP: {}", clientIP);
        return false;
    }

    /**
     * Returns the originating client IP address, honouring standard proxy headers.
     *
     * @param request The HTTP request
     * @return Client IP string
     */
    private static String getClientIP(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIP = request.getHeader("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }
        return request.getRemoteAddr();
    }

    /**
     * Checks whether an IP address is a private/internal network address or loopback.
     * Covers: loopback (127.x.x.x, ::1), RFC 1918 (10.x, 172.16-31.x, 192.168.x),
     * IPv6 link-local (fe80::/10), IPv6 unique local (fc00::/7), and same-machine.
     *
     * @param remoteAddr The remote IP address to check
     * @param localAddr  The local (server) IP address for same-machine comparison
     * @return true if the IP is private, loopback, or same-machine
     */
    static boolean isPrivateOrLocalIP(String remoteAddr, String localAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return false;
        }

        // Same-machine check
        if (remoteAddr.equals(localAddr)) {
            return true;
        }

        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            // InetAddress covers: loopback (127.x, ::1), link-local (169.254.x, fe80::),
            // and site-local (10.x, 172.16-31.x, 192.168.x, fc00::/7)
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the given IP is currently locked out due to too many failed attempts.
     * Expired lockouts are automatically cleared.
     *
     * @param clientIP The client IP address
     * @return true if the IP is locked out, false otherwise
     */
    private boolean isIPLockedOut(String clientIP) {
        Long lockoutEnd = lockoutUntil.get(clientIP);
        if (lockoutEnd == null) {
            return false;
        }
        if (System.currentTimeMillis() < lockoutEnd) {
            return true;
        }
        // Lockout has expired — clean up.
        lockoutUntil.remove(clientIP);
        failedAttempts.remove(clientIP);
        return false;
    }

    /**
     * Records a failed API-key attempt from the given IP. If the IP reaches
     * {@link #MAX_FAILED_ATTEMPTS} consecutive failures it is locked out for
     * {@link #LOCKOUT_DURATION_MS} milliseconds.
     *
     * @param clientIP The client IP address
     */
    private void recordFailedAttempt(String clientIP) {
        AtomicInteger attempts = failedAttempts.computeIfAbsent(clientIP, k -> new AtomicInteger(0));
        int count = attempts.incrementAndGet();
        logger.warn("Failed API key attempt #{} from IP: {}", count, clientIP);

        if (count >= MAX_FAILED_ATTEMPTS) {
            long lockoutEnd = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
            lockoutUntil.put(clientIP, lockoutEnd);
            logger.error("IP locked out for 15 minutes after {} failed API key attempts: {}", count, clientIP);
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
            apiKeys.put(UUID.randomUUID().toString(), stored);
            keyStore.save(toPersistedMap());
            logger.info("API key added for user: {}", username);
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
        java.util.Iterator<java.util.Map.Entry<String, StoredApiKey>> iter = apiKeys.entrySet().iterator();
        while (iter.hasNext()) {
            java.util.Map.Entry<String, StoredApiKey> entry = iter.next();
            if (verifyApiKey(apiKey, entry.getValue())) {
                removedUser = entry.getValue().username();
                iter.remove();
                break;
            }
        }
        if (removedUser != null) {
            keyStore.save(toPersistedMap());
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
            SECURE_RANDOM.nextBytes(salt);
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
        byte[] bytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(bytes);
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

    /**
     * Converts the in-memory apiKeys map into a map of PersistedKey records for saving.
     */
    private Map<String, ApiKeyStore.PersistedKey> toPersistedMap() {
        Map<String, ApiKeyStore.PersistedKey> map = new java.util.HashMap<>();
        for (Map.Entry<String, StoredApiKey> e : apiKeys.entrySet()) {
            map.put(e.getKey(), new ApiKeyStore.PersistedKey(e.getValue().username(), e.getValue().saltHex(), e.getValue().hashHex()));
        }
        return map;
    }
}
