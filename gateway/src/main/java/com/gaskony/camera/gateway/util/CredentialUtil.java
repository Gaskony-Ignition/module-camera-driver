package com.gaskony.camera.gateway.util;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Shared credential utilities for embedding camera credentials into stream URLs.
 * Protocol-agnostic: works with rtsp://, rtsps://, http://, and https:// URLs.
 * Used by all connection types (ONVIF, Generic Camera, etc.).
 */
public final class CredentialUtil {

    private static final Logger logger = LoggerFactory.getLogger(CredentialUtil.class);

    private CredentialUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Resolves a plaintext password from Ignition's SecretConfig.
     *
     * @param gatewayContext the gateway context for secret resolution
     * @param secretConfig the secret config (may be null)
     * @return the plaintext password, or null if not configured or on error
     */
    public static String resolvePassword(GatewayContext gatewayContext, SecretConfig secretConfig) {
        if (secretConfig == null) {
            return null;
        }
        try (Plaintext plaintext = Secret.create(gatewayContext, secretConfig).getPlaintext()) {
            return plaintext.getAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to retrieve password from SecretConfig: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Embeds username:password credentials into a URL.
     * Supports rtsp://, rtsps://, http://, and https:// protocols.
     * Returns the original URL unchanged if:
     * - URL is null/empty
     * - No username provided
     * - Credentials are already embedded (contains @)
     * - URL uses an unsupported protocol
     *
     * @param rawUrl the original URL without credentials
     * @param username the username (may be null)
     * @param password the password (may be null)
     * @return the URL with credentials embedded, or the original URL
     */
    public static String embedCredentials(String rawUrl, String username, String password) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return rawUrl;
        }
        if (username == null || username.trim().isEmpty()) {
            return rawUrl;
        }

        try {
            String protocol;
            String remainder;
            if (rawUrl.startsWith("rtsps://")) {
                protocol = "rtsps://";
                remainder = rawUrl.substring(8);
            } else if (rawUrl.startsWith("rtsp://")) {
                protocol = "rtsp://";
                remainder = rawUrl.substring(7);
            } else if (rawUrl.startsWith("https://")) {
                protocol = "https://";
                remainder = rawUrl.substring(8);
            } else if (rawUrl.startsWith("http://")) {
                protocol = "http://";
                remainder = rawUrl.substring(7);
            } else {
                return rawUrl;
            }

            if (remainder.contains("@")) {
                return rawUrl;
            }

            String credentials = username;
            if (password != null && !password.isEmpty()) {
                credentials += ":" + password;
            }

            return protocol + credentials + "@" + remainder;
        } catch (Exception e) {
            logger.warn("Failed to embed credentials in URL, using original: {}", e.getMessage());
            return rawUrl;
        }
    }
}
