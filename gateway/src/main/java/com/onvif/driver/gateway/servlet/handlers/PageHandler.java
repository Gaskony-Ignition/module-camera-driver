package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.CameraExtensionPoint;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Handles serving HTML pages (player and connection browser).
 */
public class PageHandler extends BaseHandler {

    public PageHandler(GatewayContext context,
                       CameraExtensionPoint cameraExtensionPoint,
                       Go2RtcManager go2RtcManager,
                       AuthenticationManager authManager,
                       String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public Object handlePlayerPage(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Player page request received");

        // P3-CD: shared AccessControl pattern (was: isAuthenticated + sendAuthenticationRequired).
        if (!requireAuthenticated(requestContext, response)) {
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

    public Object handleMsePlayerJs(RequestContext requestContext, HttpServletResponse response) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/pages/mse-player.js")) {
            if (stream == null) {
                logger.error("mse-player.js not found at /pages/mse-player.js");
                response.sendError(404, "mse-player.js not found");
                return null;
            }
            String js = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            response.setContentType("application/javascript; charset=UTF-8");
            response.setHeader("Cache-Control", "public, max-age=3600");
            response.getWriter().write(js);
        } catch (Exception e) {
            logger.error("Error serving mse-player.js", e);
            response.sendError(500, "Internal server error");
        }
        return null;
    }

    public Object handleConnectionBrowserPage(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Connection browser page request received");

        // P3-CD: this route was previously open. The security review flagged
        // it as a HIGH finding (mod-camera-driver.md line 76-81) — every other
        // page handler enforces authentication and the omission here let an
        // unauthenticated caller fingerprint that the Camera Driver module
        // is installed (and load the SPA shell). Now gated through the
        // shared AccessControl helper.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

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
