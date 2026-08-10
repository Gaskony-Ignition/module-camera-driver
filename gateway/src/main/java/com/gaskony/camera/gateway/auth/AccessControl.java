package com.gaskony.camera.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Static access-control helpers for Camera Driver route handlers.
 *
 * <p>Per /modules/.review/FINAL_REVIEW.md §5 P3 (P3-CD): the camera driver
 * mounts every route with {@code AccessControlStrategy.OPEN_ROUTE} and relies
 * on each handler to call {@link AuthenticationManager#isAuthenticated} +
 * write a 401 itself. That pattern is one missing line away from a leak —
 * {@code PageHandler.handleConnectionBrowserPage} was exactly that bug
 * (security review {@code mod-camera-driver.md} HIGH; was fingerprintable
 * and disclosed module presence to unauthenticated callers).</p>
 *
 * <p>This class follows the PLC Emulator's {@code GatewayAuthHelper} pattern
 * (see {@code /modules/ignition-module-plc-emulator/.../web/GatewayAuthHelper.java}):
 * a single static gate that handlers invoke at the top of each route. If
 * authentication fails, the helper writes a 401 directly and returns
 * {@code false} so the handler can short-circuit.</p>
 *
 * <p>The implementation deliberately does not reach into Ignition role groups
 * via {@code GatewayContext.getUserSourceManager()} because {@link AuthenticationManager}
 * also serves API-key callers that have no Ignition user record.</p>
 *
 * <p>There is deliberately NO {@code requireAdministrator} helper. One existed
 * and was a strict alias for {@link #requireAuthenticated}, kept so handlers
 * could "mark intent" ahead of a role-group integration that never landed. No
 * handler ever called it, so it protected nothing — but a method with that
 * name is a trap: the next person to call it would reasonably believe an admin
 * check had happened. Removed 10/08/2026. If a real role check is wanted, add
 * one that actually checks, via {@code GatewayContext.getUserSourceManager()}.</p>
 *
 * <p>This is a per-module duplication of the PLC pattern (intentional — see
 * SPRINT2_PLAN.md "Out of scope" — Sprint 3 may promote a shared
 * {@code gaskony-shared} jar).</p>
 */
public final class AccessControl {

    private static final Logger logger = LoggerFactory.getLogger(AccessControl.class);

    private AccessControl() {
        // utility — no instances
    }

    /**
     * Validates that the request is authenticated. On failure, writes a 401
     * response and returns {@code false}; the caller should immediately
     * return without further work.
     *
     * <p>Mirrors {@code GatewayAuthHelper.requireAuthentication} from the
     * PLC Emulator module (P3 source pattern).</p>
     *
     * @param authManager the module's authentication manager
     * @param ctx         the route request context
     * @param resp        the response to write the 401 to
     * @return {@code true} if authenticated, {@code false} otherwise (with
     *         401 already written)
     * @throws IOException if writing the 401 fails
     */
    public static boolean requireAuthenticated(AuthenticationManager authManager,
                                               RequestContext ctx,
                                               HttpServletResponse resp) throws IOException {
        if (authManager == null) {
            // Fail closed — a missing auth manager is a bug, not a free pass.
            logger.error("AccessControl.requireAuthenticated invoked with null AuthenticationManager — denying request");
            if (!resp.isCommitted()) {
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required (auth manager not initialised)");
            }
            return false;
        }

        if (authManager.isAuthenticated(ctx)) {
            return true;
        }

        if (!resp.isCommitted()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "Authentication required. Please log in to the Ignition Gateway or provide an API key.");
        }
        return false;
    }

}
