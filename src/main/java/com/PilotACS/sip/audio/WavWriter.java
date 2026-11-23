/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.audio;

import java.io.IOException;
import java.io.RandomAccessFile;

/** 流式写 8k/16bit/双声道 PCM WAV（左右声道分别为通话两路，先写占位头、close 时补正长度） */
public class WavWriter implements AutoCloseable {

    private static final int SAMPLE_RATE = 8000;
    private final RandomAccessFile file;
    private long frames;

    public WavWriter(String path) throws IOException {
        this.file = new RandomAccessFile(path, "rw");
        this.file.setLength(0);
        writeHeader(0);
    }

    /** 追加一对 PCM 采样（left/right），某侧缺帧传 null 时填静音 */
    public synchronized void writeFrame(short[] left, short[] right) throws IOException {
        int n = Math.max(left == null ? 0 : left.length, right == null ? 0 : right.length);
        for (int i = 0; i < n; i++) {
            int l = left != null && i < left.length ? left[i] : 0;
            int r = right != null && i < right.length ? right[i] : 0;
            file.write(l & 0xFF);
            file.write(l >> 8 & 0xFF);
            file.write(r & 0xFF);
            file.write(r >> 8 & 0xFF);
        }
        frames += n;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            int dataBytes = (int) (frames * 4);
            file.seek(0);
            writeHeader(dataBytes);
        } finally {
            file.close();
        }
    }

    private void writeHeader(int dataBytes) throws IOException {
        int byteRate = SAMPLE_RATE * 4;
        file.writeBytes("RIFF");
        file.writeInt(Integer.reverseBytes(36 + dataBytes));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        file.writeInt(Integer.reverseBytes(16));
        file.writeShort(Short.reverseBytes((short) 1));
        file.writeShort(Short.reverseBytes((short) 2));
        file.writeInt(Integer.reverseBytes(SAMPLE_RATE));
        file.writeInt(Integer.reverseBytes(byteRate));
        file.writeShort(Short.reverseBytes((short) 4));
        file.writeShort(Short.reverseBytes((short) 16));
        file.writeBytes("data");
        file.writeInt(Integer.reverseBytes(dataBytes));
    }
}
