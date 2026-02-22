package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Handles serving HTML pages (player and connection browser).
 */
public class PageHandler extends BaseHandler {

    public PageHandler(GatewayContext context,
                       ONVIFDeviceExtensionPoint deviceExtensionPoint,
                       GenericCameraExtensionPoint genericCameraExtensionPoint,
                       Go2RtcManager go2RtcManager,
                       AuthenticationManager authManager,
                       String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Handles serving the embeddable video player page.
     * URL: http://gateway:8088/data/camera-driver/player?device=DeviceName
     *
     * Designed to be embedded in Perspective Inline Frame component.
     */
    public Object handlePlayerPage(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Player page request received");

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try (InputStream stream = getClass().getResourceAsStream("/pages/player.html")) {
            if (stream == null) {
                logger.error("Player page not found at /pages/player.html");
                response.sendError(404, "Player page not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving player page", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }

    /**
     * Handles serving the connection browser HTML page.
     * URL: http://gateway:8088/data/camera-driver/connection-browser
     */
    public Object handleConnectionBrowserPage(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Connection browser page request received");

        try (InputStream stream = getClass().getResourceAsStream("/pages/connection-browser.html")) {
            if (stream == null) {
                logger.error("Connection browser page not found at /pages/connection-browser.html");
                response.sendError(404, "Connection browser page not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving connection browser page", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }
}
