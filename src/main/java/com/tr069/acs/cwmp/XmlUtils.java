package com.tr069.acs.cwmp;

import com.tr069.acs.common.exception.SoapException;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.List;

/**
 * CWMP 报文的 DOM 构建与序列化工具。
 *
 * <p>对齐 Python 版 ElementTree 的输出行为：无 XML 声明、无缩进空白。</p>
 */
@Slf4j
public final class XmlUtils {

    private XmlUtils() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 对应 Python 版 cwmp:ID 缺省时的 random.randint(1, 1000000)。
     */
    public static String randomCwmpId() {
        return String.valueOf(RANDOM.nextInt(1000000) + 1);
    }

    public static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.newDocument();
        } catch (Exception e) {
            throw new SoapException("创建 XML Document 失败", e);
        }
    }

    /**
     * 创建带命名空间的带前缀元素。
     */
    public static Element element(Document doc, String nsUri, String prefix, String localName) {
        Element el = doc.createElementNS(nsUri, prefix + ":" + localName);
        el.setPrefix(prefix);
        return el;
    }

    /**
     * 创建无命名空间的纯文本元素。
     */
    public static Element plainElement(Document doc, String localName, String value) {
        Element el = doc.createElement(localName);
        if (value != null) {
            el.setTextContent(value);
        }
        return el;
    }

    /**
     * 追加普通文本子元素。
     */
    public static Element appendText(Document doc, Element parent, String nsUri, String prefix,
                                     String localName, String value) {
        Element el = element(doc, nsUri, prefix, localName);
        el.setTextContent(value == null ? "" : value);
        parent.appendChild(el);
        return el;
    }

    /**
     * 追加带 xsi:type 的子元素。
     *
     * @param xsdType 不含 xsd: 前缀的类型名，如 string / unsignedInt / boolean
     */
    public static Element appendTyped(Document doc, Element parent, String nsUri, String prefix,
                                      String localName, String value, String xsdType) {
        Element el = element(doc, nsUri, prefix, localName);
        el.setAttributeNS(SoapNamespace.XSI, SoapNamespace.PREFIX_XSI + ":type",
                SoapNamespace.PREFIX_XSD + ":" + (xsdType == null || xsdType.isEmpty() ? "string" : xsdType));
        el.setTextContent(value == null ? "" : value);
        parent.appendChild(el);
        return el;
    }

    /**
     * 追加空值元素：{@code <tag xsi:nil="true"/>}。
     *
     * <p>对齐 Python 版的判定 {@code value == ""}，此处同时把 null 视为空值。</p>
     */
    public static Element appendNil(Document doc, Element parent, String nsUri, String prefix, String localName) {
        Element el = element(doc, nsUri, prefix, localName);
        el.setAttributeNS(SoapNamespace.XSI, SoapNamespace.PREFIX_XSI + ":nil", "true");
        parent.appendChild(el);
        return el;
    }

    /**
     * 值非空则写文本，否则写 nil。对应 Python 版分支 {@code != ""}。
     */
    public static void appendTextOrNil(Document doc, Element parent, String nsUri, String prefix,
                                       String localName, String value) {
        if (value == null || value.isEmpty()) {
            appendNil(doc, parent, nsUri, prefix, localName);
        } else {
            appendText(doc, parent, nsUri, prefix, localName, value);
        }
    }

    /**
     * 追加 SOAP-ENC 数组元素。
     *
     * <p>arrayType 使用完全限定名，如 {@code cwmp:ParameterValueStruct[3]}、{@code xsd:string[2]}。</p>
     *
     * @param itemTag    数组项的 tag 名，如 string / base64，此类元素不带命名空间
     * @param nsUri      数组容器自身的命名空间
     * @param arrayType  arrayType 属性值
     */
    public static Element appendArray(Document doc, Element parent, String nsUri, String prefix,
                                      String localName, String arrayType, String itemTag,
                                      List<String> values) {
        Element el = element(doc, nsUri, prefix, localName);
        el.setAttributeNS(SoapNamespace.SOAP_ENC, SoapNamespace.PREFIX_SOAP_ENC + ":arrayType", arrayType);
        if (values != null) {
            for (String v : values) {
                el.appendChild(plainElement(doc, itemTag, v));
            }
        }
        parent.appendChild(el);
        return el;
    }

    /**
     * 序列化为字符串：无 XML 声明、无缩进。
     */
    public static String toXml(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new SoapException("XML 序列化失败", e);
        }
    }
}
