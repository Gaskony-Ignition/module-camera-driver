package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.RateLimiter;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.gaskony.camera.gateway.util.ValidationUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Proxies WebRTC signaling (SDP offer/answer exchange) between browsers and
 * go2rtc so Perspective/web-ui clients can negotiate sub-second-latency
 * WebRTC playback instead of the fMP4-over-HTTP (MSE) fallback.
 *
 * URL: POST /data/camera-driver/webrtc?device=DeviceName
 * Request body: raw SDP offer, Content-Type: application/sdp
 * Response body: go2rtc's raw SDP answer, Content-Type: application/sdp,
 * with go2rtc's status code passed straight through.
 */
public class WebRtcHandler extends BaseHandler {

    // Signaling is a single quick request/response exchange (not a media
    // stream), so timeouts are kept short rather than reusing the longer
    // proxy timeouts used for actual video streaming.
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 10000;

    private static final String SDP_CONTENT_TYPE = "application/sdp";

    public WebRtcHandler(GatewayContext context,
                         CameraExtensionPoint cameraExtensionPoint,
                         Go2RtcManager go2RtcManager,
                         AuthenticationManager authManager,
                         String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public Object handle(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("WebRTC signaling request received");

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        String deviceName = requestContext.getParameter("device");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: device");
            return null;
        }

        if (!ValidationUtil.isValidDeviceName(deviceName)) {
            response.sendError(400, "Invalid device name format");
            return null;
        }

        CameraDevice device = findDevice(deviceName);
        if (device == null) {
            response.sendError(404, "Device not found");
            return null;
        }

        String offer = requestContext.readBody();
        if (offer == null || offer.trim().isEmpty()) {
            response.sendError(400, "Missing SDP offer body");
            return null;
        }

        // Shared with StreamHandler — see BaseHandler.ensureGo2RtcRegistered.
        ensureGo2RtcRegistered(device);

        if (go2RtcManager == null || !go2RtcManager.isAvailable()) {
            response.sendError(502, "go2rtc streaming service is not available");
            return null;
        }

        String signalingUrl = go2RtcManager.getWebRtcSignalingUrl(deviceName);

        // HttpClient 5 moves the connect timeout onto the connection manager's
        // ConnectionConfig; RequestConfig's setResponseTimeout replaces the old
        // socketTimeout. Same two durations as before.
        RequestConfig proxyConfig = RequestConfig.custom()
            .setResponseTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT_MS))
            .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
            .build();

        try (CloseableHttpClient proxyClient = HttpClientBuilder.create()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(connectionConfig)
                    .build())
                .setDefaultRequestConfig(proxyConfig).build()) {
            HttpPost post = new HttpPost(signalingUrl);
            post.setEntity(new StringEntity(offer, ContentType.create(SDP_CONTENT_TYPE, StandardCharsets.UTF_8)));
            go2RtcManager.applyApiAuth(post);

            try (CloseableHttpResponse upstream = proxyClient.execute(post)) {
                int statusCode = upstream.getCode();
                String body = upstream.getEntity() != null
                    ? EntityUtils.toString(upstream.getEntity(), StandardCharsets.UTF_8)
                    : "";

                response.setStatus(statusCode);
                response.setContentType(SDP_CONTENT_TYPE);
                response.getWriter().write(body);
            }
        } catch (IOException | ParseException e) {
            // Do NOT log the SDP offer/answer or signalingUrl credentials.
            logger.warn("go2rtc WebRTC signaling failed for device {}: {}", deviceName, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(502, "Could not reach the go2rtc WebRTC signaling endpoint");
            }
        }

        return null;
    }
}
