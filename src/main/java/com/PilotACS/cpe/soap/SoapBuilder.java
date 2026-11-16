/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.soap;

import com.PilotACS.cpe.model.RpcRequest;

import java.util.List;
import java.util.Map;

/**
 * ACS → CPE 的 SOAP 报文生成
 */
public final class SoapBuilder {

    private static final String ENVELOPE_OPEN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<soap-env:Envelope"
            + " xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:soap-enc=\"http://schemas.xmlsoap.org/soap/encoding/\""
            + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xmlns:cwmp=\"urn:dslforum-org:cwmp-1-0\">";

    private SoapBuilder() {
    }

    /** InformResponse，MaxEnvelopes 固定 1 */
    public static String informResponse(String id) {
        return ENVELOPE_OPEN
                + header(id)
                + "<soap-env:Body><cwmp:InformResponse><MaxEnvelopes>1</MaxEnvelopes></cwmp:InformResponse></soap-env:Body>"
                + "</soap-env:Envelope>";
    }

    /**
     * ACS 侧 Inform 请求：部分设备（收到反向连接后）用空 POST 启动会话（TR-069 3.3.3），
     * 此时 ACS 尚不知道设备身份，回 Inform 请求索取设备信息。
     */
    public static String informRequest(String id) {
        return ENVELOPE_OPEN
                + header(id)
                + "<soap-env:Body><cwmp:Inform>"
                + "<DeviceId><Manufacturer/><OUI/><ProductClass/><SerialNumber/></DeviceId>"
                + "<Event soap-enc:arrayType=\"cwmp:EventStruct[0]\"/>"
                + "<MaxEnvelopes>1</MaxEnvelopes>"
                + "<CurrentTime>" + esc(java.time.OffsetDateTime.now().withNano(0).toString()) + "</CurrentTime>"
                + "<RetryCount>0</RetryCount>"
                + "<ParameterList soap-enc:arrayType=\"cwmp:ParameterValueStruct[0]\"/>"
                + "</cwmp:Inform></soap-env:Body></soap-env:Envelope>";
    }

    /** ACS 发起的 RPC 请求 */
    public static String request(RpcRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append(ENVELOPE_OPEN).append(header(r.getId())).append("<soap-env:Body>");
        switch (r.getKind()) {
            case GPN -> sb.append(gpn(r));
            case GPV -> sb.append(gpv(r));
            case SPV -> sb.append(spv(r));
            case ADD_OBJECT -> sb.append("<cwmp:AddObject><ObjectName>").append(esc(r.getPath()))
                    .append("</ObjectName><ParameterKey>").append(esc(r.taskKey()))
                    .append("</ParameterKey></cwmp:AddObject>");
            case DELETE_OBJECT -> sb.append("<cwmp:DeleteObject><ObjectName>").append(esc(r.getPath()))
                    .append("</ObjectName><ParameterKey>").append(esc(r.taskKey()))
                    .append("</ParameterKey></cwmp:DeleteObject>");
            case REBOOT -> sb.append("<cwmp:Reboot><CommandKey>").append(esc(r.taskKey()))
                    .append("</CommandKey><DelaySeconds>0</DelaySeconds></cwmp:Reboot>");
            case FACTORY_RESET -> sb.append("<cwmp:FactoryReset><CommandKey>").append(esc(r.taskKey()))
                    .append("</CommandKey></cwmp:FactoryReset>");
            case DOWNLOAD -> sb.append(download(r));
            case GET_RPC_METHODS -> sb.append("<cwmp:GetRPCMethods/>");
            case CUSTOM -> sb.append(custom(r));
        }
        sb.append("</soap-env:Body></soap-env:Envelope>");
        return sb.toString();
    }

    /** GetRPCMethodsResponse：固定暴露 Inform/GetRPCMethods/TransferComplete */
    public static String getRpcMethodsResponse(String id, List<String> methods) {
        StringBuilder sb = new StringBuilder();
        sb.append(ENVELOPE_OPEN).append(header(id))
                .append("<soap-env:Body><cwmp:GetRPCMethodsResponse>")
                .append("<MethodList soap-enc:arrayType=\"xsd:string[").append(methods.size()).append("]\">");
        for (String m : methods) {
            sb.append("<string>").append(esc(m)).append("</string>");
        }
        sb.append("</MethodList></cwmp:GetRPCMethodsResponse></soap-env:Body></soap-env:Envelope>");
        return sb.toString();
    }

    /** ACS 侧 Fault 响应（如设备在错误状态下发了不允许的 RPC） */
    public static String acsFault(int code, String message) {
        return ENVELOPE_OPEN
                + "<soap-env:Header/><soap-env:Body><soap-env:Fault>"
                + "<faultcode>Client</faultcode>"
                + "<faultstring>CWMP fault</faultstring>"
                + "<detail><cwmp:Fault><FaultCode>" + code
                + "</FaultCode><FaultString>" + esc(message) + "</FaultString></cwmp:Fault></detail>"
                + "</soap-env:Fault></soap-env:Body></soap-env:Envelope>";
    }

    // ---------------- 内部拼装 ----------------

    private static String header(String id) {
        return "<soap-env:Header><cwmp:ID soap-env:mustUnderstand=\"1\">" + esc(id) + "</cwmp:ID></soap-env:Header>";
    }

    private static String gpn(RpcRequest r) {
        return "<cwmp:GetParameterNames><ParameterPath>" + esc(r.getPath())
                + "</ParameterPath><NextLevel>" + (r.isNextLevel() ? 1 : 0) + "</NextLevel></cwmp:GetParameterNames>";
    }

    private static String gpv(RpcRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<cwmp:GetParameterValues><ParameterNames soap-enc:arrayType=\"xsd:string[")
                .append(r.getNames() == null ? 0 : r.getNames().size()).append("]\">");
        if (r.getNames() != null) {
            for (String n : r.getNames()) {
                sb.append("<string>").append(esc(n)).append("</string>");
            }
        }
        sb.append("</ParameterNames></cwmp:GetParameterValues>");
        return sb.toString();
    }

    private static String spv(RpcRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<cwmp:SetParameterValues><ParameterList soap-enc:arrayType=\"cwmp:ParameterValueStruct[")
                .append(r.getValues() == null ? 0 : r.getValues().size()).append("]\">");
        if (r.getValues() != null) {
            for (RpcRequest.NameValue nv : r.getValues()) {
                sb.append("<ParameterValueStruct><Name>").append(esc(nv.name()))
                        .append("</Name><Value xsi:type=\"xsd:").append(nv.type() == null ? "string" : nv.type())
                        .append("\">").append(esc(nv.value())).append("</Value></ParameterValueStruct>");
            }
        }
        sb.append("</ParameterList><ParameterKey>").append(esc(r.taskKey()))
                .append("</ParameterKey></cwmp:SetParameterValues>");
        return sb.toString();
    }

    private static String download(RpcRequest r) {
        return "<cwmp:Download><CommandKey>" + esc(r.taskKey()) + "</CommandKey>"
                + "<FileType>" + esc(r.getFileType() == null ? "1 Firmware Upgrade Image" : r.getFileType())
                + "</FileType><URL>" + esc(r.getUrl()) + "</URL>"
                + "<Username></Username><Password></Password>"
                + "<FileSize>" + r.getFileSize() + "</FileSize>"
                + "<TargetFileName>" + esc(r.getFileName() == null ? "" : r.getFileName()) + "</TargetFileName>"
                + "<DelaySeconds>0</DelaySeconds><SuccessURL></SuccessURL><FailureURL></FailureURL>"
                + "</cwmp:Download>";
    }

    /** 透传型自定义 RPC：方法名 + 结构化参数 */
    private static String custom(RpcRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<cwmp:").append(r.getMethod());
        Map<String, Object> args = r.getArgs();
        if (args == null || args.isEmpty()) {
            sb.append("/>");
        } else {
            sb.append('>');
            appendStruct(sb, args);
            sb.append("</cwmp:").append(r.getMethod()).append('>');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendStruct(StringBuilder sb, Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String name = e.getKey();
            Object v = e.getValue();
            sb.append('<').append(name).append('>');
            if (v instanceof Map<?, ?> m) {
                appendStruct(sb, (Map<String, Object>) m);
            } else if (v instanceof List<?> list) {
                for (Object item : list) {
                    sb.append('<').append(name).append('>');
                    if (item instanceof Map<?, ?> m) {
                        appendStruct(sb, (Map<String, Object>) m);
                    } else {
                        sb.append(esc(String.valueOf(item)));
                    }
                    sb.append("</").append(name).append('>');
                }
            } else {
                sb.append(esc(v == null ? "" : String.valueOf(v)));
            }
            sb.append("</").append(name).append('>');
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
