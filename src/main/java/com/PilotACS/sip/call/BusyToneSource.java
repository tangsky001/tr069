/**
 * @author: tangsky001
 * @date:   2026年11月23日
 *
 */

package com.PilotACS.sip.call;

import com.PilotACS.sip.audio.WavReader;
import com.PilotACS.sip.rtp.RtpAgent;

/** 忙音/提示音源：优先循环播放配置的语音 wav（如"您拨打的用户正在通话中"），否则合成 450Hz 忙音（响 0.35s 断 0.35s） */
public class BusyToneSource {

    private final short[] wav;
    private volatile int pos;
    private final long startMs = System.currentTimeMillis();

    public BusyToneSource(String wavPath) {
        short[] loaded = wavPath == null || wavPath.isBlank() ? null : WavReader.readMono8k(wavPath);
        this.wav = loaded;
    }

    /** 下一帧 20ms PCM，永不返回 null（保证提示音循环不间断） */
    public short[] nextFrame() {
        if (wav != null && wav.length > 0) {
            short[] frame = new short[RtpAgent.FRAME_SAMPLES];
            for (int i = 0; i < frame.length; i++) {
                frame[i] = wav[pos];
                pos = (pos + 1) % wav.length;
            }
            return frame;
        }
        return synthTone();
    }

    /** 450Hz 正弦：响 0.35 秒、停 0.35 秒（中国标准忙音"嘟嘟嘟"） */
    private short[] synthTone() {
        long ms = (System.currentTimeMillis() - startMs) % 700;
        short[] frame = new short[RtpAgent.FRAME_SAMPLES];
        if (ms >= 350) {
            return frame;
        }
        long base = ms / 20 * RtpAgent.FRAME_SAMPLES;
        for (int i = 0; i < frame.length; i++) {
            long idx = base + i;
            frame[i] = (short) (Math.sin(idx * 2 * Math.PI * 450.0 / 8000.0) * 12000);
        }
        return frame;
    }
}
