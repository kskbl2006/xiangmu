package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.speech.AudioConverter;
import com.github.wechat.ilink.bot.speech.QwenTtsClient;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated TTS and local audio-conversion check; it does not contact WeChat. */
public final class SpeechSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(SpeechSmokeTest.class);

  private SpeechSmokeTest() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    QwenTtsClient.Audio audio =
        new QwenTtsClient(config).synthesize("语音模块测试成功。");
    byte[] mp3 = new AudioConverter(Path.of("runtime", "audio")).toMp3(audio);
    log.info("Speech smoke test succeeded; generated MP3 bytes={}", mp3.length);
  }
}
