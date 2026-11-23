/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.rtp;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.audio.G711;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/** 单路 RTP 端点：绑定本地口、收包进抖动缓冲、按 20ms 节拍编码发出 */
public class RtpAgent {

    /** 帧长（样本数，20ms@8k） */
    public static final int FRAME_SAMPLES = 160;

    private final DatagramSocket socket;
    private final JitterBuffer jitter;
    private final int payloadType;
    /** true=A 律（PCMA 8），false=U 律（PCMU 0） */
    private final boolean alaw;
    private volatile InetSocketAddress target;
    private volatile boolean running;
    /** 最近一次收到有效 RTP 的时间戳（毫秒），用于会话空闲检测 */
    private volatile long lastRxAt;
    private Thread recvThread;
    private int sendSeq;
    private long sendTimestamp;
    private boolean firstPacket = true;
    private String tag = "rtp";

    public RtpAgent(boolean alaw, int jitterPackets, long jitterTimeoutMs) throws SocketException {
        this.alaw = alaw;
        this.payloadType = alaw ? 8 : 0;
        this.jitter = new JitterBuffer(jitterPackets, jitterTimeoutMs);
        this.socket = new DatagramSocket(0);
        this.socket.setReceiveBufferSize(65536);
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public void setTarget(InetSocketAddress target) {
        this.target = target;
    }

    public void start() {
        running = true;
        lastRxAt = System.currentTimeMillis();
        recvThread = new Thread(this::receiveLoop, "rtp-recv-" + tag);
        recvThread.setDaemon(true);
        recvThread.start();
    }

    public long getLastRxAt() {
        return lastRxAt;
    }

    public void stop() {
        running = false;
        socket.close();
        if (recvThread != null) {
            recvThread.interrupt();
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.setSoTimeout(500);
                socket.receive(pkt);
                RtpPacket rtp = RtpPacket.parse(pkt.getData(), pkt.getLength());
                if (rtp != null && (rtp.getPayloadType() == 0 || rtp.getPayloadType() == 8)) {
                    lastRxAt = System.currentTimeMillis();
                    jitter.push(rtp.getSequence(), rtp.getPayload());
                    // 目标未定（对端 SDP 缺失）时贴住来包源地址
                    if (target == null) {
                        target = (InetSocketAddress) pkt.getSocketAddress();
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // 空转继续
            } catch (SocketException e) {
                break;
            } catch (IOException e) {
                Logs.debug(Logs.Module.SIP, "RTP 收包异常", "tag", tag, "错误", e.toString());
            }
        }
    }

    /** 每 20ms 由呼叫会话调用：弹出对端一帧（无帧返回 null，由会话决定静音/回铃/桥接） */
    public byte[] poll() {
        return jitter.poll();
    }

    /** 编码 PCM 帧并按 RTP 发出（seq/timestamp 自增） */
    public synchronized void sendPcm(short[] pcm) {
        if (!running || target == null || pcm == null || pcm.length == 0) {
            return;
        }
        byte[] frame = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            frame[i] = alaw ? G711.pcmToAlaw(pcm[i]) : G711.pcmToUlaw(pcm[i]);
        }
        RtpPacket pkt = new RtpPacket();
        pkt.setPayloadType(payloadType);
        pkt.setSequence(sendSeq++);
        pkt.setTimestamp(sendTimestamp);
        pkt.setMarker(firstPacket);
        pkt.setPayload(frame);
        sendTimestamp += FRAME_SAMPLES;
        firstPacket = false;
        try {
            byte[] data = pkt.serialize();
            socket.send(new DatagramPacket(data, data.length, target.getAddress(), target.getPort()));
        } catch (IOException e) {
            Logs.debug(Logs.Module.SIP, "RTP 发包失败", "tag", tag, "错误", e.toString());
        }
    }

    public InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }
}
