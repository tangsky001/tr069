/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.audio;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 读取 8k/16bit/单声道 PCM WAV 为短整型采样数组（回铃背景音乐用） */
public final class WavReader {

    private WavReader() {
    }

    /** 采样率或位深不符返回 null */
    public static short[] readMono8k(String path) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] all = in.readAllBytes();
            int pos = 12;
            int sampleRate = 0;
            int bits = 0;
            int channels = 0;
            byte[] data = null;
            while (pos + 8 <= all.length) {
                String chunkId = new String(all, pos, 4);
                int size = Integer.parseInt(Integer.toUnsignedString(readInt(all, pos + 4)));
                if ("fmt ".equals(chunkId) && size >= 16) {
                    channels = readShort(all, pos + 10);
                    sampleRate = readInt(all, pos + 12);
                    bits = readShort(all, pos + 22);
                } else if ("data".equals(chunkId)) {
                    data = new byte[Math.min(size, all.length - pos - 8)];
                    System.arraycopy(all, pos + 8, data, 0, data.length);
                }
                pos += 8 + size + (size % 2);
            }
            if (data == null || sampleRate != 8000 || bits != 16 || channels != 1) {
                return null;
            }
            short[] samples = new short[data.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) (data[i * 2] & 0xFF | data[i * 2 + 1] << 8);
            }
            return samples;
        } catch (IOException e) {
            return null;
        }
    }

    private static int readShort(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | b[off + 3] << 24;
    }
}
