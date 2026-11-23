/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.call;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.audio.G711;
import com.PilotACS.sip.audio.WavWriter;
import com.PilotACS.sip.rtp.RtpAgent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 一通 B2BUA 呼叫：A/B 两腿 RTP 桥接、回铃媒体、录音 */
public class CallSession {

    /** 呼叫状态 */
    public enum State { RINGING, ACTIVE, ENDED }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String callIdA;
    private String callIdB;
    private final String callerExt;
    private String calleeExt;
    private final RtpAgent agentA;
    private RtpAgent agentB;
    private final boolean alawA;
    private boolean alawB = true;
    private volatile State state = State.RINGING;
    private final long createdAt = System.currentTimeMillis();
    private volatile long answeredAt;
    private volatile WavWriter recorder;
    private final RingbackSource ringback;
    private final boolean recordEnabled;
    /** 振铃期是否给 A 腿（SIP 主叫）发服务器回铃早媒体；MG 主叫用网关侧信号则关掉 */
    private final boolean earlyMediaToA;
    /** 忙线提示音会话：占位呼叫，振铃期循环向 A 腿放"正在通话"提示音 */
    private volatile BusyToneSource busyPrompt;
    /** 挂断忙音：一方挂断后，向存活方循环播忙音直至拆线 */
    private volatile BusyToneSource toneA;
    private volatile BusyToneSource toneB;
    private String recordPath;

    public CallSession(String callIdA, String callerExt, String calleeExt, RtpAgent agentA,
                       boolean alawA, RingbackSource ringback, boolean recordEnabled) {
        this(callIdA, callerExt, calleeExt, agentA, alawA, ringback, recordEnabled, true);
    }

    public CallSession(String callIdA, String callerExt, String calleeExt, RtpAgent agentA,
                       boolean alawA, RingbackSource ringback, boolean recordEnabled, boolean earlyMediaToA) {
        this.callIdA = callIdA;
        this.callerExt = callerExt;
        this.calleeExt = calleeExt;
        this.agentA = agentA;
        this.alawA = alawA;
        this.ringback = ringback;
        this.recordEnabled = recordEnabled;
        this.earlyMediaToA = earlyMediaToA;
    }

    /** 接通：切 ACTIVE 并开录音（两腿 RTP 已在会话内） */
    public void answer() {
        this.state = State.ACTIVE;
        this.answeredAt = System.currentTimeMillis();
        if (recordEnabled) {
            openRecorder();
        }
        Logs.info(Logs.Module.SIP, "呼叫接通", "主叫", callerExt, "被叫", calleeExt,
                "录音", recordPath == null ? "关闭" : recordPath);
    }

    private void openRecorder() {
        try {
            Path dir = Path.of("data/sip-records");
            Files.createDirectories(dir);
            recordPath = dir.resolve("call-" + TS.format(LocalDateTime.now()) + "-"
                    + callerExt + "-" + calleeExt + ".wav").toString();
            recorder = new WavWriter(recordPath);
        } catch (IOException e) {
            Logs.error(Logs.Module.SIP, "录音文件创建失败", "错误", e.toString());
            recorder = null;
        }
    }

    /** 每 20ms 节拍：振铃发回铃音乐，接通后双向桥接 + 录音 */
    public void tick() {
        if (state == State.ENDED) {
            return;
        }
        if (state == State.RINGING) {
            if (busyPrompt != null) {
                // 忙线提示音会话：循环向主叫放"正在通话"提示音
                agentA.sendPcm(busyPrompt.nextFrame());
                agentA.poll();
                return;
            }
            if (earlyMediaToA) {
                agentA.sendPcm(ringback.nextFrame());
            }
            // 振铃期只排空不转发（H.248 主叫的回铃由网关侧信号提供）
            agentA.poll();
            if (agentB != null) {
                agentB.poll();
            }
            return;
        }
        if (agentB == null) {
            // B 腿尚未挂入（理论不可达，防御性兜底）：只排空 A，避免媒体节拍线程崩
            agentA.poll();
            return;
        }
        byte[] fromB = agentB.poll();
        byte[] fromA = agentA.poll();
        short[] pcmA = decode(fromA, alawA);
        short[] pcmB = decode(fromB, alawB);
        // 挂断忙音：一方已挂，向存活方播忙音代替对端媒体
        agentA.sendPcm(toneA != null ? toneA.nextFrame() : pcmB);
        agentB.sendPcm(toneB != null ? toneB.nextFrame() : pcmA);
        if (recorder != null) {
            try {
                recorder.writeFrame(pcmA, pcmB);
            } catch (IOException e) {
                Logs.error(Logs.Module.SIP, "录音写入失败", "错误", e.toString());
            }
        }
    }

    private short[] decode(byte[] encoded, boolean alaw) {
        if (encoded == null) {
            return null;
        }
        short[] pcm = new short[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            pcm[i] = alaw ? G711.alawToPcm(encoded[i]) : G711.ulawToPcm(encoded[i]);
        }
        return pcm;
    }

    /** 挂断收尾：停 RTP、落录音文件 */
    public void teardown(String reason) {
        if (state == State.ENDED) {
            return;
        }
        long durationSec = answeredAt > 0 ? (System.currentTimeMillis() - answeredAt) / 1000 : 0;
        state = State.ENDED;
        agentA.stop();
        if (agentB != null) {
            agentB.stop();
        }
        if (recorder != null) {
            try {
                recorder.close();
            } catch (IOException e) {
                Logs.error(Logs.Module.SIP, "录音关闭失败", "错误", e.toString());
            }
        }
        Logs.info(Logs.Module.SIP, "呼叫结束", "主叫", callerExt, "被叫", calleeExt,
                "通话秒", durationSec, "原因", reason);
    }

    public String getCallIdA() { return callIdA; }
    public String getCallIdB() { return callIdB; }
    public void setCallIdB(String callIdB) { this.callIdB = callIdB; }
    public String getCallerExt() { return callerExt; }
    public String getCalleeExt() { return calleeExt; }
    /** 队列呼叫选定实际被叫后回填 */
    public void setCalleeExt(String calleeExt) { this.calleeExt = calleeExt; }
    public RtpAgent getAgentA() { return agentA; }
    public boolean isAlawA() { return alawA; }
    public RtpAgent getAgentB() { return agentB; }
    /** B 腿 RTP 在 CallManager 创建后挂入会话（SIP 外呼 / H.248 腿） */
    public void setAgentB(RtpAgent agentB) { this.agentB = agentB; }
    public State getState() { return state; }
    public boolean isPromptOnly() { return busyPrompt != null; }
    public void setBusyPrompt(BusyToneSource src) { this.busyPrompt = src; }
    public void setToneA(BusyToneSource src) { this.toneA = src; }
    public void setToneB(BusyToneSource src) { this.toneB = src; }
    public long getCreatedAt() { return createdAt; }
    public long getAnsweredAt() { return answeredAt; }
    public String getRecordPath() { return recordPath; }
}
