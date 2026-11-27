/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.soap;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.SoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** CPE → ACS 的 SOAP 报文解析（Inform、各 RPC 响应、Fault、TransferComplete） */
public final class SoapParser {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SoapParser.class);

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private SoapParser() {
    }

    /** 空报文（空 POST）返回 name=null 的消息 */
    public static SoapMessage parse(String xml) {
        SoapMessage msg = new SoapMessage();
        if (xml == null || xml.isBlank()) {
            return msg;
        }
        Document doc = readDoc(xml);
        Element envelope = doc.getDocumentElement();
        // 命名空间自适应：cwmp-1-0/1-1/1-2… 都能解析（原先只认硬编码 1-0 的设备才正常）
        String cwmpNs = detectCwmpNamespace(envelope);
        if (cwmpNs != null) {
            msg.setCwmpNamespace(cwmpNs);
            msg.setCwmpVersion(versionOfNamespace(cwmpNs));
        }
        Element header = firstChildIgnoreCase(envelope, "Header");
        Element body = firstChildIgnoreCase(envelope, "Body");
        if (header != null) {
            Element idEl = firstChild(header, "ID");
            if (idEl != null) {
                msg.setId(idEl.getTextContent().trim());
                if (msg.getCwmpNamespace() == null && isCwmpNs(idEl.getNamespaceURI())) {
                    msg.setCwmpNamespace(idEl.getNamespaceURI().trim());
                    msg.setCwmpVersion(versionOfNamespace(msg.getCwmpNamespace()));
                }
            }
            Element nmr = firstChild(header, "NoMoreRequests");
            if (nmr != null) {
                String v = nmr.getTextContent().trim();
                msg.setNoMoreRequests("1".equals(v) || "true".equalsIgnoreCase(v));
            }
            Element sto = firstChild(header, "SessionTimeout");
            if (sto != null) {
                try {
                    msg.setSessionTimeoutSeconds(Long.parseLong(sto.getTextContent().trim()));
                } catch (NumberFormatException ignore) {
                }
            }
            Element hold = firstChild(header, "HoldRequests");
            if (hold != null) {
                String v = hold.getTextContent().trim();
                msg.setHoldRequests("1".equals(v) || "true".equalsIgnoreCase(v));
            }
        }
        if (body == null) {
            return msg;
        }
        Element root = firstElementChild(body);
        if (root == null) {
            return msg;
        }
        if (msg.getCwmpNamespace() == null && isCwmpNs(root.getNamespaceURI())) {
            msg.setCwmpNamespace(root.getNamespaceURI().trim());
            msg.setCwmpVersion(versionOfNamespace(msg.getCwmpNamespace()));
        }
        String method = localName(root);
        msg.setName(method);
        switch (method) {
            case "Inform" -> parseInform(root, msg);
            case "GetParameterNamesResponse" -> parseGpn(root, msg);
            case "GetParameterValuesResponse" -> parseGpv(root, msg);
            case "SetParameterValuesResponse" -> {
                Element st = firstChild(root, "Status");
                msg.setStatus(st == null ? 0 : intOf(st.getTextContent().trim(), 0));
            }
            case "AddObjectResponse" -> {
                Element in = firstChild(root, "InstanceNumber");
                if (in != null) {
                    msg.setInstanceNumber(longOf(in.getTextContent().trim()));
                }
                Element st = firstChild(root, "Status");
                msg.setStatus(st == null ? 0 : intOf(st.getTextContent().trim(), 0));
            }
            case "DeleteObjectResponse" -> {
                Element st = firstChild(root, "Status");
                msg.setStatus(st == null ? 0 : intOf(st.getTextContent().trim(), 0));
            }
            case "GetRPCMethodsResponse" -> {
                Element list = firstChild(root, "MethodList");
                if (list != null) {
                    for (Node n : elementChildren(list)) {
                        msg.getMethods().add(n.getTextContent().trim());
                    }
                }
            }
            case "TransferComplete" -> parseTransferComplete(root, msg);
            case "AutonomousTransferComplete" -> {
                parseTransferComplete(root, msg);
            }
            case "Fault" -> parseFault(root, msg);
            case "DownloadResponse" -> {
                Element st = firstChild(root, "Status");
                msg.setStatus(st == null ? 0 : intOf(st.getTextContent().trim(), 0));
                msg.setStartTime(text(root, "StartTime"));
                msg.setCompleteTime(text(root, "CompleteTime"));
            }
            default -> {
            }
        }
        return msg;
    }

    private static void parseInform(Element root, SoapMessage msg) {
        Element deviceId = firstChild(root, "DeviceId");
        if (deviceId != null) {
            msg.setManufacturer(text(deviceId, "Manufacturer"));
            msg.setOui(text(deviceId, "OUI"));
            msg.setProductClass(text(deviceId, "ProductClass"));
            msg.setSerialNumber(text(deviceId, "SerialNumber"));
        }
        Element event = firstChild(root, "Event");
        if (event != null) {
            for (Element st : elementChildren(event)) {
                if ("EventStruct".equals(localName(st))) {
                    String code = text(st, "EventCode");
                    if (code != null && !code.isBlank()) {
                        msg.getEvents().add(code.trim());
                    }
                }
            }
        }
        String me = text(root, "MaxEnvelopes");
        if (me != null) {
            try {
                msg.setMaxEnvelopes(Long.parseLong(me.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        String rc = text(root, "RetryCount");
        if (rc != null) {
            try {
                msg.setRetryCount(Long.parseLong(rc.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        parseParameterList(root, msg.getParams());
    }

    private static void parseGpn(Element root, SoapMessage msg) {
        List<CpeParam> list = new ArrayList<>();
        parseParameterInfoList(root, list);
        msg.setResults(list);
    }

    private static void parseGpv(Element root, SoapMessage msg) {
        List<CpeParam> list = new ArrayList<>();
        parseParameterList(root, list);
        msg.setResults(list);
    }

    private static void parseTransferComplete(Element root, SoapMessage msg) {
        msg.setCommandKey(text(root, "CommandKey"));
        Element fault = firstChild(root, "FaultStruct");
        if (fault != null) {
            msg.setTcFaultCode(intOf(text(fault, "FaultCode"), 0));
            msg.setTcFaultString(text(fault, "FaultString"));
        }
        msg.setStartValue(text(root, "StartTime"));
        msg.setEndValue(text(root, "CompleteTime"));
    }

    private static void parseFault(Element root, SoapMessage msg) {
        msg.setFault(true);
        // faultcode/faultstring/detail 大小写不敏感（SOAP 1.1 规范小写，部分设备发 <FaultCode> 大写形态）
        String code = textIgnoreCase(root, "faultcode");
        String str = textIgnoreCase(root, "faultstring");
        msg.setFaultString(str == null ? code : str);
        Element detail = firstChildIgnoreCase(root, "detail");
        Element cwmpFault = detail == null ? null : firstChildIgnoreCase(detail, "Fault");
        if (cwmpFault != null) {
            msg.setFaultCode(intOf(text(cwmpFault, "FaultCode"), 0));
            String s = text(cwmpFault, "FaultString");
            if (s != null) {
                msg.setFaultString(s);
            }
            // 遍历所有 SetParameterValuesFault（部分失败可能不止一条，原先只取第一条）
            for (Element child : elementChildren(cwmpFault)) {
                if (!"SetParameterValuesFault".equals(localName(child))) {
                    continue;
                }
                if (firstChild(child, "ParameterName") != null) {
                    msg.getSetFaults().add(new SoapMessage.SetFault(
                            text(child, "ParameterName"),
                            intOf(text(child, "FaultCode"), 0),
                            text(child, "FaultString")));
                } else {
                    for (Element st : elementChildren(child)) {
                        if ("ParameterErrorResponse".equals(localName(st))) {
                            msg.getSetFaults().add(new SoapMessage.SetFault(
                                    text(st, "ParameterName"),
                                    intOf(text(st, "FaultCode"), 0),
                                    text(st, "FaultString")));
                        }
                    }
                }
            }
        }
    }

    /** 解析 ParameterValueStruct */
    private static void parseParameterList(Element parent, List<CpeParam> out) {
        Element list = firstChild(parent, "ParameterList");
        if (list == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Element st : elementChildren(list)) {
            if (!"ParameterValueStruct".equals(localName(st))) {
                continue;
            }
            Element name = firstChild(st, "Name");
            Element value = firstChild(st, "Value");
            if (name == null) {
                continue;
            }
            String path = name.getTextContent().trim();
            String type = "string";
            String val = null;
            if (value != null) {
                String rawType = value.getAttributeNS(XSI_NS, "type");
                if (rawType != null && rawType.contains(":")) {
                    type = rawType.substring(rawType.indexOf(':') + 1);
                } else if (rawType != null && !rawType.isBlank()) {
                    type = rawType;
                }
                String content = value.getTextContent();
                val = content == null ? "" : content.trim();
            }
            boolean object = path.endsWith(".");
            out.add(new CpeParam(path, val, type, object, false, now));
        }
    }

    /** 解析 GPN 响应 ParameterInfoStruct */
    private static void parseParameterInfoList(Element parent, List<CpeParam> out) {
        Element list = firstChild(parent, "ParameterList");
        if (list == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Element st : elementChildren(list)) {
            if (!"ParameterInfoStruct".equals(localName(st))) {
                continue;
            }
            Element name = firstChild(st, "Name");
            if (name == null) {
                continue;
            }
            String path = name.getTextContent().trim();
            boolean writable = "1".equals(text(st, "Writable"));
            boolean object = path.endsWith(".");
            out.add(new CpeParam(path, null, null, object, writable, now));
        }
    }

    private static Document readDoc(String xml) {
        try {
            return parseDoc(xml);
        } catch (Exception first) {
            // 部分设备在参数值里夹带 XML 1.0 非法控制字符（如 0x1），导致整个 Inform 解析失败；
            // 剥离非法字符后重试一次，避免一颗坏参数毁掉整个会话
            String cleaned = stripInvalidXmlChars(xml);
            if (cleaned.length() != xml.length()) {
                LOG.info("报文含非法 XML 控制字符，已剥离 {} 个后重试解析", xml.length() - cleaned.length());
                try {
                    return parseDoc(cleaned);
                } catch (Exception second) {
                    throw new SoapException("SOAP 报文解析失败: " + second.getMessage(), second);
                }
            }
            throw new SoapException("SOAP 报文解析失败: " + first.getMessage(), first);
        }
    }

    private static Document parseDoc(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
        // 不设 ErrorHandler 时解析器会把 [Fatal Error] 直接打到 stderr，改成显式抛出由上层统一处理
        db.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException e) {
                // 警告忽略
            }

            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return db.parse(new InputSource(new StringReader(xml)));
    }

    /** 剥离 XML 1.0 不允许的控制字符（保留 TAB/LF/CR，不影响合法报文） */
    private static String stripInvalidXmlChars(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c >= 0x20 || c == 0x9 || c == 0xA || c == 0xD) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 按本地名找第一个子元素（不区分命名空间） */
    private static Element firstChild(Element parent, String localName) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && localName(n).equals(localName)) {
                return e;
            }
        }
        return null;
    }

    /** 按本地名找第一个子元素（不区分命名空间、大小写不敏感） */
    private static Element firstChildIgnoreCase(Element parent, String localName) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && localName(n).equalsIgnoreCase(localName)) {
                return e;
            }
        }
        return null;
    }

    private static String textIgnoreCase(Element parent, String childLocalName) {
        Element c = firstChildIgnoreCase(parent, childLocalName);
        return c == null ? null : c.getTextContent().trim();
    }

    /** CWMP 命名空间判定：urn:dslforum-org:cwmp-1-N */
    private static boolean isCwmpNs(String uri) {
        return uri != null && uri.trim().startsWith("urn:dslforum-org:cwmp-1-");
    }

    /** 从 Envelope 元素检测 CWMP 命名空间：先看元素自身，再扫属性值（部分设备把命名空间写在非标准位置） */
    private static String detectCwmpNamespace(Element envelope) {
        if (envelope == null) {
            return null;
        }
        if (isCwmpNs(envelope.getNamespaceURI())) {
            return envelope.getNamespaceURI().trim();
        }
        org.w3c.dom.NamedNodeMap attrs = envelope.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                String val = attrs.item(i).getNodeValue();
                if (isCwmpNs(val)) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    /** cwmp-1-N → 1.N */
    private static String versionOfNamespace(String ns) {
        if (ns == null) {
            return null;
        }
        int idx = ns.lastIndexOf("cwmp-1-");
        if (idx < 0) {
            return null;
        }
        return "1." + ns.substring(idx + "cwmp-1-".length()).trim();
    }

    private static Element firstElementChild(Element parent) {
        for (Element e : elementChildren(parent)) {
            return e;
        }
        return null;
    }

    private static List<Element> elementChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private static String localName(Node n) {
        String ln = n.getLocalName();
        return ln == null ? n.getNodeName() : ln;
    }

    private static String text(Element parent, String childLocalName) {
        Element c = firstChild(parent, childLocalName);
        return c == null ? null : c.getTextContent().trim();
    }

    private static int intOf(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class SoapException extends RuntimeException {
        public SoapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
