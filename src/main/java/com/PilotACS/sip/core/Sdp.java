/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.core;

import java.util.ArrayList;
import java.util.List;

/** SDP 解析与组装（只取音频媒体相关字段） */
public class Sdp {

    /** 媒体方向载荷 */
    public static class Media {
        public String ip;
        public int port;
        public List<Integer> payloadTypes = new ArrayList<>();
        /** 载荷类型 → 编码名（静态载荷可能缺 rtpmap） */
        public java.util.Map<Integer, String> codecs = new java.util.LinkedHashMap<>();
        public int ptimeMs = 20;
    }

    private String sessionId = Long.toHexString(System.currentTimeMillis());
    private Media audio;

    /** 解析 SDP 文本，无有效 audio 媒体时 audio 为 null */
    public static Sdp parse(String body) {
        Sdp sdp = new Sdp();
        if (body == null || body.isBlank()) {
            return sdp;
        }
        String connectionIp = null;
        boolean inAudio = false;
        for (String line : body.replace("\r\n", "\n").split("\n")) {
            line = line.trim();
            if (line.startsWith("c=")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    connectionIp = parts[2].split("/")[0];
                }
            } else if (line.startsWith("m=audio")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    inAudio = true;
                    Media m = new Media();
                    m.port = Integer.parseInt(parts[1]);
                    for (int i = 3; i < parts.length; i++) {
                        try {
                            m.payloadTypes.add(Integer.parseInt(parts[i]));
                        } catch (NumberFormatException ignored) {
                            // 忽略非数字载荷
                        }
                    }
                    m.ip = connectionIp;
                    sdp.audio = m;
                }
            } else if (line.startsWith("a=rtpmap:") && inAudio) {
                String[] parts = line.substring(9).trim().split("\\s+");
                if (parts.length >= 2 && sdp.audio != null) {
                    try {
                        sdp.audio.codecs.put(Integer.parseInt(parts[0]), parts[1].split("/")[0].toUpperCase());
                    } catch (NumberFormatException ignored) {
                        // 忽略非法 rtpmap
                    }
                }
            } else if (line.startsWith("a=ptime:") && inAudio && sdp.audio != null) {
                try {
                    sdp.audio.ptimeMs = Math.max(10, Integer.parseInt(line.substring(8).trim()));
                } catch (NumberFormatException ignored) {
                    // 保默认 20ms
                }
            }
        }
        return sdp;
    }

    /** 组装 SDP：服务器本端媒体（ip/port/编码），8k 采样 */
    public static String build(String ip, int port, int payloadType, String codecName) {
        long now = System.currentTimeMillis();
        return "v=0\r\n"
                + "o=- " + now + " " + now + " IN IP4 " + ip + "\r\n"
                + "s=PilotACS\r\n"
                + "c=IN IP4 " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + port + " RTP/AVP " + payloadType + "\r\n"
                + "a=rtpmap:" + payloadType + " " + codecName + "/8000\r\n"
                + "a=ptime:20\r\n"
                + "a=sendrecv\r\n";
    }

    /** 组装 SDP（双编码 offer）：首个为服务器首选，对端 answer 可能选任意一个 */
    public static String build(String ip, int port, int[] payloadTypes, String[] codecNames) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n")
                .append("o=- ").append(now).append(' ').append(now).append(" IN IP4 ").append(ip).append("\r\n")
                .append("s=PilotACS\r\n")
                .append("c=IN IP4 ").append(ip).append("\r\n")
                .append("t=0 0\r\n")
                .append("m=audio ").append(port).append(" RTP/AVP");
        for (int pt : payloadTypes) {
            sb.append(' ').append(pt);
        }
        sb.append("\r\n");
        for (int i = 0; i < payloadTypes.length; i++) {
            sb.append("a=rtpmap:").append(payloadTypes[i]).append(' ').append(codecNames[i]).append("/8000\r\n");
        }
        sb.append("a=ptime:20\r\n").append("a=sendrecv\r\n");
        return sb.toString();
    }

    public Media getAudio() { return audio; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
}
