package com.github.wechat.ilink.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 展示 SDK 登录二维码，避免在日志中输出含凭据的原始内容。 */
final class QrCodePresenter {
  private static final Logger log = LoggerFactory.getLogger(QrCodePresenter.class);

  private QrCodePresenter() {}

  static void present(String qrCodeContent) throws IOException {
    if (qrCodeContent == null || qrCodeContent.isBlank()) {
      throw new IOException("iLink did not return QR code content");
    }
    if (qrCodeContent.startsWith("http://") || qrCodeContent.startsWith("https://")) {
      log.info("Open this local login QR code URL in a browser: {}", qrCodeContent);
      return;
    }
    String base64 = qrCodeContent;
    int separator = qrCodeContent.indexOf(",");
    if (qrCodeContent.startsWith("data:image/") && separator >= 0) {
      base64 = qrCodeContent.substring(separator + 1);
    }
    try {
      Path directory = Path.of("runtime");
      Files.createDirectories(directory);
      Path output = directory.resolve("ilink-login-qrcode.png");
      Files.write(output, Base64.getMimeDecoder().decode(base64));
      log.info("Login QR image written locally to {}", output.toAbsolutePath());
    } catch (IllegalArgumentException e) {
      throw new IOException("Unsupported iLink QR code content format", e);
    }
  }
}
