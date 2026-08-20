package com.wechat.bot.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码终端渲染工具：将扫码登录链接渲染为控制台字符二维码。
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    /**
     * 将内容渲染为终端可扫的字符二维码。
     *
     * @param content 二维码内容（扫码登录链接）
     * @return 多行字符串二维码
     */
    public static String render(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, 33, 33, hints);

            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    // 使用 Unicode 半块字符提高扫码识别率
                    sb.append(matrix.get(x, y) ? "██" : "  ");
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // 渲染失败时返回原始内容，用户可自行用工具生成二维码
            return "二维码渲染失败，请使用以下内容自行生成二维码扫码登录：\n" + content;
        }
    }
}
