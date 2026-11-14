package com.tr069.acs.cwmp;

import com.tr069.acs.common.exception.SoapException;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CWMP 报文解析器 */
@Slf4j
public final class SoapParser {

    private SoapParser() {
    }

    /** 解析 CPE 上报或响应的 SOAP 报文 */
    public static SoapMessage parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new SoapException("SOAP 报文为空");
        }
        Document doc = toDocument(xml);
        String cwmpId = extractCwmpId(doc);
        Element body = firstChildElement(doc.getDocumentElement(), "Body");
        if (body == null) {
            throw new SoapException("SOAP 报文缺少 Body");
        }
        Element root = firstElement(body);
        if (root == null) {
            throw new SoapException("SOAP Body 为空");
        }
        String localName = localName(root);

        if ("Fault".equals(localName)) {
            FaultStruct fault = parseFault(root);
            Map<String, Object> dict = new LinkedHashMap<>();
            dict.put("FaultCode", fault.getFaultCode());
            dict.put("FaultString", fault.getFaultString());
            List<Map<String, Object>> failures = new ArrayList<>();
            if (fault.getSetParameterValuesFaultList() != null) {
                for (FaultStruct.SetParameterValuesFault f : fault.getSetParameterValuesFaultList()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ParameterName", f.getParameterName());
                    m.put("FaultCode", f.getFaultCode());
                    m.put("FaultString", f.getFaultString());
                    failures.add(m);
                }
                dict.put("SetParameterValuesFaultList", failures);
            }
            return SoapMessage.builder()
                    .cwmpId(cwmpId)
                    .methodName(localName)
                    .fault(fault)
                    .body(dict)
                    .strResult(buildFaultStrResult(fault, failures))
                    .build();
        }

        RpcMethod method = RpcMethod.of(localName);
        SoapMessage.SoapMessageBuilder builder = SoapMessage.builder()
                .cwmpId(cwmpId)
                .method(method)
                .methodName(localName)
                .body(convertResult(root, localName))
                .strResult(buildStrResult(root, localName));
        return builder.build();
    }

    /** 解析 CPE 主动上报的 Inform */
    public static InformRequest parseInform(String xml) {
        Document doc = toDocument(xml);
        String cwmpVersion = extractCwmpVersion(doc);
        Element body = firstChildElement(doc.getDocumentElement(), "Body");
        if (body == null) {
            throw new SoapException("Inform 报文缺少 Body");
        }
        Element root = firstElement(body);
        if (root == null || !"Inform".equals(localName(root))) {
            throw new SoapException("报文不是 Inform");
        }
        InformRequest req = toInformRequest(root);
        req.setCwmpVersion(cwmpVersion);
        return req;
    }

    /** 判断报文是否为空 POST（CPE 无内容上报） */
    public static boolean isEmptyPost(String xml) {
        return xml == null || xml.isBlank();
    }

    // ==================== DOM 解析 ====================

    private static Document toDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SoapException("SOAP 报文解析失败", e);
        }
    }

    private static String extractCwmpId(Document doc) {
        Element header = firstChildElement(doc.getDocumentElement(), "Header");
        if (header == null) {
            return "";
        }
        Element id = firstChildElement(header, "ID");
        return id == null ? "" : text(id);
    }

    /**
     * 从 SOAP Envelope 的命名空间声明里提取设备声明的 CWMP 版本号。
     * <p>设备 Inform 报文里 Envelope 会带 {@code xmlns:cwmp="urn:dslforum-org:cwmp-1-X"}，
     * X 就是设备支持的 CWMP 版本（0~5）。取不到或格式不符则返回默认 {@code cwmp-1-0}，
     * 保证向下兼容——老设备只认 cwmp-1-0 命名空间，ACS 回同样的版本不会出问题。
     */
    private static String extractCwmpVersion(Document doc) {
        Element envelope = doc.getDocumentElement();
        // 遍历 Envelope 上的所有 xmlns:* 声明
        org.w3c.dom.NamedNodeMap attrs = envelope.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String nsUri = attr.getNamespaceURI();
            if (nsUri != null && nsUri.equals(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI)) {
                String prefix = attr.getLocalName();
                String value = attr.getNodeValue();
                if ("cwmp".equals(prefix) && value != null && value.startsWith(SoapNamespace.CWMP_PREFIX)) {
                    String version = value.substring(SoapNamespace.CWMP_PREFIX.length());
                    // 校验格式：cwmp-1-X（X=0~5，覆盖 TR-069 Amendment 1~6 全部版本）
                    if (version.matches("cwmp-1-[0-5]")) {
                        return version;
                    }
                }
            }
        }
        return SoapEnvelopeBuilder.DEFAULT_CWMP_VERSION;
    }

    private static InformRequest toInformRequest(Element root) {
        InformRequest.InformRequestBuilder builder = InformRequest.builder();

        Element deviceId = firstChildElement(root, "DeviceId");
        if (deviceId != null) {
            builder.deviceId(com.tr069.acs.cwmp.model.DeviceIdStruct.builder()
                    .manufacturer(textOfChild(deviceId, "Manufacturer"))
                    .oui(textOfChild(deviceId, "OUI"))
                    .productClass(textOfChild(deviceId, "ProductClass"))
                    .serialNumber(textOfChild(deviceId, "SerialNumber"))
                    .build());
        }

        Element eventEl = firstChildElement(root, "Event");
        List<EventStruct> events = new ArrayList<>();
        if (eventEl != null) {
            for (Element e : childElements(eventEl, "EventStruct")) {
                events.add(EventStruct.builder()
                        .eventCode(textOfChild(e, "EventCode"))
                        .commandKey(textOfChild(e, "CommandKey"))
                        .build());
            }
        }
        builder.eventList(events);

        builder.maxEnvelopes(textOfChild(root, "MaxEnvelopes"))
                .currentTime(textOfChild(root, "CurrentTime"))
                .retryCount(textOfChild(root, "RetryCount"));

        List<ParameterValueStruct> parameters = parseParameterValueList(root);
        builder.parameterList(parameters);
        return builder.build();
    }

    /** 对应 get_parameter_values response 的 ParameterList 解析 */
    private static List<ParameterValueStruct> parseParameterValueList(Element parent) {
        List<ParameterValueStruct> result = new ArrayList<>();
        Element listEl = firstChildElement(parent, "ParameterList");
        if (listEl == null) {
            return result;
        }
        for (Element item : childElements(listEl, "ParameterValueStruct")) {
            Element valueEl = firstChildElement(item, "Value");
            String valueType = null;
            if (valueEl != null) {
                String type = valueEl.getAttributeNS(SoapNamespace.XSI, "type");
                if (type != null && !type.isEmpty()) {
                    valueType = XmlUtils.canonicalType(type);
                }
            }
            result.add(ParameterValueStruct.builder()
                    .name(textOfChild(item, "Name"))
                    .value(valueEl == null ? "" : text(valueEl))
                    .valueType(valueType)
                    .build());
        }
        return result;
    }

    /** 对应 convert_result：把响应转换为 dict_data 结构 */
    private static Map<String, Object> convertResult(Element root, String methodName) {
        Map<String, Object> dict = new LinkedHashMap<>();

        switch (methodName) {
            case "GetRPCMethodsResponse" -> dict.put("MethodList", listOfSimpleValues(root, "MethodList"));
            case "GetParameterValuesResponse" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (ParameterValueStruct p : parseParameterValueList(root)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("Name", p.getName());
                    m.put("Value", p.getValue());
                    m.put("Value_type", p.getValueType());
                    entries.add(m);
                }
                dict.put("ParameterList", entries);
            }
            case "GetParameterNamesResponse" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                Element listEl = firstChildElement(root, "ParameterList");
                if (listEl != null) {
                    for (Element item : childElements(listEl, "ParameterInfoStruct")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("Name", textOfChild(item, "Name"));
                        m.put("Writable", textOfChild(item, "Writable"));
                        entries.add(m);
                    }
                }
                dict.put("ParameterList", entries);
            }
            case "SetParameterValuesResponse" -> dict.put("Status", textOfChild(root, "Status"));
            case "SetParameterAttributesResponse" -> {
                // 响应体无有效字段，保持空结构
            }
            case "GetParameterAttributesResponse" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                Element listEl = firstChildElement(root, "ParameterList");
                if (listEl != null) {
                    for (Element item : childElements(listEl, "ParameterAttributeStruct")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("Name", textOfChild(item, "Name"));
                        m.put("Notification", textOfChild(item, "Notification"));
                        m.put("AccessList", textOfChild(item, "AccessList"));
                        entries.add(m);
                    }
                }
                dict.put("ParameterList", entries);
            }
            case "AddObjectResponse" -> {
                dict.put("InstanceNumber", textOfChild(root, "InstanceNumber"));
                dict.put("Status", textOfChild(root, "Status"));
            }
            case "DeleteObjectResponse" -> dict.put("Status", textOfChild(root, "Status"));
            case "RebootResponse", "FactoryResetResponse", "ScheduleInformResponse",
                    "ScheduleDownloadResponse", "SetVouchersResponse", "CancelTransferResponse",
                    "ChangeDUStateResponse" -> {
                // 响应体无有效字段，保持空结构
            }
            case "DownloadResponse", "UploadResponse" -> {
                dict.put("Status", textOfChild(root, "Status"));
                dict.put("StartTime", textOfChild(root, "StartTime"));
                dict.put("CompleteTime", textOfChild(root, "CompleteTime"));
            }
            case "GetQueuedTransfersResponse" -> dict.put("TransferList",
                    transferList(root, false));
            case "GetAllQueuedTransfersResponse" -> dict.put("TransferList",
                    transferList(root, true));
            case "GetOptionsResponse" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                Element listEl = firstChildElement(root, "OptionList");
                if (listEl != null) {
                    for (Element item : childElements(listEl, "OptionStruct")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("OptionName", textOfChild(item, "OptionName"));
                        m.put("VoucherSN", textOfChild(item, "VoucherSN"));
                        m.put("State", textOfChild(item, "State"));
                        m.put("Mode", textOfChild(item, "Mode"));
                        m.put("StartDate", textOfChild(item, "StartDate"));
                        m.put("ExpirationDate", textOfChild(item, "ExpirationDate"));
                        m.put("IsTransferable", textOfChild(item, "IsTransferable"));
                        entries.add(m);
                    }
                }
                dict.put("OptionList", entries);
            }
            case "GetParameterValuesXMLResponse" ->
                    dict.put("XmlContent", textOfChild(root, "Content"));
            default -> {
                // Inform 等 CPE 主动请求由专门逻辑处理
            }
        }
        // CPE 主动请求（TransferComplete / RequestDownload / Kicked 等）拍平简单子节点，
        // 供控制器取 CommandKey / FaultCode / FileType 等字段
        RpcMethod reqMethod = RpcMethod.of(methodName);
        if (reqMethod != null && reqMethod.isRequestFromCpe()) {
            flattenSimpleChildren(root, dict);
        }
        return dict;
    }

    /** 把无子元素的子节点拍平进字典 */
    private static void flattenSimpleChildren(Element root, Map<String, Object> dict) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element e)) {
                continue;
            }
            if (e.getChildNodes().getLength() > 0) {
                boolean hasElement = false;
                NodeList inner = e.getChildNodes();
                for (int j = 0; j < inner.getLength(); j++) {
                    if (inner.item(j) instanceof Element) {
                        hasElement = true;
                        break;
                    }
                }
                if (hasElement) {
                    continue;
                }
            }
            String key = localName(e);
            if (!dict.containsKey(key)) {
                dict.put(key, text(e));
            }
        }
    }

    private static List<Map<String, Object>> transferList(Element root, boolean all) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Element listEl = firstChildElement(root, "TransferList");
        if (listEl == null) {
            return entries;
        }
        for (Element item : childElements(listEl, "QueuedTransferStruct")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("CommandKey", textOfChild(item, "CommandKey"));
            m.put("State", textOfChild(item, "State"));
            if (all) {
                m.put("IsDownload", textOfChild(item, "IsDownload"));
                m.put("FileType", textOfChild(item, "FileType"));
                m.put("FileSize", textOfChild(item, "FileSize"));
                m.put("TargetFileName", textOfChild(item, "TargetFileName"));
            }
            entries.add(m);
        }
        return entries;
    }

    private static FaultStruct parseFault(Element root) {
        Element detail = firstChildElement(root, "detail");
        FaultStruct.FaultStructBuilder builder = FaultStruct.builder()
                .soapFaultCode(textOfChild(root, "faultcode"))
                .faultString(textOfChild(root, "faultstring"));
        List<FaultStruct.SetParameterValuesFault> failures = new ArrayList<>();
        if (detail != null) {
            Element cwmpFault = firstChildElement(detail, "Fault");
            if (cwmpFault != null) {
                builder.faultCode(textOfChild(cwmpFault, "FaultCode"));
                builder.faultString(textOfChild(cwmpFault, "FaultString"));
                Element faultList = firstChildElement(cwmpFault, "SetParameterValuesFaultList");
                if (faultList != null) {
                    for (Element item : childElements(faultList, "SetParameterValuesFaultStruct")) {
                        failures.add(FaultStruct.SetParameterValuesFault.builder()
                                .parameterName(textOfChild(item, "ParameterName"))
                                .faultCode(textOfChild(item, "FaultCode"))
                                .faultString(textOfChild(item, "FaultString"))
                                .build());
                    }
                }
            }
        }
        builder.setParameterValuesFaultList(failures);
        return builder.build();
    }

    /** 生成可读的结果描述，用于日志与结果展示 */
    private static String buildStrResult(Element root, String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n*****").append(methodName).append(" Succeed!******\n");
        sb.append("-----The Result:\n");
        for (ParameterValueStruct p : parseParameterValueList(root)) {
            sb.append("Name = ").append(p.getName())
                    .append("; Value = ").append(p.getValue())
                    .append("; Value_Type = ").append(p.getValueType())
                    .append("\n");
        }
        if ("GetParameterValuesXMLResponse".equals(methodName)) {
            String xml = textOfChild(root, "Content");
            if (!xml.isEmpty()) {
                sb.append("XmlContent = ").append(xml).append("\n");
            }
        }
        return sb.toString();
    }

    private static String buildFaultStrResult(FaultStruct fault,
                                              List<Map<String, Object>> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("FaultCode = ").append(fault.getFaultCode())
                .append("; FaultString = ").append(fault.getFaultString()).append("\n");
        for (Map<String, Object> f : failures) {
            sb.append("ParameterName = ").append(f.get("ParameterName"))
                    .append("; FaultCode = ").append(f.get("FaultCode"))
                    .append("; FaultString = ").append(f.get("FaultString"))
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== DOM 工具 ====================

    private static List<String> listOfSimpleValues(Element parent, String localName) {
        List<String> result = new ArrayList<>();
        Element listEl = firstChildElement(parent, localName);
        if (listEl == null) {
            return result;
        }
        NodeList children = listEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e) {
                result.add(text(e));
            }
        }
        return result;
    }

    private static String localName(Element el) {
        String local = el.getLocalName();
        return local == null ? el.getNodeName() : local;
    }

    private static Element firstElement(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && localName.equals(localName(e))) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && name.equals(localName(e))) {
                result.add(e);
            }
        }
        return result;
    }

    private static String textOfChild(Element parent, String localName) {
        Element child = firstChildElement(parent, localName);
        return child == null ? "" : text(child);
    }

    private static String text(Element el) {
        String v = el.getTextContent();
        return v == null ? "" : v.trim();
    }
}
