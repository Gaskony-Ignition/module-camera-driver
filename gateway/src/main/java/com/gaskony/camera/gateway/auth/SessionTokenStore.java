package com.gaskony.camera.gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-lived bearer tokens that let the Perspective camera components authenticate to the
 * {@code /data/camera-driver/*} endpoints without an API key or any user configuration.
 *
 * <p>Why this exists: a Perspective session is not a Gateway WebUI session, so a Perspective
 * view's {@code fetch()} carries no credential the {@link AuthenticationManager} recognises
 * (it would always 401 — "Authentication required"). Each camera component instead obtains a
 * token from its gateway-side {@code CameraComponentDelegate}, which runs <em>inside</em> the
 * authenticated Perspective session. Because the delegate only exists for a live, rendered
 * component in a real session, issuing the token IS the authorisation — the session's existence
 * is the proof. The component then sends the token on every request.</p>
 *
 * <p>Security properties:</p>
 * <ul>
 *   <li>Tokens are 256-bit cryptographically-random, URL-safe strings.</li>
 *   <li>Single global namespace: any live token authenticates any camera request. Cameras are
 *       not individually access-controlled at this layer (matching the existing API-key model).</li>
 *   <li>Tokens expire after {@link #DEFAULT_TTL_MS} and are purged lazily on validation/mint.</li>
 *   <li>Static storage mirrors {@link com.gaskony.camera.gateway.servlet.RateLimiter} so the
 *       Perspective delegate and the servlet handlers share one registry without instance wiring.</li>
 * </ul>
 *
 * <p>This is the secure, request-scoped replacement for the deleted "any active Perspective
 * session anywhere on the gateway" heuristic (flagged as an auth bypass): access now requires
 * presenting a live token that was minted for an actual rendered component, not merely the
 * existence of some unrelated session.</p>
 */
public final class SessionTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionTokenStore.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Default token lifetime (ms). Clients refresh well before this elapses. */
    public static final long DEFAULT_TTL_MS = 120_000L;

    /** Hard cap on stored tokens — a backstop against unbounded growth. */
    private static final int MAX_TOKENS = 10_000;

    /**
     * Sweep expired tokens on roughly 1-in-N validate calls.
     * N=50 gives a probabilistic sweep without paying the cost on every hot path.
     */
    private static final int SWEEP_INTERVAL = 50;

    /** Counter used to sample validate calls for periodic sweep. */
    private static final AtomicLong VALIDATE_COUNTER = new AtomicLong(0);

    /** token -&gt; expiry epoch millis. */
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();

    private SessionTokenStore() {
    }

    /** Mints a new token valid for {@link #DEFAULT_TTL_MS}. */
    public static String mint() {
        return mint(DEFAULT_TTL_MS);
    }

    /**
     * Mints a new token valid for the given lifetime.
     *
     * @param ttlMs requested lifetime in milliseconds (floored at 1s)
     * @return the freshly minted token
     */
    public static String mint(long ttlMs) {
        purgeExpired();
        if (TOKENS.size() >= MAX_TOKENS) {
            // Backstop: clear rather than grow unbounded; active clients simply re-request.
            logger.warn("SessionTokenStore exceeded {} tokens - clearing", MAX_TOKENS);
            TOKENS.clear();
        }
        byte[] raw = new byte[32];
        SECURE_RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        TOKENS.put(token, System.currentTimeMillis() + Math.max(1_000L, ttlMs));
        return token;
    }

    /**
     * Reads the token from the {@code X-Camera-Token} request header and validates it.
     *
     * <p>The query-parameter fallback ({@code ?token=}) has been intentionally removed: query
     * parameters are echoed verbatim into gateway access logs and proxy logs, creating a token
     * leak vector. All callers must present the token via the {@code X-Camera-Token} header.</p>
     *
     * <p>On roughly 1-in-{@value #SWEEP_INTERVAL} calls a lightweight sweep removes expired
     * entries so that stale tokens do not accumulate between {@link #mint()} calls.</p>
     *
     * @param request the HTTP request
     * @return true if a live, unexpired token was presented via the header
     */
    public static boolean isValid(HttpServletRequest request) {
        // Periodic background sweep — cheap: only runs on every SWEEP_INTERVAL-th validate call.
        if (VALIDATE_COUNTER.incrementAndGet() % SWEEP_INTERVAL == 0) {
            purgeExpired();
        }

        String token = request.getHeader("X-Camera-Token");
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long expiry = TOKENS.get(token);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            TOKENS.remove(token);
            return false;
        }
        return true;
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        TOKENS.entrySet().removeIf(e -> now >= e.getValue());
    }

    /** Clears all tokens. Call on module shutdown. */
    public static void reset() {
        TOKENS.clear();
    }
}
