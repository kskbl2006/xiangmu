package com.wechat.bot.voice;

import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.wechat.bot.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 语音服务：负责入站语音识别与出站语音回复。
 * <p>入站识别：微信服务端会对语音消息自动转写（VoiceItem.text），
 * 本服务优先读取转写文本；转写缺失时通过媒体下载器下载语音数据留作后续 ASR 处理。
 * <p>出站回复：调用 LLM TTS 接口将文本合成语音并发送；失败时自动降级为文字回复。
 */
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final LlmClient llmClient;
    private final boolean replyEnabled;

    public VoiceService(LlmClient llmClient, boolean replyEnabled) {
        this.llmClient = llmClient;
        this.replyEnabled = replyEnabled;
    }

    /**
     * 识别入站语音消息，返回转写文本。
     *
     * @param item            语音消息项
     * @param mediaDownloader 媒体下载器（由调用方绑定已登录的 ILinkClient）
     * @return 转写文本；无法识别时返回 null
     */
    public String recognizeVoice(MessageItem item, MediaDownloader mediaDownloader) {
        VoiceItem voiceItem = item.getVoice_item();
        if (voiceItem == null) {
            return null;
        }

        // 微信服务端转写结果优先
        String transcript = voiceItem.getText();
        if (transcript != null && !transcript.isBlank()) {
            log.info("语音识别（服务端转写）：{}", transcript);
            return transcript;
        }

        // 转写缺失时下载语音数据（可在此接入自研/第三方 ASR）
        try {
            if (voiceItem.getMedia() != null && mediaDownloader != null) {
                byte[] data = mediaDownloader.download(voiceItem.getMedia());
                log.info("语音转写缺失，已下载语音数据 {} 字节（时长 {} ms，采样率 {}）",
                        data.length, voiceItem.getPlaytime(), voiceItem.getSample_rate());
            }
        } catch (Exception e) {
            log.warn("下载语音数据失败：{}", e.getMessage());
        }
        log.warn("语音消息无转写文本，暂无法识别");
        return null;
    }

    /**
     * 是否启用语音回复。
     */
    public boolean isReplyEnabled() {
        return replyEnabled;
    }

    /**
     * 合成语音并交给发送器发送；失败返回 false 由上层降级为文字。
     *
     * @param text       待合成文本
     * @param sendVoice  实际发送函数（(voiceBytes, playTimeMs, sampleRate) -> void）
     */
    public boolean replyWithVoice(String text, VoiceSender sendVoice) {
        if (!replyEnabled) {
            return false;
        }
        try {
            byte[] audio = llmClient.textToSpeech(text);
            // 按中文语速 ~4 字/秒估算播放时长
            int playTimeMs = Math.max(1000, text.length() * 250);
            sendVoice.send(audio, playTimeMs, 16000);
            log.info("语音回复已发送，文本长度 {}，音频 {} 字节", text.length(), audio.length);
            return true;
        } catch (Exception e) {
            log.error("语音合成失败，将降级为文字回复：{}", e.getMessage());
            return false;
        }
    }

    /** 媒体下载函数接口（绑定已登录的 ILinkClient） */
    @FunctionalInterface
    public interface MediaDownloader {
        byte[] download(CDNMedia media) throws IOException;
    }

    /** 语音发送函数接口 */
    @FunctionalInterface
    public interface VoiceSender {
        void send(byte[] voiceBytes, int playTimeMs, int sampleRate) throws Exception;
    }
}
