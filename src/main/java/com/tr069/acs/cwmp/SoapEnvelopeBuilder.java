package com.tr069.acs.cwmp;

import com.tr069.acs.common.exception.SoapException;
import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CWMP SOAP 报文构造器。
 *
 * <p>生成的 XML 遵循 CWMP 要求的子元素顺序、xsi:nil 判定与 arrayType 完全限定名写法。</p>
 */
@Slf4j
public final class SoapEnvelopeBuilder {

    private SoapEnvelopeBuilder() {
    }

    public static final String DEFAULT_CWMP_VERSION = "cwmp-1-0";

    /**
     * InformResponse 的默认 MaxEnvelopes：一个 HTTP 事务只携带一条 SOAP 消息。
     */
    private static final int DEFAULT_MAX_ENVELOPES = 1;

    // ==================== 对外入口 ====================

    public static String build(RpcMethod method, Map<String, Object> args) {
        return build(method, args, null, DEFAULT_CWMP_VERSION);
    }

    public static String build(RpcMethod method, Map<String, Object> args, String cwmpId) {
        return build(method, args, cwmpId, DEFAULT_CWMP_VERSION);
    }

    /**
     * 构造完整 SOAP 报文。
     *
     * @param method       CWMP 方法
     * @param args         方法参数，键为 SOAP 元素名
     * @param cwmpId       会话 ID，为空时随机生成 1~1000000
     * @param cwmpVersion  CWMP 命名空间版本，如 cwmp-1-0
     * @return 序列化后的 SOAP 报文
     */
    public static String build(RpcMethod method, Map<String, Object> args,
                               String cwmpId, String cwmpVersion) {
        if (method == null) {
            throw new SoapException("CWMP 方法不能为空");
        }
        Document doc = XmlUtils.newDocument();
        String cwmpNs = SoapNamespace.cwmp(cwmpVersion);

        Element envelope = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Envelope");
        // 命名空间声明按字典序输出，保证同一份报文每次生成都一致
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_SOAP_ENC, SoapNamespace.SOAP_ENC);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_SOAP_ENV, SoapNamespace.SOAP_ENV);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_CWMP, cwmpNs);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_XSD, SoapNamespace.XSD);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_XSI, SoapNamespace.XSI);
        doc.appendChild(envelope);

        // Header：cwmp:ID 与 cwmp:HoldRequests，均带 mustUnderstand=1
        Element header = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Header");
        envelope.appendChild(header);

        Element id = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "ID");
        id.setAttributeNS(SoapNamespace.SOAP_ENV, SoapNamespace.PREFIX_SOAP_ENV + ":mustUnderstand", "1");
        id.setTextContent(cwmpId == null || cwmpId.isEmpty() ? XmlUtils.randomCwmpId() : cwmpId);
        header.appendChild(id);

        Element holdRequests = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "HoldRequests");
        holdRequests.setAttributeNS(SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV + ":mustUnderstand", "1");
        holdRequests.setTextContent("0");
        header.appendChild(holdRequests);

        // Body
        Element body = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Body");
        envelope.appendChild(body);

        Map<String, Object> safeArgs = args == null ? Collections.emptyMap() : args;

        if (!buildBody(doc, body, cwmpNs, method, safeArgs)) {
            throw new SoapException("不支持的 CWMP 方法: " + method.getValue());
        }

        String soap = XmlUtils.toXml(doc);
        // 临时关闭：完整 SOAP 报文太长，采集/下发一轮就刷屏，掩盖了真正要看的日志。
        // 需要排查报文内容时再打开，或改用配置开关：
        // logging.level.com.tr069.acs.cwmp.SoapEnvelopeBuilder: debug
        // if (log.isDebugEnabled()) {
        //     log.debug("构造 CWMP 报文 [{}]: {}", method.getValue(), soap);
        // }
        return soap;
    }

    /**
     * 构造 InformResponse。
     */
    public static String buildInformResponse(String cwmpId, String cwmpVersion) {
        return build(RpcMethod.INFORM_RESPONSE,
                Map.of("MaxEnvelopes", String.valueOf(DEFAULT_MAX_ENVELOPES)),
                cwmpId, cwmpVersion);
    }

    /**
     * 构造 SOAP Fault 响应。
     */
    public static String buildFault(FaultStruct fault, String cwmpId, String cwmpVersion) {
        Document doc = XmlUtils.newDocument();
        String cwmpNs = SoapNamespace.cwmp(cwmpVersion);

        Element envelope = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Envelope");
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_SOAP_ENC, SoapNamespace.SOAP_ENC);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_SOAP_ENV, SoapNamespace.SOAP_ENV);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_CWMP, cwmpNs);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_XSD, SoapNamespace.XSD);
        envelope.setAttributeNS(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                "xmlns:" + SoapNamespace.PREFIX_XSI, SoapNamespace.XSI);
        doc.appendChild(envelope);

        Element header = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Header");
        envelope.appendChild(header);
        Element id = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "ID");
        id.setAttributeNS(SoapNamespace.SOAP_ENV, SoapNamespace.PREFIX_SOAP_ENV + ":mustUnderstand", "1");
        id.setTextContent(cwmpId == null || cwmpId.isEmpty() ? XmlUtils.randomCwmpId() : cwmpId);
        header.appendChild(id);
        Element holdRequests = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "HoldRequests");
        holdRequests.setAttributeNS(SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV + ":mustUnderstand", "1");
        holdRequests.setTextContent("0");
        header.appendChild(holdRequests);

        Element body = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Body");
        envelope.appendChild(body);

        buildFaultBody(doc, body, cwmpNs, fault);

        return XmlUtils.toXml(doc);
    }

    // ==================== Body 构造 ====================

    private static boolean buildBody(Document doc, Element body, String cwmpNs,
                                     RpcMethod method, Map<String, Object> args) {
        switch (method) {
            // ---------- 无参数结构 ----------
            case GET_RPC_METHODS:
            case FACTORY_RESET:
            case GET_QUEUED_TRANSFERS:
            case GET_ALL_QUEUED_TRANSFERS:
            case TRANSFER_COMPLETE_RESPONSE:
            case REQUEST_DOWNLOAD_RESPONSE:
            case DU_STATE_CHANGE_COMPLETE_RESPONSE:
            case AUTONOMOUS_TRANSFER_COMPLETE_RESPONSE:
            case AUTONOMOUS_DU_STATE_CHANGE_COMPLETE_RESPONSE:
                appendCwmpRoot(doc, body, cwmpNs, method.getValue());
                return true;

            case SET_PARAMETER_VALUES:
                buildSetParameterValues(doc, body, cwmpNs, args);
                return true;
            case GET_PARAMETER_VALUES:
            case GET_PARAMETER_ATTRIBUTES:
                buildParameterNames(doc, body, cwmpNs, method.getValue(), args);
                return true;
            case GET_PARAMETER_NAMES:
                buildGetParameterNames(doc, body, cwmpNs, args);
                return true;
            case SET_PARAMETER_ATTRIBUTES:
                buildSetParameterAttributes(doc, body, cwmpNs, args);
                return true;
            case ADD_OBJECT:
            case DELETE_OBJECT:
                buildObjectWithKey(doc, body, cwmpNs, method.getValue(), args);
                return true;
            case DOWNLOAD:
                buildDownload(doc, body, cwmpNs, args);
                return true;
            case UPLOAD:
                buildUpload(doc, body, cwmpNs, args);
                return true;
            case REBOOT:
            case CANCEL_TRANSFER:
                buildCommandKeyOnly(doc, body, cwmpNs, method.getValue(), args);
                return true;
            case SCHEDULE_INFORM:
                buildScheduleInform(doc, body, cwmpNs, args);
                return true;
            case SCHEDULE_DOWNLOAD:
                buildScheduleDownload(doc, body, cwmpNs, args);
                return true;
            case CHANGE_DU_STATE:
                buildChangeDuState(doc, body, cwmpNs, args);
                return true;
            case SET_VOUCHERS:
                buildSetVouchers(doc, body, cwmpNs, args);
                return true;
            case GET_OPTIONS:
                buildGetOptions(doc, body, cwmpNs, args);
                return true;

            // ---------- 响应 ----------
            case INFORM_RESPONSE:
                buildInformResponseBody(doc, body, cwmpNs, args);
                return true;
            case GET_RPC_METHODS_RESPONSE:
                buildGetRpcMethodsResponse(doc, body, cwmpNs, args);
                return true;
            case KICKED_RESPONSE:
                buildKickedResponse(doc, body, cwmpNs, args);
                return true;

            default:
                return false;
        }
    }

    private static Element appendCwmpRoot(Document doc, Element body, String cwmpNs, String name) {
        Element root = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, name);
        body.appendChild(root);
        return root;
    }

    // -------------------------------------------------- 个方法实现

    /**
     * SetParameterValues：
     * <pre>
     * ParameterList [SOAP-ENC:arrayType="cwmp:ParameterValueStruct[N]"]
     *   ParameterValueStruct: Name(xsi:type=xsd:string), Value(xsi:type=xsd:&lt;Value_type&gt;)
     * ParameterKey (xsi:type="xsd:string")
     * </pre>
     */
    private static void buildSetParameterValues(Document doc, Element body, String cwmpNs,
                                                Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.SET_PARAMETER_VALUES.getValue());

        List<ParameterValueStruct> parameters = toParameterValueList(args.get("ParameterList"));
        Element list = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterList");
        list.setAttributeNS(SoapNamespace.SOAP_ENC, SoapNamespace.PREFIX_SOAP_ENC + ":arrayType",
                "cwmp:ParameterValueStruct[" + parameters.size() + "]");
        for (ParameterValueStruct p : parameters) {
            Element item = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterValueStruct");
            XmlUtils.appendTyped(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Name",
                    p.getName(), "string");
            XmlUtils.appendTyped(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Value",
                    p.getValue(), valueType(p));
            list.appendChild(item);
        }
        root.appendChild(list);

        XmlUtils.appendTyped(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterKey",
                str(args, "ParameterKey"), "string");
    }

    private static String valueType(ParameterValueStruct p) {
        return p.getValueType() == null || p.getValueType().isEmpty() ? "string" : p.getValueType();
    }

    /**
     * GetParameterValues / GetParameterAttributes 共用：ParameterNames 字符串数组。
     */
    private static void buildParameterNames(Document doc, Element body, String cwmpNs,
                                            String name, Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, name);
        List<String> names = strList(args.get("ParameterNames"));
        XmlUtils.appendArray(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterNames",
                "xsd:string[" + names.size() + "]", "string", names);
    }

    /**
     * GetParameterNames：ParameterPath 与 NextLevel，均不带 xsi:type。
     */
    private static void buildGetParameterNames(Document doc, Element body, String cwmpNs,
                                               Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.GET_PARAMETER_NAMES.getValue());
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterPath",
                str(args, "ParameterPath"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "NextLevel",
                str(args, "NextLevel"));
    }

    /**
     * SetParameterAttributes：
     * <pre>
     * ParameterList [arrayType="cwmp:SetParameterAttributesStruct[N]"]
     *   SetParameterAttributesStruct:
     *     Name, NotificationChange, Notification, AccessListChange,
     *     AccessList (长度为 0 时 nil，否则含若干 string 且不带 arrayType)
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private static void buildSetParameterAttributes(Document doc, Element body, String cwmpNs,
                                                    Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.SET_PARAMETER_ATTRIBUTES.getValue());

        Object raw = args.get("ParameterList");
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    items.add((Map<String, Object>) m);
                }
            }
        }

        Element listEl = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterList");
        listEl.setAttributeNS(SoapNamespace.SOAP_ENC, SoapNamespace.PREFIX_SOAP_ENC + ":arrayType",
                "cwmp:SetParameterAttributesStruct[" + items.size() + "]");
        for (Map<String, Object> item : items) {
            Element s = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP,
                    "SetParameterAttributesStruct");
            XmlUtils.appendText(doc, s, cwmpNs, SoapNamespace.PREFIX_CWMP, "Name", strAny(item, "Name", "name"));
            XmlUtils.appendText(doc, s, cwmpNs, SoapNamespace.PREFIX_CWMP, "NotificationChange",
                    strAny(item, "NotificationChange", "notificationChange"));
            XmlUtils.appendText(doc, s, cwmpNs, SoapNamespace.PREFIX_CWMP, "Notification",
                    strAny(item, "Notification", "notification"));
            XmlUtils.appendText(doc, s, cwmpNs, SoapNamespace.PREFIX_CWMP, "AccessListChange",
                    strAny(item, "AccessListChange", "accessListChange"));

            List<String> accessList = strList(item.get("AccessList"));
            if (accessList.isEmpty()) {
                XmlUtils.appendNil(doc, s, cwmpNs, SoapNamespace.PREFIX_CWMP, "AccessList");
            } else {
                Element accessEl = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "AccessList");
                for (String v : accessList) {
                    accessEl.appendChild(XmlUtils.plainElement(doc, "string", v));
                }
                s.appendChild(accessEl);
            }
            listEl.appendChild(s);
        }
        root.appendChild(listEl);
    }

    /**
     * AddObject / DeleteObject：ObjectName 与 ParameterKey（空则 nil）。
     */
    private static void buildObjectWithKey(Document doc, Element body, String cwmpNs,
                                           String name, Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, name);
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "ObjectName",
                str(args, "ObjectName"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "ParameterKey",
                str(args, "ParameterKey"));
    }

    /**
     * Download：CommandKey(nil) FileType URL Username(nil) Password(nil) FileSize
     * TargetFileName(空则空元素) DelaySeconds SuccessURL(nil) FailureURL(nil)
     */
    private static void buildDownload(Document doc, Element body, String cwmpNs,
                                      Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.DOWNLOAD.getValue());
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FileType",
                str(args, "FileType"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "URL", str(args, "URL"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Username",
                str(args, "Username"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Password",
                str(args, "Password"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FileSize",
                str(args, "FileSize"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "TargetFileName",
                str(args, "TargetFileName"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "DelaySeconds",
                str(args, "DelaySeconds"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "SuccessURL",
                str(args, "SuccessURL"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FailureURL",
                str(args, "FailureURL"));
    }

    /**
     * Upload：CommandKey FileType URL Username(nil) Password(nil) DelaySeconds
     */
    private static void buildUpload(Document doc, Element body, String cwmpNs,
                                    Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.UPLOAD.getValue());
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FileType",
                str(args, "FileType"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "URL", str(args, "URL"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Username",
                str(args, "Username"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Password",
                str(args, "Password"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "DelaySeconds",
                str(args, "DelaySeconds"));
    }

    /**
     * Reboot / CancelTransfer：仅 CommandKey（空则 nil）。
     */
    private static void buildCommandKeyOnly(Document doc, Element body, String cwmpNs,
                                            String name, Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, name);
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
    }

    /**
     * ScheduleInform：DelaySeconds 在 CommandKey 之前，顺序不可颠倒。
     */
    private static void buildScheduleInform(Document doc, Element body, String cwmpNs,
                                            Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.SCHEDULE_INFORM.getValue());
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "DelaySeconds",
                str(args, "DelaySeconds"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
    }

    /**
     * ScheduleDownload：CommandKey FileType URL Username(nil) Password(nil) FileSize
     * TargetFileName 以及 TimeWindowList 数组。
     */
    @SuppressWarnings("unchecked")
    private static void buildScheduleDownload(Document doc, Element body, String cwmpNs,
                                              Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.SCHEDULE_DOWNLOAD.getValue());
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FileType",
                str(args, "FileType"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "URL", str(args, "URL"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Username",
                str(args, "Username"));
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "Password",
                str(args, "Password"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "FileSize",
                str(args, "FileSize"));
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "TargetFileName",
                str(args, "TargetFileName"));

        Object raw = args.get("TimeWindowList");
        List<Map<String, Object>> windows = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    windows.add((Map<String, Object>) m);
                }
            }
        }
        Element listEl = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "TimeWindowList");
        listEl.setAttributeNS(SoapNamespace.SOAP_ENC, SoapNamespace.PREFIX_SOAP_ENC + ":arrayType",
                "cwmp:TimeWindowStruct[" + windows.size() + "]");
        for (Map<String, Object> w : windows) {
            Element item = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "TimeWindowStruct");
            XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "WindowStart",
                    str(w, "WindowStart"));
            XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "WindowEnd",
                    str(w, "WindowEnd"));
            XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "WindowMode",
                    str(w, "WindowMode"));
            // UserMessage 为空时写空元素，不是 nil
            XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "UserMessage",
                    str(w, "UserMessage"));
            XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "MaxRetries",
                    str(w, "MaxRetries"));
            listEl.appendChild(item);
        }
        root.appendChild(listEl);
    }

    /**
     * ChangeDUState：Operations 数组（子项 tag 由操作类型决定）与 CommandKey。
     */
    @SuppressWarnings("unchecked")
    private static void buildChangeDuState(Document doc, Element body, String cwmpNs,
                                           Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.CHANGE_DU_STATE.getValue());

        Object raw = args.get("Operations");
        List<Map<String, Object>> operations = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    operations.add((Map<String, Object>) m);
                }
            }
        }
        Element listEl = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "Operations");
        listEl.setAttributeNS(SoapNamespace.SOAP_ENC, SoapNamespace.PREFIX_SOAP_ENC + ":arrayType",
                "cwmp:OperationsStruct[" + operations.size() + "]");
        for (Map<String, Object> op : operations) {
            String opName = str(op, "Name");
            Element item = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, opName);
            switch (opName) {
                case "InstallOpStruct" -> {
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "URL", str(op, "URL"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "UUID", str(op, "UUID"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Username", str(op, "Username"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Password", str(op, "Password"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "ExecutionEnvRef",
                            str(op, "ExecutionEnvRef"));
                }
                case "UninstallOpStruct" -> {
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "UUID", str(op, "UUID"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Version", str(op, "Version"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "ExecutionEnvRef",
                            str(op, "ExecutionEnvRef"));
                }
                case "UpdateOpStruct" -> {
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "UUID", str(op, "UUID"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Version", str(op, "Version"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "URL", str(op, "URL"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Username", str(op, "Username"));
                    XmlUtils.appendText(doc, item, cwmpNs, SoapNamespace.PREFIX_CWMP, "Password", str(op, "Password"));
                }
                default -> throw new SoapException("不支持的 DUState 操作类型: " + opName);
            }
            listEl.appendChild(item);
        }
        root.appendChild(listEl);

        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "CommandKey",
                str(args, "CommandKey"));
    }

    /**
     * SetVouchers：VoucherList 数组，arrayType 为 xsd:base64，子项 tag 为 base64。
     */
    private static void buildSetVouchers(Document doc, Element body, String cwmpNs,
                                         Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.SET_VOUCHERS.getValue());
        List<String> vouchers = strList(args.get("VoucherList"));
        XmlUtils.appendArray(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "VoucherList",
                "xsd:base64[" + vouchers.size() + "]", "base64", vouchers);
    }

    /**
     * GetOptions：OptionName（空则 nil）。
     */
    private static void buildGetOptions(Document doc, Element body, String cwmpNs,
                                        Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.GET_OPTIONS.getValue());
        XmlUtils.appendTextOrNil(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "OptionName",
                str(args, "OptionName"));
    }

    /**
     * InformResponse：MaxEnvelopes。
     */
    private static void buildInformResponseBody(Document doc, Element body, String cwmpNs,
                                                Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.INFORM_RESPONSE.getValue());
        String maxEnvelopes = str(args, "MaxEnvelopes");
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "MaxEnvelopes",
                maxEnvelopes == null || maxEnvelopes.isEmpty()
                        ? String.valueOf(DEFAULT_MAX_ENVELOPES) : maxEnvelopes);
    }

    /**
     * GetRPCMethodsResponse：MethodList 字符串数组。
     */
    private static void buildGetRpcMethodsResponse(Document doc, Element body, String cwmpNs,
                                                   Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.GET_RPC_METHODS_RESPONSE.getValue());
        List<String> methods = strList(args.get("MethodList"));
        XmlUtils.appendArray(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "MethodList",
                "xsd:string[" + methods.size() + "]", "string", methods);
    }

    /**
     * KickedResponse：NextURL。
     */
    private static void buildKickedResponse(Document doc, Element body, String cwmpNs,
                                            Map<String, Object> args) {
        Element root = appendCwmpRoot(doc, body, cwmpNs, RpcMethod.KICKED_RESPONSE.getValue());
        XmlUtils.appendText(doc, root, cwmpNs, SoapNamespace.PREFIX_CWMP, "NextURL", str(args, "NextURL"));
    }

    /**
     * Fault：根元素为 SOAP-ENV:Fault，含 faultcode / faultstring / detail/cwmp:Fault。
     */
    private static void buildFaultBody(Document doc, Element body, String cwmpNs, FaultStruct fault) {
        Element root = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "Fault");
        body.appendChild(root);

        XmlUtils.appendText(doc, root, SoapNamespace.SOAP_ENV, SoapNamespace.PREFIX_SOAP_ENV,
                "faultcode", fault.getSoapFaultCode() == null ? "SOAP-ENV:Server" : fault.getSoapFaultCode());
        XmlUtils.appendText(doc, root, SoapNamespace.SOAP_ENV, SoapNamespace.PREFIX_SOAP_ENV,
                "faultstring", fault.getFaultString());

        Element detail = XmlUtils.element(doc, SoapNamespace.SOAP_ENV,
                SoapNamespace.PREFIX_SOAP_ENV, "detail");
        root.appendChild(detail);

        Element cwmpFault = XmlUtils.element(doc, cwmpNs, SoapNamespace.PREFIX_CWMP, "Fault");
        detail.appendChild(cwmpFault);
        XmlUtils.appendText(doc, cwmpFault, cwmpNs, SoapNamespace.PREFIX_CWMP, "FaultCode",
                fault.getFaultCode());
        XmlUtils.appendText(doc, cwmpFault, cwmpNs, SoapNamespace.PREFIX_CWMP, "FaultString",
                fault.getFaultString());
    }

    // ==================== 参数取值工具 ====================

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 按多个候选键名取值，先精确后忽略大小写。
     *
     * <p>同一份参数在内存里是 JavaBean（Name / Value），经队列 JSON 往返后是 Map 且键名变为
     * 字段名（name / value），两侧写法必须都能取到，否则下发的报文里参数会全空。</p>
     */
    private static String strAny(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object v = args.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String actual = entry.getKey();
            if (actual == null) {
                continue;
            }
            for (String key : keys) {
                if (actual.equalsIgnoreCase(key) && entry.getValue() != null) {
                    return String.valueOf(entry.getValue());
                }
            }
        }
        return "";
    }

    private static List<String> strList(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                result.add(o == null ? "" : String.valueOf(o));
            }
        } else if (raw.getClass().isArray()) {
            Object[] arr = (Object[]) raw;
            for (Object o : arr) {
                result.add(o == null ? "" : String.valueOf(o));
            }
        } else {
            result.add(String.valueOf(raw));
        }
        return result;
    }

    /**
     * 兼容 List&lt;ParameterValueStruct&gt; 与 List&lt;Map&gt; 两种入参形式。
     */
    @SuppressWarnings("unchecked")
    private static List<ParameterValueStruct> toParameterValueList(Object raw) {
        List<ParameterValueStruct> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                if (o instanceof ParameterValueStruct pvs) {
                    list.add(pvs);
                } else if (o instanceof Map<?, ?> m) {
                    // 指令经 Redis 队列往返后，ParameterValueStruct 会变成普通 Map，
                    // 键名是 Jackson 序列化的字段名（name / value / valueType），
                    // 与 SOAP 里的 Name / Value 大小写不同，必须按忽略大小写取值
                    Map<String, Object> map = (Map<String, Object>) m;
                    list.add(ParameterValueStruct.builder()
                            .name(strAny(map, "Name", "name"))
                            .value(strAny(map, "Value", "value"))
                            .valueType(strAny(map, "Value_type", "valueType", "value_type"))
                            .build());
                }
            }
        }
        return list;
    }
}
