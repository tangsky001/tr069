package com.tr069.acs.api.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** 二维码生成：把一段文本渲染成 PNG */
public final class QrCodeUtil {

    /** 默认边长（像素） */
    public static final int DEFAULT_SIZE = 240;

    /** 纠错等级 M：容错约 15%，够用且码图不至于太密 */
    private static final ErrorCorrectionLevel ECC = ErrorCorrectionLevel.M;

    private QrCodeUtil() {
    }

    /**
     * 生成二维码 PNG。
     *
     * @param content 二维码内容
     * @return PNG 字节
     */
    public static byte[] toPngBytes(String content) {
        return toPngBytes(content, DEFAULT_SIZE);
    }

    /**
     * 生成二维码 PNG。
     *
     * @param content 二维码内容
     * @param size    边长（像素）
     * @return PNG 字节
     */
    public static byte[] toPngBytes(String content, int size) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("二维码内容不能为空");
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            hints.put(EncodeHintType.ERROR_CORRECTION, ECC);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (WriterException | java.io.IOException e) {
            throw new IllegalStateException("生成二维码失败: " + e.getMessage(), e);
        }
    }
}
