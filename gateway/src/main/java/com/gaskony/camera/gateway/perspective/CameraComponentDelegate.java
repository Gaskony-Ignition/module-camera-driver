package com.gaskony.camera.gateway.perspective;

import com.gaskony.camera.gateway.auth.SessionTokenStore;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.perspective.gateway.api.Component;
import com.inductiveautomation.perspective.gateway.api.ComponentModelDelegate;
import com.inductiveautomation.perspective.gateway.messages.EventFiredMsg;

/**
 * Gateway-side delegate for the camera Perspective components (Camera Viewer / Camera Grid).
 *
 * <p>An instance is created by the Perspective runtime for each rendered camera component,
 * running inside that component's authenticated Perspective session. Its only job is to hand
 * the browser a short-lived {@link SessionTokenStore} token, which the component then sends on
 * its {@code fetch()} calls to {@code /data/camera-driver/*}. This lets Perspective view the
 * gateway's cameras with no API key and no user configuration — exactly "just reference the
 * camera". Because the delegate exists only for a live component in a real session, issuing the
 * token requires no further check: the session is the authorisation.</p>
 *
 * <p>Event protocol (names shared with the client-side {@code CameraAuthDelegate}):</p>
 * <ul>
 *   <li>client &rarr; gateway: {@code "camera-auth-request"} (no payload) — issue/refresh a token</li>
 *   <li>gateway &rarr; client: {@code "camera-auth-token"} {@code { token, ttlMs }}</li>
 * </ul>
 */
public class CameraComponentDelegate extends ComponentModelDelegate {

    private static final String EVENT_REQUEST = "camera-auth-request";
    private static final String EVENT_TOKEN = "camera-auth-token";

    public CameraComponentDelegate(Component component) {
        super(component);
    }

    @Override
    protected void onStartup() {
        // Push a token proactively so the client has one even if its request races startup.
        issueToken();
    }

    @Override
    protected void onShutdown() {
        // Tokens are short-lived and self-expire; nothing per-component to clean up.
    }

    @Override
    public void handleEvent(EventFiredMsg message) {
        if (message != null && EVENT_REQUEST.equals(message.getEventName())) {
            issueToken();
            return;
        }
        super.handleEvent(message);
    }

    private void issueToken() {
        try {
            String token = SessionTokenStore.mint();
            JsonObject payload = new JsonObject();
            payload.addProperty("token", token);
            payload.addProperty("ttlMs", SessionTokenStore.DEFAULT_TTL_MS);
            fireEvent(EVENT_TOKEN, payload);
        } catch (Exception e) {
            log.warn("Failed to issue camera session token", e);
        }
    }
}
