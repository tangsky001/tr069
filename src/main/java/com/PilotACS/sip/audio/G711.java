/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.audio;

/** G.711 A 律 / U 律编解码（8k 单字节 ↔ 16bit PCM 小端） */
public final class G711 {

    private static final short[] ALAW_TO_PCM = new short[256];
    private static final short[] ULAW_TO_PCM = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int alaw = i ^ 0x55;
            int aSign = alaw & 0x80;
            int aExpo = (alaw >> 4) & 0x07;
            int aMant = alaw & 0x0F;
            int aVal = aExpo == 0 ? (aMant << 4) + 8 : ((aMant << 4) + 0x108) << (aExpo - 1);
            ALAW_TO_PCM[i] = (short) (aSign == 0 ? aVal : -aVal);

            int ulaw = ~i & 0xFF;
            int uSign = ulaw & 0x80;
            int uExpo = (ulaw >> 4) & 0x07;
            int uMant = ulaw & 0x0F;
            int uVal = ((uMant << 3) + 0x84) << uExpo;
            ULAW_TO_PCM[i] = (short) (uSign == 0 ? uVal : -uVal);
        }
    }

    private G711() {
    }

    public static short alawToPcm(int alaw) {
        return ALAW_TO_PCM[alaw & 0xFF];
    }

    public static short ulawToPcm(int ulaw) {
        return ULAW_TO_PCM[ulaw & 0xFF];
    }

    public static byte pcmToAlaw(short pcm) {
        int sign = pcm & 0x8000;
        int v = pcm < 0 ? -pcm : pcm;
        if (v > 32635) {
            v = 32635;
        }
        int expo = v < 256 ? 0 : v < 512 ? 1 : v < 1024 ? 2 : v < 2048 ? 3 : v < 4096 ? 4 : v < 8192 ? 5 : v < 16384 ? 6 : 7;
        int mant = expo == 0 ? (v >> 4) : (v >> (expo + 3)) & 0x0F;
        int alaw = (expo << 4) | mant;
        byte out = (byte) ((sign == 0 ? alaw : alaw | 0x80) ^ 0x55);
        return out;
    }

    public static byte pcmToUlaw(short pcm) {
        int sign = pcm & 0x8000;
        int v = (pcm < 0 ? -pcm : pcm) + 0x84;
        if (v > 32767) {
            v = 32767;
        }
        int expo = 7;
        for (int mask = 0x4000; (v & mask) == 0 && expo > 0; mask >>= 1) {
            expo--;
        }
        int mant = (v >> (expo + 3)) & 0x0F;
        int ulaw = (expo << 4) | mant;
        byte out = (byte) (~(sign == 0 ? ulaw : ulaw | 0x80));
        return out;
    }
}
