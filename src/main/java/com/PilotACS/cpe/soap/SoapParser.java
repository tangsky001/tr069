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
        Element header = firstChild(envelope, "Header");
        Element body = firstChild(envelope, "Body");
        if (header != null) {
            Element idEl = firstChild(header, "ID");
            if (idEl != null) {
                msg.setId(idEl.getTextContent().trim());
            }
            // TR-069 A.3.2 扩展头：设备声明不再发后续请求 / 会话超时（按局部名匹配，不挑前缀）
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
                    // 未声明就保持 0（用 ACS 默认会话超时）
                }
            }
        }
        if (body == null) {
            return msg;
        }
        Element root = firstElementChild(body);
        if (root == null) {
            return msg;
        }
        String method = localName(root);
        msg.setName(method);
        switch (method) {
            case "Inform" -> parseInform(root, msg);
            case "GetParameterNamesResponse" -> parseGpn(root, msg);
            case "GetParameterValuesResponse" -> parseGpv(root, msg);
            case "SetParameterValuesResponse" -> {
                Element st = firstChild(root, "Status");
                // 畸形数值不能抛裸 NFE：异常穿透会掐断连接不给响应，设备重传 → 死循环
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
            case "GetRPCMethodsResponse" -> {
                Element list = firstChild(root, "MethodList");
                if (list != null) {
                    for (Node n : elementChildren(list)) {
                        msg.getMethods().add(n.getTextContent().trim());
                    }
                }
            }
            case "TransferComplete" -> parseTransferComplete(root, msg);
            case "Fault" -> parseFault(root, msg);
            case "DownloadResponse" -> {
                // 设备回执：Status 0/1（1=进行中，最终成败等 7 TRANSFER COMPLETE）+ 起止时间
                Element st = firstChild(root, "Status");
                msg.setStatus(st == null ? 0 : intOf(st.getTextContent().trim(), 0));
                msg.setStartTime(text(root, "StartTime"));
                msg.setCompleteTime(text(root, "CompleteTime"));
            }
            default -> {
                // Reboot/FactoryReset 等空体响应
            }
        }
        return msg;
    }

    // ---------------- 各报文解析 ----------------

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
                // 保持默认 1
            }
        }
        String rc = text(root, "RetryCount");
        if (rc != null) {
            try {
                msg.setRetryCount(Long.parseLong(rc.trim()));
            } catch (NumberFormatException ignore) {
                // 保持默认 0
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
        String code = text(root, "faultcode");
        String str = text(root, "faultstring");
        msg.setFaultString(str == null ? code : str);
        Element detail = firstChild(root, "detail");
        Element cwmpFault = detail == null ? null : firstChild(detail, "Fault");
        if (cwmpFault != null) {
            msg.setFaultCode(intOf(text(cwmpFault, "FaultCode"), 0));
            String s = text(cwmpFault, "FaultString");
            if (s != null) {
                msg.setFaultString(s);
            }
            Element spvFaults = firstChild(cwmpFault, "SetParameterValuesFault");
            if (spvFaults != null) {
                if (firstChild(spvFaults, "ParameterName") != null) {
                    // TR-069 标准形态：SetParameterValuesFault 本身就是单结构体（抓包样例口径）
                    msg.getSetFaults().add(new SoapMessage.SetFault(
                            text(spvFaults, "ParameterName"),
                            intOf(text(spvFaults, "FaultCode"), 0),
                            text(spvFaults, "FaultString")));
                } else {
                    // 兼容个别设备的容器形态：SetParameterValuesFault 下挂多个 ParameterErrorResponse
                    for (Element st : elementChildren(spvFaults)) {
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

    // ---------------- 公共解析 ----------------

    /** ParameterValueStruct：Name + Value(xsi:type) */
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

    /** ParameterInfoStruct：GPN 响应，Name + Writable；对象节点以 . 结尾 */
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

    // ---------------- DOM 工具 ----------------

    private static Document readDoc(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setExpandEntityReferences(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new SoapException("SOAP 报文解析失败: " + e.getMessage(), e);
        }
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
