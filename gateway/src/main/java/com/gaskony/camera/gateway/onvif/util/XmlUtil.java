package com.gaskony.camera.gateway.onvif.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * XML parsing utilities for ONVIF SOAP responses.
 * Provides secure XML parsing with XXE protection and convenient DOM navigation methods.
 */
public class XmlUtil {

    private static final DocumentBuilderFactory SECURE_FACTORY;

    static {
        SECURE_FACTORY = DocumentBuilderFactory.newInstance();
        SECURE_FACTORY.setNamespaceAware(true);

        // Prevent XXE (XML External Entity) attacks
        try {
            SECURE_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SECURE_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            SECURE_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            SECURE_FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SECURE_FACTORY.setXIncludeAware(false);
            SECURE_FACTORY.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure secure XML parser", e);
        }
    }

    /**
     * Parses XML string into Document with XXE protection.
     * @param xml XML string to parse
     * @return Parsed DOM Document
     * @throws Exception if parsing fails
     */
    public static Document parseXml(String xml) throws Exception {
        DocumentBuilder builder = SECURE_FACTORY.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Gets text content of first element with given local name in document.
     * @param doc Document to search
     * @param localName Element local name to find
     * @return Text content or empty Optional
     */
    public static Optional<String> getTextContent(Document doc, String localName) {
        return findElement(doc, localName)
            .map(Element::getTextContent);
    }

    /**
     * Gets text content of first element with given local name in document.
     * @param doc Document to search
     * @param localName Element local name to find
     * @param defaultValue Default value if not found
     * @return Text content or default value
     */
    public static String getTextContent(Document doc, String localName, String defaultValue) {
        return getTextContent(doc, localName).orElse(defaultValue);
    }

    /**
     * Gets text content of child element.
     * @param parent Parent element
     * @param childName Child element local name
     * @return Text content or empty Optional
     */
    public static Optional<String> getChildTextContent(Element parent, String childName) {
        return findElement(parent, childName)
            .map(Element::getTextContent);
    }

    /**
     * Gets text content of child element with default value.
     * @param parent Parent element
     * @param childName Child element local name
     * @param defaultValue Default value if not found
     * @return Text content or default value
     */
    public static String getChildTextContent(Element parent, String childName, String defaultValue) {
        return getChildTextContent(parent, childName).orElse(defaultValue);
    }

    /**
     * Finds first element with given local name in document.
     * @param doc Document to search
     * @param localName Element local name to find
     * @return Element or empty Optional
     */
    public static Optional<Element> findElement(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                if (element.getLocalName().equals(localName)) {
                    return Optional.of(element);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds first child element with given local name.
     * @param parent Parent element
     * @param localName Child element local name to find
     * @return Element or empty Optional
     */
    public static Optional<Element> findElement(Element parent, String localName) {
        NodeList children = parent.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                if (child.getLocalName().equals(localName)) {
                    return Optional.of(child);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Parses integer from element text content.
     * @param parent Parent element
     * @param childName Child element local name
     * @return Parsed integer or empty Optional
     */
    public static Optional<Integer> getChildIntContent(Element parent, String childName) {
        return getChildTextContent(parent, childName)
            .flatMap(text -> {
                try {
                    return Optional.of(Integer.parseInt(text));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            });
    }

    /**
     * Parses double from element text content.
     * @param parent Parent element
     * @param childName Child element local name
     * @return Parsed double or empty Optional
     */
    public static Optional<Double> getChildDoubleContent(Element parent, String childName) {
        return getChildTextContent(parent, childName)
            .flatMap(text -> {
                try {
                    return Optional.of(Double.parseDouble(text));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            });
    }

    /**
     * Escapes special XML characters to prevent XML injection attacks.
     * Handles: &lt; &gt; &amp; &quot; &apos;
     * @param text Text to escape
     * @return XML-safe escaped text
     */
    public static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")   // Must be first to avoid double-escaping
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    /**
     * Escapes XML attribute value.
     * @param value Attribute value to escape
     * @return XML-safe escaped attribute value
     */
    public static String escapeXmlAttribute(String value) {
        return escapeXml(value);
    }
}
