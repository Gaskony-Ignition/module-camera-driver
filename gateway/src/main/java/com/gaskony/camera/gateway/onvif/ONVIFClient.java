package com.gaskony.camera.gateway.onvif;

import com.gaskony.camera.gateway.device.CameraConfig.SslValidationMode;
import com.gaskony.camera.gateway.onvif.util.XmlUtil;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF Client for communicating with ONVIF-compatible devices.
 * Implements SOAP-based communication with WS-UsernameToken authentication.
 */
public class ONVIFClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFClient.class);

    private final String deviceUrl;
    private final String username;
    private final String password;
    private final int timeout;
    private final SslValidationMode sslValidationMode;
    private final CloseableHttpClient httpClient;

    // Service endpoints (discovered dynamically)
    private String mediaServiceUrl;
    private String ptzServiceUrl;

    /**
     * Creates a new ONVIF client.
     *
     * @param host IP address or hostname
     * @param port Port number
     * @param username ONVIF username
     * @param password ONVIF password
     * @param useHttps Use HTTPS instead of HTTP
     * @param timeout Connection timeout in seconds
     * @param sslValidationMode SSL/TLS certificate validation mode
     */
    public ONVIFClient(String host, int port, String username, String password,
                      boolean useHttps, int timeout, SslValidationMode sslValidationMode) {
        String protocol = useHttps ? "https" : "http";
        this.deviceUrl = String.format("%s://%s:%d/onvif/device_service", protocol, host, port);
        this.username = username;
        this.password = password;
        this.timeout = timeout * 1000; // Convert to milliseconds
        this.sslValidationMode = sslValidationMode != null ? sslValidationMode : SslValidationMode.STRICT;

        // Configure HTTP client
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(this.timeout)
            .setSocketTimeout(this.timeout)
            .setConnectionRequestTimeout(this.timeout)
            .setRedirectsEnabled(true)  // CRITICAL: Follow HTTP -> HTTPS redirects
            .setMaxRedirects(5)
            .build();

        HttpClientBuilder clientBuilder = HttpClientBuilder.create()
            .setDefaultRequestConfig(requestConfig);

        // Configure HTTP authentication for snapshot URLs
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            new UsernamePasswordCredentials(username, password)
        );
        clientBuilder.setDefaultCredentialsProvider(credentialsProvider);

        // Configure SSL/TLS based on validation mode
        try {
            SSLContext sslContext = createSSLContext(this.sslValidationMode);
            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                sslContext,
                this.sslValidationMode == SslValidationMode.STRICT
                    ? SSLConnectionSocketFactory.getDefaultHostnameVerifier()
                    : NoopHostnameVerifier.INSTANCE
            );
            clientBuilder.setSSLSocketFactory(sslSocketFactory);
            logger.info("SSL configured with {} mode (device uses {})",
                this.sslValidationMode, useHttps ? "HTTPS" : "HTTP");
        } catch (Exception e) {
            logger.warn("Failed to configure SSL context: {}", e.getMessage());
            logger.warn("HTTPS connections may fail with certificate errors");
        }

        this.httpClient = clientBuilder.build();

        logger.info("Created ONVIF client for {}", this.deviceUrl);
    }

    /**
     * Creates SSL context based on the configured validation mode.
     *
     * @param mode SSL validation mode
     * @return Configured SSL context
     * @throws Exception if SSL context creation fails
     */
    private SSLContext createSSLContext(SslValidationMode mode) throws Exception {
        // SECURITY (C5 — /modules/.review/FINAL_REVIEW.md §4): the default for any
        // unknown / unset mode is now STRICT. INSECURE must be requested explicitly
        // by the operator via the device-config form; every poll cycle additionally
        // emits a WARN while INSECURE is active (see ONVIFPoller.poll()).
        if (mode == null) {
            mode = SslValidationMode.STRICT;
        }
        switch (mode) {
            case INSECURE:
                // Explicit opt-in: accept all certificates. Deployment-time decision only —
                // intended for self-signed cameras on isolated networks.
                logger.warn(
                    "ONVIFClient configured with INSECURE SSL validation for {} — "
                        + "accepting any certificate. Do NOT use in production; switch to STRICT.",
                    deviceUrl);
                TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;
                return SSLContextBuilder.create()
                    .loadTrustMaterial(null, acceptingTrustStrategy)
                    .build();

            case TRUST_FIRST_USE:
                // TRUST_FIRST_USE mode is not implemented
                // Certificate pinning would require persistent storage and complexity
                // Users should choose either STRICT (production) or INSECURE (development)
                throw new IllegalArgumentException(
                    "TRUST_FIRST_USE SSL mode is not implemented. " +
                    "Please use STRICT mode (recommended for production) or INSECURE mode (development only). " +
                    "Planned for future release."
                );

            case STRICT:
            default:
                // SECURE DEFAULT — full certificate-chain and hostname validation against
                // the JVM's system trust store. Any unknown enum value also lands here.
                logger.info("Using STRICT SSL validation - full certificate validation");
                return SSLContext.getDefault();
        }
    }

    /**
     * Returns true if this client is configured to bypass SSL/TLS certificate
     * validation (INSECURE mode). Callers (e.g. ONVIFPoller) use this to emit a
     * recurring WARN while the unsafe mode is active.
     *
     * @return true when SSL validation is disabled, false otherwise.
     */
    public boolean isInsecureSslMode() {
        return sslValidationMode == SslValidationMode.INSECURE;
    }

    /**
     * Tests connectivity to the ONVIF device.
     *
     * @return true if connection successful
     */
    public boolean testConnection() {
        try {
            DeviceInformation info = getDeviceInformation();
            return info != null && info.manufacturer() != null;
        } catch (Exception e) {
            logger.error("Connection test failed", e);
            return false;
        }
    }

    /**
     * Gets device information from ONVIF device.
     *
     * @return Device information
     * @throws IOException if communication fails
     */
    public DeviceInformation getDeviceInformation() throws IOException {
        String soapRequest = buildSoapEnvelope(
            "<tds:GetDeviceInformation xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        );

        String response = sendSoapRequest(soapRequest);
        return parseDeviceInformation(response);
    }

    /**
     * Gets available services from ONVIF device.
     *
     * @return List of available services
     * @throws IOException if communication fails
     */
    public List<ONVIFService> getServices() throws IOException {
        String soapRequest = buildSoapEnvelope(
            "<tds:GetServices xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">" +
            "<tds:IncludeCapability>false</tds:IncludeCapability>" +
            "</tds:GetServices>"
        );

        String response = sendSoapRequest(soapRequest);
        List<ONVIFService> services = parseServices(response);

        // Cache service URLs for later use
        for (ONVIFService service : services) {
            String serviceName = service.getServiceName().toLowerCase();
            if (serviceName.contains("media")) {
                mediaServiceUrl = service.getXAddr();
                logger.info("Media service URL: {}", mediaServiceUrl);
            } else if (serviceName.contains("ptz")) {
                ptzServiceUrl = service.getXAddr();
                logger.info("PTZ service URL: {}", ptzServiceUrl);
            }
        }

        return services;
    }

    /**
     * Gets media profiles from ONVIF device.
     *
     * @return List of media profiles
     * @throws IOException if communication fails
     */
    public List<MediaProfile> getMediaProfiles() throws IOException {
        if (mediaServiceUrl == null) {
            throw new IOException("Media service not available - call getServices() first");
        }

        String soapRequest = buildSoapEnvelope(
            "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        );

        String response = sendSoapRequest(mediaServiceUrl, soapRequest);
        return parseMediaProfiles(response);
    }

    /**
     * Gets snapshot URI for a media profile.
     *
     * @param profileToken Profile token
     * @return Snapshot URI
     * @throws IOException if communication fails
     */
    public String getSnapshotUri(String profileToken) throws IOException {
        if (mediaServiceUrl == null) {
            throw new IOException("Media service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<trt:GetSnapshotUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
            "<trt:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</trt:ProfileToken>" +
            "</trt:GetSnapshotUri>"
        );

        String response = sendSoapRequest(mediaServiceUrl, soapRequest);
        return parseUri(response);
    }

    /**
     * Gets stream URI for a media profile.
     *
     * @param profileToken Profile token
     * @return Stream URI
     * @throws IOException if communication fails
     */
    public String getStreamUri(String profileToken) throws IOException {
        if (mediaServiceUrl == null) {
            throw new IOException("Media service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
            "<trt:StreamSetup>" +
            "<tt:Stream xmlns:tt=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</tt:Stream>" +
            "<tt:Transport xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<tt:Protocol>RTSP</tt:Protocol>" +
            "</tt:Transport>" +
            "</trt:StreamSetup>" +
            "<trt:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</trt:ProfileToken>" +
            "</trt:GetStreamUri>"
        );

        String response = sendSoapRequest(mediaServiceUrl, soapRequest);
        return parseUri(response);
    }

    /**
     * Gets PTZ status.
     *
     * @param profileToken Profile token
     * @return PTZ status
     * @throws IOException if communication fails
     */
    public PTZStatus getPTZStatus(String profileToken) throws IOException {
        if (ptzServiceUrl == null) {
            throw new IOException("PTZ service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<tptz:GetStatus xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<tptz:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</tptz:ProfileToken>" +
            "</tptz:GetStatus>"
        );

        String response = sendSoapRequest(ptzServiceUrl, soapRequest);
        return parsePTZStatus(response);
    }

    /**
     * Moves PTZ to absolute position.
     *
     * @param profileToken Profile token
     * @param pan Pan position (-1.0 to 1.0)
     * @param tilt Tilt position (-1.0 to 1.0)
     * @param zoom Zoom position (0.0 to 1.0)
     * @throws IOException if communication fails
     */
    public void absoluteMove(String profileToken, double pan, double tilt, double zoom) throws IOException {
        if (ptzServiceUrl == null) {
            throw new IOException("PTZ service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<tptz:AbsoluteMove xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<tptz:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</tptz:ProfileToken>" +
            "<tptz:Position>" +
            "<tt:PanTilt x=\"" + pan + "\" y=\"" + tilt + "\" xmlns:tt=\"http://www.onvif.org/ver10/schema\"/>" +
            "<tt:Zoom x=\"" + zoom + "\" xmlns:tt=\"http://www.onvif.org/ver10/schema\"/>" +
            "</tptz:Position>" +
            "</tptz:AbsoluteMove>"
        );

        sendSoapRequest(ptzServiceUrl, soapRequest);
    }

    /**
     * Moves PTZ continuously at the given speed until stopped.
     *
     * @param profileToken Profile token
     * @param panSpeed Pan speed (-1.0 to 1.0)
     * @param tiltSpeed Tilt speed (-1.0 to 1.0)
     * @param zoomSpeed Zoom speed (-1.0 to 1.0)
     * @throws IOException if communication fails
     */
    public void continuousMove(String profileToken, double panSpeed, double tiltSpeed, double zoomSpeed) throws IOException {
        if (ptzServiceUrl == null) {
            throw new IOException("PTZ service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<tptz:ContinuousMove xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<tptz:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</tptz:ProfileToken>" +
            "<tptz:Velocity>" +
            "<tt:PanTilt x=\"" + panSpeed + "\" y=\"" + tiltSpeed + "\" xmlns:tt=\"http://www.onvif.org/ver10/schema\"/>" +
            "<tt:Zoom x=\"" + zoomSpeed + "\" xmlns:tt=\"http://www.onvif.org/ver10/schema\"/>" +
            "</tptz:Velocity>" +
            "</tptz:ContinuousMove>"
        );

        sendSoapRequest(ptzServiceUrl, soapRequest);
    }

    /**
     * Stops PTZ movement.
     *
     * @param profileToken Profile token
     * @throws IOException if communication fails
     */
    public void ptzStop(String profileToken) throws IOException {
        if (ptzServiceUrl == null) {
            throw new IOException("PTZ service not available");
        }

        String soapRequest = buildSoapEnvelope(
            "<tptz:Stop xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<tptz:ProfileToken>" + XmlUtil.escapeXml(profileToken) + "</tptz:ProfileToken>" +
            "<tptz:PanTilt>true</tptz:PanTilt>" +
            "<tptz:Zoom>true</tptz:Zoom>" +
            "</tptz:Stop>"
        );

        sendSoapRequest(ptzServiceUrl, soapRequest);
    }

    /**
     * Builds a complete SOAP envelope with authentication.
     *
     * @param body SOAP body content
     * @return Complete SOAP envelope XML
     */
    private String buildSoapEnvelope(String body) {
        String authHeader = ONVIFAuth.generateUsernameToken(username, password);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soap:Envelope " +
            "xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<soap:Header>" + authHeader + "</soap:Header>" +
            "<soap:Body>" + body + "</soap:Body>" +
            "</soap:Envelope>";
    }

    /**
     * Sends SOAP request to ONVIF device.
     *
     * @param soapRequest SOAP envelope XML
     * @return SOAP response XML
     * @throws IOException if communication fails
     */
    private String sendSoapRequest(String soapRequest) throws IOException {
        return sendSoapRequest(deviceUrl, soapRequest);
    }

    /**
     * Sends SOAP request to specific service URL.
     *
     * @param url Service URL
     * @param soapRequest SOAP envelope XML
     * @return SOAP response XML
     * @throws IOException if communication fails
     */
    private String sendSoapRequest(String url, String soapRequest) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/soap+xml; charset=utf-8");
        post.setEntity(new StringEntity(soapRequest, StandardCharsets.UTF_8));

        try {
            HttpResponse response = httpClient.execute(post);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                String responseBody = EntityUtils.toString(entity);
                int statusCode = response.getStatusLine().getStatusCode();

                if (statusCode != 200) {
                    throw new IOException("SOAP request failed with status " + statusCode + ": " + responseBody);
                }

                return responseBody;
            } else {
                throw new IOException("Empty response from ONVIF device");
            }
        } catch (IOException e) {
            logger.error("Failed to send SOAP request to {}", url, e);
            throw e;
        }
    }

    /**
     * Parses GetDeviceInformation response.
     *
     * @param xml SOAP response XML
     * @return Device information
     */
    private DeviceInformation parseDeviceInformation(String xml) {
        try {
            Document doc = parseXml(xml);

            String manufacturer = getTextContent(doc, "Manufacturer");
            String model = getTextContent(doc, "Model");
            String firmwareVersion = getTextContent(doc, "FirmwareVersion");
            String serialNumber = getTextContent(doc, "SerialNumber");
            String hardwareId = getTextContent(doc, "HardwareId");

            DeviceInformation info = new DeviceInformation(
                manufacturer, model, firmwareVersion, serialNumber, hardwareId
            );

            logger.info("Parsed device info: {}", info);
            return info;

        } catch (Exception e) {
            logger.error("Failed to parse device information", e);
            return null;
        }
    }

    /**
     * Parses GetServices response.
     *
     * @param xml SOAP response XML
     * @return List of services
     */
    private List<ONVIFService> parseServices(String xml) {
        List<ONVIFService> services = new ArrayList<>();

        try {
            Document doc = parseXml(xml);
            NodeList serviceNodes = doc.getElementsByTagName("tds:Service");

            for (int i = 0; i < serviceNodes.getLength(); i++) {
                Element serviceElement = (Element) serviceNodes.item(i);

                String namespace = getChildTextContent(serviceElement, "Namespace");
                String xAddr = getChildTextContent(serviceElement, "XAddr");

                // Get version
                Element versionElement = (Element) serviceElement.getElementsByTagName("Version").item(0);
                String version = "Unknown";
                if (versionElement != null) {
                    String major = getChildTextContent(versionElement, "Major");
                    String minor = getChildTextContent(versionElement, "Minor");
                    version = major + "." + minor;
                }

                ONVIFService service = new ONVIFService(namespace, xAddr, version);
                services.add(service);
                logger.debug("Found service: {}", service);
            }

            logger.info("Parsed {} services", services.size());

        } catch (Exception e) {
            logger.error("Failed to parse services", e);
        }

        return services;
    }

    /**
     * Parses XML string into Document with XXE protection.
     * Delegates to XmlUtil for secure parsing.
     */
    private Document parseXml(String xml) throws Exception {
        return XmlUtil.parseXml(xml);
    }

    /**
     * Gets text content of first element with given tag name.
     * Delegates to XmlUtil.
     */
    private String getTextContent(Document doc, String tagName) {
        return XmlUtil.getTextContent(doc, tagName).orElse(null);
    }

    /**
     * Gets text content of child element.
     * Delegates to XmlUtil.
     */
    private String getChildTextContent(Element parent, String childName) {
        return XmlUtil.getChildTextContent(parent, childName).orElse(null);
    }

    /**
     * Parses GetProfiles response.
     */
    private List<MediaProfile> parseMediaProfiles(String xml) {
        List<MediaProfile> profiles = new ArrayList<>();

        try {
            Document doc = parseXml(xml);
            NodeList profileNodes = doc.getElementsByTagName("*");

            for (int i = 0; i < profileNodes.getLength(); i++) {
                if (profileNodes.item(i) instanceof Element) {
                    Element element = (Element) profileNodes.item(i);
                    if (element.getLocalName().equals("Profiles")) {
                        MediaProfile profile = new MediaProfile();
                        profile.setToken(element.getAttribute("token"));
                        profile.setName(getChildTextContent(element, "Name"));

                        // Parse video encoder configuration
                        Element videoEncoder = findElement(element, "VideoEncoderConfiguration");
                        if (videoEncoder != null) {
                            profile.setEncoding(getChildTextContent(videoEncoder, "Encoding"));

                            Element resolution = findElement(videoEncoder, "Resolution");
                            if (resolution != null) {
                                String width = getChildTextContent(resolution, "Width");
                                String height = getChildTextContent(resolution, "Height");
                                if (width != null) profile.setWidth(Integer.parseInt(width));
                                if (height != null) profile.setHeight(Integer.parseInt(height));
                            }

                            String frameRate = getChildTextContent(videoEncoder, "FrameRate");
                            String bitrate = getChildTextContent(videoEncoder, "Bitrate");
                            if (frameRate != null) profile.setFrameRate(Integer.parseInt(frameRate));
                            if (bitrate != null) profile.setBitrate(Integer.parseInt(bitrate));
                        }

                        profiles.add(profile);
                        logger.debug("Parsed media profile: {}", profile);
                    }
                }
            }

            logger.info("Parsed {} media profiles", profiles.size());

        } catch (Exception e) {
            logger.error("Failed to parse media profiles", e);
        }

        return profiles;
    }

    /**
     * Parses URI response (for snapshot or stream URI).
     */
    private String parseUri(String xml) {
        try {
            Document doc = parseXml(xml);
            String uri = getTextContent(doc, "Uri");
            logger.debug("Parsed URI: {}", uri);
            return uri;
        } catch (Exception e) {
            logger.error("Failed to parse URI", e);
            return null;
        }
    }

    /**
     * Parses PTZ status response.
     */
    private PTZStatus parsePTZStatus(String xml) {
        try {
            Document doc = parseXml(xml);
            PTZStatus status = new PTZStatus();

            // Find PanTilt element
            Element panTilt = findElementByName(doc, "PanTilt");
            if (panTilt != null) {
                String panStr = panTilt.getAttribute("x");
                String tiltStr = panTilt.getAttribute("y");
                if (panStr != null && !panStr.isEmpty()) {
                    status.setPan(Double.parseDouble(panStr));
                }
                if (tiltStr != null && !tiltStr.isEmpty()) {
                    status.setTilt(Double.parseDouble(tiltStr));
                }
            }

            // Find Zoom element
            Element zoom = findElementByName(doc, "Zoom");
            if (zoom != null) {
                String zoomStr = zoom.getAttribute("x");
                if (zoomStr != null && !zoomStr.isEmpty()) {
                    status.setZoom(Double.parseDouble(zoomStr));
                }
            }

            // Find move status
            String moveStatus = getTextContent(doc, "MoveStatus");
            if (moveStatus != null) {
                status.setMoveStatus(moveStatus);
            } else {
                status.setMoveStatus("IDLE");
            }

            logger.debug("Parsed PTZ status: {}", status);
            return status;

        } catch (Exception e) {
            logger.error("Failed to parse PTZ status", e);
            return new PTZStatus();
        }
    }

    /**
     * Finds element by local name.
     */
    private Element findElementByName(Document doc, String localName) {
        return XmlUtil.findElement(doc, localName).orElse(null);
    }

    /**
     * Finds child element by local name.
     * Delegates to XmlUtil.
     */
    private Element findElement(Element parent, String localName) {
        return XmlUtil.findElement(parent, localName).orElse(null);
    }

    /**
     * Gets a snapshot image from the camera as a byte array.
     * This method retrieves the snapshot URI and then fetches the actual JPEG image.
     *
     * @param profileToken Profile token to get snapshot from
     * @return JPEG image as byte array
     * @throws IOException if snapshot retrieval fails
     */
    public byte[] getSnapshot(String profileToken) throws IOException {
        // Get snapshot URI from camera
        String snapshotUri = getSnapshotUri(profileToken);

        if (snapshotUri == null || snapshotUri.isEmpty()) {
            throw new IOException("Snapshot URI not available for profile: " + profileToken);
        }

        logger.debug("Fetching snapshot from: {}", snapshotUri);

        // Create HTTP GET request
        HttpGet httpGet = new HttpGet(snapshotUri);

        try {
            // Execute request
            HttpResponse response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();

            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                String errorMsg = String.format("Snapshot request failed with status %d: %s",
                    statusCode, response.getStatusLine().getReasonPhrase());
                logger.error(errorMsg);
                throw new IOException(errorMsg);
            }

            if (entity == null) {
                throw new IOException("No content in snapshot response");
            }

            // Read image bytes
            byte[] imageBytes = EntityUtils.toByteArray(entity);

            logger.debug("Retrieved snapshot: {} bytes", imageBytes.length);

            return imageBytes;

        } catch (IOException e) {
            logger.error("Failed to retrieve snapshot from {}: {}", snapshotUri, e.getMessage());
            throw e;
        } finally {
            httpGet.releaseConnection();
        }
    }

    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
