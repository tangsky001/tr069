/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.call;

import com.PilotACS.sip.audio.WavReader;
import com.PilotACS.sip.rtp.RtpAgent;

/** 回铃音源：优先循环播放配置的背景音乐 wav，否则合成 450Hz（响 1s 停 4s）回铃音 */
public class RingbackSource {

    private final short[] music;
    private volatile int musicPos;
    private final long startMs = System.currentTimeMillis();

    public RingbackSource(String wavPath) {
        short[] loaded = wavPath == null || wavPath.isBlank() ? null : WavReader.readMono8k(wavPath);
        this.music = loaded;
    }

    /** 下一帧 20ms PCM，永不返回 null（保证回铃不间断） */
    public short[] nextFrame() {
        if (music != null && music.length > 0) {
            short[] frame = new short[RtpAgent.FRAME_SAMPLES];
            for (int i = 0; i < frame.length; i++) {
                frame[i] = music[musicPos];
                musicPos = (musicPos + 1) % music.length;
            }
            return frame;
        }
        return synthTone();
    }

    /** 450Hz 正弦，响 1 秒停 4 秒 */
    private short[] synthTone() {
        long ms = (System.currentTimeMillis() - startMs) % 5000;
        short[] frame = new short[RtpAgent.FRAME_SAMPLES];
        if (ms >= 1000) {
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
