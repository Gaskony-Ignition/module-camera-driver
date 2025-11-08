package com.onvif.driver.gateway.onvif;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final CloseableHttpClient httpClient;

    /**
     * Creates a new ONVIF client.
     *
     * @param host IP address or hostname
     * @param port Port number
     * @param username ONVIF username
     * @param password ONVIF password
     * @param useHttps Use HTTPS instead of HTTP
     * @param timeout Connection timeout in seconds
     */
    public ONVIFClient(String host, int port, String username, String password,
                      boolean useHttps, int timeout) {
        String protocol = useHttps ? "https" : "http";
        this.deviceUrl = String.format("%s://%s:%d/onvif/device_service", protocol, host, port);
        this.username = username;
        this.password = password;
        this.timeout = timeout * 1000; // Convert to milliseconds

        // Configure HTTP client
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(this.timeout)
            .setSocketTimeout(this.timeout)
            .setConnectionRequestTimeout(this.timeout)
            .build();

        this.httpClient = HttpClientBuilder.create()
            .setDefaultRequestConfig(requestConfig)
            .build();

        logger.info("Created ONVIF client for {}", this.deviceUrl);
    }

    /**
     * Tests connectivity to the ONVIF device.
     *
     * @return true if connection successful
     */
    public boolean testConnection() {
        try {
            DeviceInformation info = getDeviceInformation();
            return info != null && info.getManufacturer() != null;
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
        return parseServices(response);
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
        HttpPost post = new HttpPost(deviceUrl);
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
            logger.error("Failed to send SOAP request to {}", deviceUrl, e);
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
     * Parses XML string into Document.
     */
    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Gets text content of first element with given tag name.
     */
    private String getTextContent(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (element.getLocalName().equals(tagName)) {
                return element.getTextContent();
            }
        }
        return null;
    }

    /**
     * Gets text content of child element.
     */
    private String getChildTextContent(Element parent, String childName) {
        NodeList children = parent.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Element child = (Element) children.item(i);
            if (child.getLocalName().equals(childName)) {
                return child.getTextContent();
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
