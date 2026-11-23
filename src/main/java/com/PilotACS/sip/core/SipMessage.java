/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SIP 报文解析与组装（请求/响应通用） */
public class SipMessage {

    /** 报文类型 */
    public enum Kind { REQUEST, RESPONSE }

    private Kind kind;
    private String method = "";
    private String uri = "";
    private int statusCode;
    private String reasonPhrase = "";
    private String version = "SIP/2.0";
    /** 头域（原始名 → 值列表，保持顺序） */
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private String body = "";

    /** 解析原始报文，格式非法返回 null */
    public static SipMessage parse(String raw) {
        try {
            String text = raw.replace("\r\n", "\n");
            int split = text.indexOf("\n\n");
            String head = split >= 0 ? text.substring(0, split) : text;
            String body = split >= 0 ? text.substring(split + 2) : "";
            String[] lines = head.split("\n");
            if (lines.length == 0) {
                return null;
            }
            SipMessage msg = new SipMessage();
            String first = lines[0].trim();
            if (first.startsWith("SIP/2.0")) {
                msg.kind = Kind.RESPONSE;
                String[] parts = first.split("\\s+", 3);
                msg.version = parts[0];
                msg.statusCode = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                msg.reasonPhrase = parts.length > 2 ? parts[2] : "";
            } else {
                msg.kind = Kind.REQUEST;
                String[] parts = first.split("\\s+", 3);
                msg.method = parts[0].toUpperCase();
                msg.uri = parts.length > 1 ? parts[1] : "*";
                msg.version = parts.length > 2 ? parts[2] : "SIP/2.0";
            }
            String lastKey = null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                if ((line.startsWith(" ") || line.startsWith("\t")) && lastKey != null) {
                    List<String> values = msg.headers.get(lastKey);
                    values.set(values.size() - 1, values.get(values.size() - 1) + " " + line.trim());
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                lastKey = line.substring(0, idx).trim();
                msg.headers.computeIfAbsent(lastKey, k -> new ArrayList<>()).add(line.substring(idx + 1).trim());
            }
            msg.body = body;
            return msg;
        } catch (Exception e) {
            return null;
        }
    }

    public static SipMessage request(String method, String uri) {
        SipMessage msg = new SipMessage();
        msg.kind = Kind.REQUEST;
        msg.method = method.toUpperCase();
        msg.uri = uri;
        return msg;
    }

    public static SipMessage response(int code, String reason) {
        SipMessage msg = new SipMessage();
        msg.kind = Kind.RESPONSE;
        msg.statusCode = code;
        msg.reasonPhrase = reason;
        return msg;
    }

    public void setHeader(String name, String value) {
        headers.remove(name);
        List<String> other = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                other.add(e.getKey());
            }
        }
        for (String k : other) {
            headers.remove(k);
        }
        headers.put(name, new ArrayList<>(List.of(value)));
    }

    public void addHeader(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    /** 大小写不敏感取第一个头值，无则返回 null */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                List<String> values = e.getValue();
                return values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    public boolean hasHeader(String name) {
        return header(name) != null;
    }

    public String callId() {
        return header("Call-ID");
    }

    public String fromTag() {
        return tagOf(header("From"));
    }

    public String toTag() {
        return tagOf(header("To"));
    }

    /** 从 From/To 头中提取 tag 参数 */
    public static String tagOf(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int idx = headerValue.indexOf("tag=");
        return idx >= 0 ? headerValue.substring(idx + 4).split("[;>\\s]")[0] : null;
    }

    /** 提取头里尖括号内的地址（无尖括号取分号前部分） */
    public static String uriOf(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int lt = headerValue.indexOf('<');
        int gt = headerValue.indexOf('>', lt);
        if (lt >= 0 && gt > lt) {
            return headerValue.substring(lt + 1, gt);
        }
        int semi = headerValue.indexOf(';');
        return semi >= 0 ? headerValue.substring(0, semi).trim() : headerValue.trim();
    }

    /** From/To/Request-URI 中的 user 部分（sip:user@host） */
    public static String userOf(String uri) {
        if (uri == null) {
            return null;
        }
        int start = uri.indexOf("sip:") >= 0 ? uri.indexOf("sip:") + 4 : 0;
        int semi = uri.indexOf(';', start);
        String core = semi >= 0 ? uri.substring(start, semi) : uri.substring(start);
        int at = core.indexOf('@');
        return at >= 0 ? core.substring(0, at) : core;
    }

    public int cseqNumber() {
        String cseq = header("CSeq");
        if (cseq == null || cseq.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(cseq.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        if (kind == Kind.REQUEST) {
            sb.append(method).append(' ').append(uri).append(' ').append(version).append("\r\n");
        } else {
            sb.append(version).append(' ').append(statusCode).append(' ').append(reasonPhrase).append("\r\n");
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        if (body != null && !body.isEmpty()) {
            sb.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
            sb.append("\r\n").append(body);
        } else {
            sb.append("Content-Length: 0\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public Kind getKind() { return kind; }
    public String getMethod() { return method; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public int getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reasonPhrase; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Map<String, List<String>> getHeaders() { return headers; }
}
