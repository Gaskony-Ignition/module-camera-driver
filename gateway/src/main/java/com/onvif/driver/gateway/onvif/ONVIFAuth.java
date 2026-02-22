package com.onvif.driver.gateway.onvif;

import com.onvif.driver.gateway.onvif.util.XmlUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

/**
 * ONVIF WS-UsernameToken Authentication.
 * Generates authentication headers for ONVIF SOAP requests.
 */
public class ONVIFAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates WS-UsernameToken header for ONVIF authentication.
     *
     * @param username ONVIF username
     * @param password ONVIF password
     * @return XML string containing the WS-Security header
     */
    public static String generateUsernameToken(String username, String password) {
        try {
            // Generate random nonce (16 bytes)
            byte[] nonceBytes = new byte[16];
            RANDOM.nextBytes(nonceBytes);
            String nonce = Base64.getEncoder().encodeToString(nonceBytes);

            // Generate timestamp in ISO 8601 format
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            String created = dateFormat.format(new Date());

            // Generate password digest: Base64( SHA-1( nonce + created + password ) )
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(nonceBytes);
            digest.update(created.getBytes(StandardCharsets.UTF_8));
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            String passwordDigest = Base64.getEncoder().encodeToString(digest.digest());

            // Build WS-Security header
            return String.format(
                "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
                "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
                "<wsse:UsernameToken>" +
                "<wsse:Username>%s</wsse:Username>" +
                "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">%s</wsse:Password>" +
                "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">%s</wsse:Nonce>" +
                "<wsu:Created>%s</wsu:Created>" +
                "</wsse:UsernameToken>" +
                "</wsse:Security>",
                XmlUtil.escapeXml(username), passwordDigest, nonce, created
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ONVIF authentication token", e);
        }
    }
}
