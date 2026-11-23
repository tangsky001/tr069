/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.rtp;

/** RTP 包解析与组装（RFC 3550，无扩展头） */
public class RtpPacket {

    private int version = 2;
    private boolean marker;
    private int payloadType;
    private int sequence;
    private long timestamp;
    private long ssrc = (long) (Math.random() * 0x7FFFFFFFL);
    private byte[] payload = new byte[0];

    /** 解析 UDP 载荷，非法返回 null */
    public static RtpPacket parse(byte[] data, int length) {
        if (length < 12 || (data[0] & 0xC0) >> 6 != 2) {
            return null;
        }
        RtpPacket p = new RtpPacket();
        boolean padding = (data[0] & 0x20) != 0;
        int csrcCount = data[0] & 0x0F;
        int headerLen = 12 + csrcCount * 4;
        if (length < headerLen) {
            return null;
        }
        p.marker = (data[1] & 0x80) != 0;
        p.payloadType = data[1] & 0x7F;
        p.sequence = (data[2] & 0xFF) << 8 | data[3] & 0xFF;
        p.timestamp = (data[4] & 0xFF) << 24 | (data[5] & 0xFF) << 16 | (data[6] & 0xFF) << 8 | data[7] & 0xFF;
        p.ssrc = (data[8] & 0xFF) << 24 | (data[9] & 0xFF) << 16 | (data[10] & 0xFF) << 8 | data[11] & 0xFF;
        int payloadLen = length - headerLen;
        if (padding && payloadLen > 0) {
            payloadLen -= data[length - 1] & 0xFF;
        }
        if (payloadLen <= 0) {
            payloadLen = 0;
        }
        p.payload = new byte[payloadLen];
        System.arraycopy(data, headerLen, p.payload, 0, payloadLen);
        return p;
    }

    /** 组装为 UDP 载荷（每包固定 20ms 帧一律置 marker=0，首包置 1） */
    public byte[] serialize() {
        byte[] out = new byte[12 + payload.length];
        out[0] = (byte) 0x80;
        out[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));
        out[2] = (byte) (sequence >> 8);
        out[3] = (byte) sequence;
        out[4] = (byte) (timestamp >> 24);
        out[5] = (byte) (timestamp >> 16);
        out[6] = (byte) (timestamp >> 8);
        out[7] = (byte) timestamp;
        out[8] = (byte) (ssrc >> 24);
        out[9] = (byte) (ssrc >> 16);
        out[10] = (byte) (ssrc >> 8);
        out[11] = (byte) ssrc;
        System.arraycopy(payload, 0, out, 12, payload.length);
        return out;
    }

    public boolean isMarker() { return marker; }
    public void setMarker(boolean marker) { this.marker = marker; }
    public int getPayloadType() { return payloadType; }
    public void setPayloadType(int payloadType) { this.payloadType = payloadType; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence & 0xFFFF; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp & 0xFFFFFFFFL; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }
}
