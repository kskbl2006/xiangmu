package com.github.wechat.ilink.bot.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.CnTripPlannerSkill;
import com.github.wechat.ilink.bot.agent.QwenCityResolver;
import com.github.wechat.ilink.bot.agent.TravelAgentService;
import com.github.wechat.ilink.bot.agent.TravelBriefSkill;
import com.github.wechat.ilink.bot.agent.TravelPlanReviewSkill;
import com.github.wechat.ilink.bot.agent.TravelPdfRenderer;
import com.github.wechat.ilink.bot.agent.TravelTaskStore;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.intent.IntentRecognizer;
import com.github.wechat.ilink.bot.intent.IntentRecognizer.Intent;
import com.github.wechat.ilink.bot.llm.QwenClient;
import com.github.wechat.ilink.bot.maps.BaiduMapClient;
import com.github.wechat.ilink.bot.maps.TravelMapService;
import com.github.wechat.ilink.bot.memory.ConversationMemoryStore;
import com.github.wechat.ilink.bot.speech.AudioConverter;
import com.github.wechat.ilink.bot.speech.QwenAsrClient;
import com.github.wechat.ilink.bot.speech.QwenTtsClient;
import com.github.wechat.ilink.bot.tool.CalculatorTool;
import com.github.wechat.ilink.bot.tool.DateTimeTool;
import com.github.wechat.ilink.bot.tool.ToolRegistry;
import com.github.wechat.ilink.bot.tool.WeatherTool;
import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelRagService;
import com.github.wechat.ilink.bot.weather.Weather;
import com.github.wechat.ilink.bot.weather.OpenMeteoWeather;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes iLink messages without blocking the long-poll thread on LLM and media work. */
public final class WeChatMessageRouter implements OnMessageListener, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(WeChatMessageRouter.class);
  private static final long ATTACHMENT_WAIT_SECONDS = 3L;

  private final AtomicReference<ILinkClient> clientRef;
  private final AppConfig config;
  private final QwenClient qwenClient;
  private final QwenAsrClient asrClient;
  private final QwenTtsClient ttsClient;
  private final AudioConverter audioConverter = new AudioConverter(Path.of("runtime", "audio"));
  private final IntentRecognizer intentRecognizer = new IntentRecognizer();
  private final ToolRegistry toolRegistry;
  private final TravelRagService travelRagService;
  private final TravelAgentService travelAgentService;
  private final TravelPdfRenderer travelPdfRenderer;
  private final TravelTaskStore travelTasks =
      new TravelTaskStore(Path.of("runtime", "travel-tasks.json"));
  private final ConversationMemoryStore memory;
  private final MessageDeduplicator deduplicator =
      new MessageDeduplicator(Duration.ofMinutes(5), 2_000);
  private final PerUserTaskExecutor tasks = new PerUserTaskExecutor();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().name("wechat-attachment-buffer").daemon(true).unstarted(runnable));
  private final ConcurrentHashMap<String, PendingImage> pendingImages = new ConcurrentHashMap<>();

  public WeChatMessageRouter(
      AtomicReference<ILinkClient> clientRef,
      AppConfig config,
      ConversationMemoryStore memory) {
    this.clientRef = clientRef;
    this.config = config;
    this.memory = memory;
    this.qwenClient = new QwenClient(config);
    this.asrClient = new QwenAsrClient(config);
    this.ttsClient = new QwenTtsClient(config);
    ObjectMapper objectMapper = new ObjectMapper();
    Weather weather = new Weather(config);
    OpenMeteoWeather travelWeather = new OpenMeteoWeather(config);
    TravelMapService travelMapService =
        new TravelMapService(new BaiduMapClient(config), config.getBaiduMapMaxPoiQueries());
    this.toolRegistry =
        new ToolRegistry(
            objectMapper,
            List.of(
                new WeatherTool(weather, objectMapper),
                new CalculatorTool(objectMapper),
                new DateTimeTool(objectMapper)));
    this.travelRagService = TravelRagService.fromBundledIndex(config, objectMapper);
    this.travelAgentService =
        new TravelAgentService(
            new TravelBriefSkill(new QwenCityResolver(qwenClient)),
            new CnTripPlannerSkill(qwenClient),
            new TravelPlanReviewSkill(),
            travelRagService::retrieveAttractionsForPlanning,
            travelWeather::forecast,
            travelMapService::enrich);
    this.travelPdfRenderer = new TravelPdfRenderer(config);
    log.info("Initialized tool calling: tools={}", toolRegistry.names());
    log.info(
        "Initialized travel RAG: enabled={}, chunks={}, model={}",
        config.isTravelRagEnabled(),
        travelRagService.size(),
        travelRagService.model());
    log.info("Initialized Baidu Map enrichment: enabled={}", config.hasBaiduMapApiKey());
  }

  @Override
  public void onMessages(List<WeixinMessage> messages) {
    for (WeixinMessage message : messages) {
      route(message);
    }
  }

  private void route(WeixinMessage message) {
    String sender = message.getFrom_user_id();
    if (!isReplyableIncomingMessage(sender) || deduplicator.isDuplicate(message.getMessage_id())) {
      return;
    }
    String text = readText(message);
    MessageItem image = findImage(message);
    MessageItem voice = findVoice(message);
    log.info(
        "Received WeChat message: messageId={}, fromUserId={}, type={}",
        message.getMessage_id(),
        sender,
        text != null ? "text" : image != null ? "image" : voice != null ? "voice" : "unsupported");

    if (image != null) {
      bufferImage(sender, image);
      return;
    }
    if (voice != null) {
      tasks.submit(sender, () -> replyToVoice(sender, voice));
      return;
    }
    if (text != null && !text.isBlank()) {
      PendingImage pending = pendingImages.remove(sender);
      if (pending != null) {
        pending.flushTask().cancel(false);
        tasks.submit(sender, () -> replyToImage(sender, pending.item(), text));
      } else {
        tasks.submit(sender, () -> replyToText(sender, text));
      }
    }
  }

  private void bufferImage(String sender, MessageItem image) {
    ScheduledFuture<?> flush =
        scheduler.schedule(
            () -> {
              PendingImage pending = pendingImages.remove(sender);
              if (pending != null) {
                tasks.submit(sender, () -> replyToImage(sender, pending.item(), null));
              }
            },
            ATTACHMENT_WAIT_SECONDS,
            TimeUnit.SECONDS);
    PendingImage previous = pendingImages.put(sender, new PendingImage(image, flush));
    if (previous != null) {
      previous.flushTask().cancel(false);
      tasks.submit(sender, () -> replyToImage(sender, previous.item(), null));
    }
    log.info("Buffered image for userId={} for up to {} seconds", sender, ATTACHMENT_WAIT_SECONDS);
  }

  private void replyToText(String sender, String text) {
    try {
      ILinkClient client = requireClient();
      IntentRecognizer.Result recognized = intentRecognizer.recognize(text);
      if (recognized.intent() == Intent.CLEAR_MEMORY) {
        memory.clear(sender);
        client.sendText(sender, "已清除当前会话的短期记忆。");
        return;
      }
      String travelGoal =
          travelAgentService.supports(text)
              ? text
              : travelTasks.resolveGoal(sender, text).orElse(null);
      if (travelGoal != null) {
        travelTasks.progress(sender, travelGoal, "已接收");
        TravelAgentService.Result result;
        try {
          result =
              travelAgentService.execute(
                  travelGoal, stage -> travelTasks.progress(sender, travelGoal, stage));
        } catch (Exception e) {
          travelTasks.progress(sender, travelGoal, "执行失败，可发送“继续上次任务”重试");
          throw e;
        }
        client.sendText(sender, result.reply());
        sendTravelArtifacts(client, sender, result);
        storeTravelResult(sender, travelGoal, result);
        memory.addTurn(sender, text, result.reply());
        logRoute(sender, "travel-artifacts");
        return;
      }
      String reply = generateReply(sender, text);
      client.sendText(sender, reply);
      memory.addTurn(sender, text, reply);
      logRoute(sender, "text");
    } catch (Exception e) {
      log.error("Failed to process text message for userId={}: {}", sender, e.getMessage(), e);
      sendFailure(sender, "消息处理失败，请稍后重试。");
    }
  }

  private void replyToImage(String sender, MessageItem image, String textPrompt) {
    try {
      ILinkClient client = requireClient();
      String unavailableReply = unavailableLlmReply(config);
      if (unavailableReply != null) {
        client.sendText(sender, unavailableReply);
        return;
      }
      long downloadStartedAt = System.nanoTime();
      byte[] imageBytes = client.downloadImageFromMessageItem(image);
      String mimeType = detectImageMimeType(imageBytes);
      log.info(
          "Downloaded WeChat image: bytes={}, mimeType={}, elapsedMs={}",
          imageBytes.length,
          mimeType,
          elapsedMillis(downloadStartedAt));
      String prompt =
          textPrompt == null || textPrompt.isBlank()
              ? "请简洁描述图片内容。若图片包含清晰文字，请准确提取；若文字模糊，请明确说明。"
              : textPrompt;
      long visionStartedAt = System.nanoTime();
      String reply =
          qwenClient.chatWithImage(
              memory.getRecentTurns(sender), imageBytes, mimeType, prompt);
      client.sendText(sender, reply);
      memory.addTurn(sender, "[图片] " + prompt, reply);
      log.info("Sent vision reply to userId={}; modelElapsedMs={}", sender, elapsedMillis(visionStartedAt));
    } catch (Exception e) {
      log.error("Failed to process image for userId={}: {}", sender, e.getMessage(), e);
      sendFailure(sender, "图片处理失败，请稍后重试或换一张较小的 JPG/PNG 图片。");
    }
  }

  private void replyToVoice(String sender, MessageItem voiceMessage) {
    try {
      ILinkClient client = requireClient();
      String unavailableReply = unavailableLlmReply(config);
      if (unavailableReply != null) {
        client.sendText(sender, unavailableReply);
        return;
      }
      VoiceItem voice = voiceMessage.getVoice_item();
      String transcript = voice.getText();
      String transcriptSource = "WeChat";
      if (transcript == null || transcript.isBlank()) {
        String mimeType = voiceMimeType(voice.getEncode_type());
        if (mimeType == null) {
          client.sendText(
              sender,
              "已收到语音，但当前消息没有微信转写，且音频为 Silk 格式。请稍后重试或暂时发送文字。");
          log.warn("Voice has no transcript and unsupported encodeType={}", voice.getEncode_type());
          return;
        }
        byte[] audio = client.downloadVoiceFromMessageItem(voiceMessage);
        transcript = asrClient.transcribe(audio, mimeType);
        transcriptSource = config.getQwenAsrModel();
      }
      transcript = transcript.trim();
      log.info("Recognized voice for userId={} via {}: chars={}", sender, transcriptSource, transcript.length());

      IntentRecognizer.Result recognized = intentRecognizer.recognize(transcript);
      String reply;
      TravelAgentService.Result travelResult = null;
      if (recognized.intent() == Intent.CLEAR_MEMORY) {
        memory.clear(sender);
        reply = "已清除当前会话的短期记忆。";
      } else {
        String travelGoal =
            travelAgentService.supports(transcript)
                ? transcript
                : travelTasks.resolveGoal(sender, transcript).orElse(null);
        if (travelGoal != null) {
          travelTasks.progress(sender, travelGoal, "已接收");
          try {
            travelResult =
                travelAgentService.execute(
                    travelGoal, stage -> travelTasks.progress(sender, travelGoal, stage));
          } catch (Exception e) {
            travelTasks.progress(sender, travelGoal, "执行失败，可发送“继续上次任务”重试");
            throw e;
          }
          storeTravelResult(sender, travelGoal, travelResult);
          reply = travelResult.reply();
        } else {
          reply = generateReply(sender, transcript);
        }
      }
      client.sendText(sender, "语音识别：" + transcript + "\n\n" + reply);
      if (travelResult != null) sendTravelArtifacts(client, sender, travelResult);
      memory.addTurn(sender, "[语音] " + transcript, reply);
      logRoute(sender, "voice");

      QwenTtsClient.Audio generated = ttsClient.synthesize(textForSpeech(reply));
      byte[] mp3 = audioConverter.toMp3(generated);
      client.sendFile(sender, mp3, "语音回复.mp3", null);
      log.info("Sent TTS MP3 reply to userId={}; bytes={}", sender, mp3.length);
    } catch (Exception e) {
      log.error("Failed to process voice for userId={}: {}", sender, e.getMessage(), e);
      sendFailure(sender, "语音处理失败，请稍后重试或暂时发送文字。");
    }
  }

  private String generateReply(String sender, String userMessage) throws Exception {
    String unavailableReply = unavailableLlmReply(config);
    if (unavailableReply != null) {
      return unavailableReply;
    }
    List<InMemoryTravelVectorStore.Hit> travelHits = travelRagService.retrieve(userMessage);
    String modelInput = TravelRagService.enhancePrompt(userMessage, travelHits);
    return qwenClient
        .chatWithTools(memory.getRecentTurns(sender), modelInput, toolRegistry)
        .answer();
  }

  private void storeTravelResult(
      String sender, String travelGoal, TravelAgentService.Result result) {
    if (result.plan() != null) {
      travelTasks.complete(sender, result.plan().brief());
      return;
    }
    switch (result.missingInput()) {
      case ORIGIN -> travelTasks.progress(sender, travelGoal, "等待补充出发地");
      case DESTINATION -> travelTasks.progress(sender, travelGoal, "等待补充目的地");
      case NONE -> travelTasks.progress(sender, travelGoal, "执行未完成");
    }
  }

  private void sendTravelArtifacts(
      ILinkClient client, String sender, TravelAgentService.Result result) throws Exception {
    if (result.markdownDocument() == null) return;
    client.sendFile(
        sender,
        result.markdownDocument().getBytes(StandardCharsets.UTF_8),
        result.fileName(),
        null);
    try {
      byte[] pdf = travelPdfRenderer.render(result.markdownDocument());
      String pdfName = result.fileName().replaceFirst("\\.md$", ".pdf");
      client.sendFile(sender, pdf, pdfName, null);
    } catch (Exception e) {
      log.warn("Unable to render travel PDF for userId={}: {}", sender, e.getMessage());
    }
  }

  static String unavailableLlmReply(AppConfig config) {
    return unavailableLlmReply(
        config.hasDashscopeApiKey(),
        config.isWeChatLlmReplyEnabled(),
        config.getWeChatMissingApiReplyText(),
        config.getWeChatAutoReplyText());
  }

  static String unavailableLlmReply(
      boolean apiConfigured,
      boolean llmReplyEnabled,
      String missingApiReply,
      String disabledReply) {
    if (!apiConfigured) return missingApiReply;
    return llmReplyEnabled ? null : disabledReply;
  }

  private void logRoute(String sender, String messageType) {
    log.info("Sent {} reply to userId={}; route=TOOLS_OR_LLM", messageType, sender);
  }

  private void sendFailure(String sender, String message) {
    try {
      ILinkClient client = clientRef.get();
      if (client != null && client.getLoginContext() != null) {
        client.sendText(sender, message);
      }
    } catch (Exception sendError) {
      log.error("Failed to send error notice to userId={}: {}", sender, sendError.getMessage());
    }
  }

  private ILinkClient requireClient() {
    ILinkClient client = clientRef.get();
    if (client == null || client.getLoginContext() == null) {
      throw new IllegalStateException("WeChat client is not logged in");
    }
    return client;
  }

  private boolean isReplyableIncomingMessage(String sender) {
    if (!config.isWeChatAutoReplyEnabled() || sender == null || sender.isBlank()) {
      return false;
    }
    ILinkClient client = clientRef.get();
    if (client == null || client.getLoginContext() == null) {
      return false;
    }
    return !sender.equals(client.getLoginContext().getBotId());
  }

  static String readText(WeixinMessage message) {
    if (message.getItem_list() == null) {
      return null;
    }
    for (MessageItem item : message.getItem_list()) {
      if (item.getText_item() != null) {
        return item.getText_item().getText();
      }
    }
    return null;
  }

  private static MessageItem findImage(WeixinMessage message) {
    if (message.getItem_list() == null) {
      return null;
    }
    for (MessageItem item : message.getItem_list()) {
      if (item.getImage_item() != null) {
        return item;
      }
    }
    return null;
  }

  private static MessageItem findVoice(WeixinMessage message) {
    if (message.getItem_list() == null) {
      return null;
    }
    for (MessageItem item : message.getItem_list()) {
      if (item.getVoice_item() != null) {
        return item;
      }
    }
    return null;
  }

  private static String voiceMimeType(Integer encodeType) {
    if (encodeType == null) return null;
    return switch (encodeType) {
      case 5 -> "audio/amr";
      case 7 -> "audio/mpeg";
      case 8 -> "audio/ogg";
      default -> null;
    };
  }

  private static String textForSpeech(String reply) {
    String plain = reply.replaceAll("[`*_#>]", "").replaceAll("\\s+", " ").trim();
    return plain.length() <= 500 ? plain : plain.substring(0, 500);
  }

  static String detectImageMimeType(byte[] bytes) {
    if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
    if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
    if (startsWith(bytes, 'G', 'I', 'F', '8')) return "image/gif";
    if (bytes.length >= 12
        && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
      return "image/webp";
    }
    log.warn("Unknown image format; submitting it as JPEG to the vision model");
    return "image/jpeg";
  }

  private static boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) return false;
    for (int index = 0; index < prefix.length; index++) {
      if ((bytes[index] & 0xFF) != prefix[index]) return false;
    }
    return true;
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }

  @Override
  public void close() {
    for (PendingImage pending : pendingImages.values()) {
      pending.flushTask().cancel(false);
    }
    scheduler.close();
    tasks.close();
  }

  private record PendingImage(MessageItem item, ScheduledFuture<?> flushTask) {}

}
