package com.wechat.bot.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.wechat.bot.config.AppConfig;
import com.wechat.bot.intent.IntentRecognizer;
import com.wechat.bot.llm.LlmClient;
import com.wechat.bot.llm.model.ChatMessage;
import com.wechat.bot.tool.ToolRegistry;
import com.wechat.bot.tool.WeatherTool;
import com.wechat.bot.voice.VoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息处理器：机器人核心业务逻辑。
 * <p>处理流程：
 * <ol>
 *   <li>解析入站消息（文本 / 图片 / 语音）</li>
 *   <li>意图识别（规则 + LLM 两级策略）</li>
 *   <li>按意图分发：工具链式调用 / Function Calling 循环 / 文生图 / 语音回复</li>
 *   <li>组装回复并通过 ILinkClient 发送</li>
 * </ol>
 */
public class BotMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(BotMessageHandler.class);
    private static final Pattern CHAIN_CITY = Pattern.compile("#chain\\s*(\\S+)");
    private static final int MAX_HISTORY = 20;

    private final AppConfig config;
    private final LlmClient llmClient;
    private final IntentRecognizer intentRecognizer;
    private final ToolRegistry toolRegistry;
    private final WeatherTool weatherTool;
    private final VoiceService voiceService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 微信客户端（登录后由主程序绑定，因与消息监听器存在相互引用） */
    private volatile ILinkClient wechatClient;

    /** 每用户对话上下文（最近 N 轮） */
    private final Map<String, List<ChatMessage>> chatHistories = new ConcurrentHashMap<>();

    public BotMessageHandler(AppConfig config,
                             LlmClient llmClient,
                             IntentRecognizer intentRecognizer,
                             ToolRegistry toolRegistry,
                             WeatherTool weatherTool,
                             VoiceService voiceService) {
        this.config = config;
        this.llmClient = llmClient;
        this.intentRecognizer = intentRecognizer;
        this.toolRegistry = toolRegistry;
        this.weatherTool = weatherTool;
        this.voiceService = voiceService;
    }

    /**
     * 绑定微信客户端（创建监听器需要 handler，故 client 在 build 后注入）。
     */
    public void bindWechatClient(ILinkClient wechatClient) {
        this.wechatClient = wechatClient;
    }

    /**
     * 处理一批入站消息。
     */
    public void handle(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            if (msg.getItem_list() == null) {
                continue;
            }
            for (MessageItem item : msg.getItem_list()) {
                try {
                    handleItem(msg.getFrom_user_id(), item);
                } catch (Exception e) {
                    log.error("处理消息失败，from={}", msg.getFrom_user_id(), e);
                    safeSendText(msg.getFrom_user_id(), "抱歉，处理你的消息时出错了：" + e.getMessage());
                }
            }
        }
    }

    private void handleItem(String fromUserId, MessageItem item) throws IOException {
        // 1. 语音消息：先识别转写为文本
        if (item.getVoice_item() != null) {
            String transcript = voiceService.recognizeVoice(
                    item, wechatClient::downloadMedia);
            if (transcript == null) {
                safeSendText(fromUserId, "抱歉，这条语音我没能识别出来，请发送文字消息吧～");
                return;
            }
            safeSendText(fromUserId, "听到你说：" + transcript);
            dispatchIntent(fromUserId, transcript, true);
            return;
        }

        // 2. 图片消息：多模态理解
        if (item.getImage_item() != null) {
            handleImageMessage(fromUserId, item);
            return;
        }

        // 3. 文本消息
        if (item.getText_item() != null && item.getText_item().getText() != null) {
            dispatchIntent(fromUserId, item.getText_item().getText(), false);
        }
    }

    /**
     * 意图识别与分发。
     */
    private void dispatchIntent(String fromUserId, String text, boolean fromVoice) {
        IntentRecognizer.Intent intent = intentRecognizer.recognize(text);
        switch (intent) {
            case CHAIN_DEMO -> runChainDemo(fromUserId, text);
            case IMAGE_GEN -> handleImageGeneration(fromUserId, text);
            case VOICE_REPLY -> handleVoiceReply(fromUserId, text);
            default -> handleChatWithTools(fromUserId, text, fromVoice);
        }
    }

    // ---------------- Function Calling 核心循环 ----------------

    /**
     * 带 Function Calling 的对话处理（CHAT/WEATHER/DATETIME 意图统一走此流程）。
     * <p>工作流程：
     * <ol>
     *   <li>将用户消息 + 工具定义发给 LLM</li>
     *   <li>LLM 判断是否需要调用工具：返回 tool_calls 则本地执行工具</li>
     *   <li>工具执行结果以 tool 角色回传，再次请求 LLM</li>
     *   <li>循环直到 LLM 给出最终文本回复（或达到最大轮数）</li>
     * </ol>
     */
    private void handleChatWithTools(String fromUserId, String text, boolean fromVoice) {
        try {
            List<ChatMessage> history = historyOf(fromUserId);
            history.add(ChatMessage.user(text));

            String reply = runFunctionCallingLoop(history);
            history.add(ChatMessage.assistant(reply));
            trimHistory(history);

            // 若开启语音回复且来自语音，则尝试语音回复
            if (fromVoice && voiceService.isReplyEnabled()) {
                boolean sent = voiceService.replyWithVoice(reply,
                        (bytes, playTime, sampleRate) -> wechatClient.sendVoice(
                                fromUserId, bytes, "reply.mp3", playTime, sampleRate));
                if (sent) {
                    return;
                }
            }
            safeSendText(fromUserId, reply);
        } catch (Exception e) {
            log.error("Function Calling 对话失败，from={}", fromUserId, e);
            safeSendText(fromUserId, "抱歉，我暂时无法回复：" + e.getMessage());
        }
    }

    /**
     * Function Calling 多轮循环：支持多步工具调用，
     * 后续步骤可基于前一步工具结果继续调用（链式）。
     */
    private String runFunctionCallingLoop(List<ChatMessage> history) throws IOException {
        for (int round = 1; round <= config.maxToolRounds(); round++) {
            LlmClient.ChatResult result = llmClient.chatWithTools(
                    history, toolRegistry.toToolDefinitions());

            if (!result.hasToolCalls()) {
                // 模型给出最终回答
                return result.content() == null || result.content().isBlank()
                        ? "（我暂时不知道怎么回答）" : result.content();
            }

            // 模型请求调用工具：先记录 assistant 的工具调用消息
            history.add(ChatMessage.assistantToolCalls(result.toolCalls()));

            for (var call : result.toolCalls()) {
                log.info("第 {} 轮工具调用：{}({})", round, call.name(), call.arguments());
                JsonNode args = parseArguments(call.arguments());
                String toolResult = toolRegistry.execute(call.name(), args);
                log.info("工具 {} 返回：{}", call.name(),
                        toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult);
                // 工具结果回传，供下一轮模型使用
                history.add(ChatMessage.toolResult(call.getId(), call.name(), toolResult));
            }
            // 进入下一轮：模型基于工具结果继续推理或再次调用工具
        }
        return "这个问题需要太多步骤了，我最多支持 " + config.maxToolRounds() + " 轮工具调用～";
    }

    // ---------------- 多步链式调用演示（显式流程） ----------------

    /**
     * 多步工具链式调用演示：#chain 城市名。
     * <p>明确的三个步骤，后一步的输入依赖前一步的输出：
     * <ol>
     *   <li>步骤1：WeatherTool 地理编码 —— 城市名 → 经纬度</li>
     *   <li>步骤2：WeatherTool 天气查询 —— 经纬度 → 天气数据（依赖步骤1）</li>
     *   <li>步骤3：LLM 总结 —— 天气数据 → 穿衣建议（依赖步骤2）</li>
     * </ol>
     */
    private void runChainDemo(String fromUserId, String text) {
        Matcher m = CHAIN_CITY.matcher(text);
        if (!m.find()) {
            safeSendText(fromUserId, "用法：#chain 城市名\n例如：#chain 北京\n（演示多步工具链式调用：城市→坐标→天气→建议）");
            return;
        }
        String city = m.group(1);

        try {
            safeSendText(fromUserId, "▶ 开始多步链式调用演示（3 步）\n步骤1：查询城市「" + city + "」的坐标...");

            // 步骤1+2：城市 → 坐标 → 天气（WeatherTool 内部链式）
            var weather = weatherTool.chainExecute(city);
            if (weather.has("error")) {
                safeSendText(fromUserId, "❌ 链式调用失败：" + weather.path("error").asText());
                return;
            }
            String cityResolved = weather.path("city").asText();
            safeSendText(fromUserId, "步骤1 完成：坐标已获取（" + cityResolved + "）\n"
                    + "步骤2：根据坐标查询天气...");
            String weatherBrief = weather.path("city").asText() + "当前"
                    + weather.path("weather").asText() + "，气温 "
                    + weather.path("temperature").asDouble() + "℃";
            safeSendText(fromUserId, "步骤2 完成：" + weatherBrief + "\n"
                    + "步骤3：基于天气结果生成建议...");

            // 步骤3：天气数据 → LLM 生成建议（依赖步骤2 结果）
            String advice = llmClient.chat(List.of(
                    ChatMessage.system("你是贴心的生活助手，根据天气用一句话给出穿衣/出行建议，50字以内。"),
                    ChatMessage.user("当前天气：" + weatherBrief)));
            safeSendText(fromUserId, "步骤3 完成 ✅\n最终建议：" + advice);
            log.info("链式调用演示完成：{} -> {} -> 建议", city, weatherBrief);
        } catch (Exception e) {
            log.error("链式调用演示失败", e);
            safeSendText(fromUserId, "链式调用演示失败：" + e.getMessage());
        }
    }

    // ---------------- 图片处理 ----------------

    /**
     * 图片消息：下载后调用多模态模型理解图片内容。
     */
    private void handleImageMessage(String fromUserId, MessageItem item) {
        try {
            byte[] imageBytes = wechatClient.downloadImageFromMessageItem(item);
            String dataUrl = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);
            String description = llmClient.describeImage("请用中文描述这张图片的内容", dataUrl);
            safeSendText(fromUserId, "我看到：" + description);
        } catch (Exception e) {
            log.error("图片理解失败，from={}", fromUserId, e);
            safeSendText(fromUserId, "这张图片我暂时看不懂：" + e.getMessage());
        }
    }

    /**
     * 文生图意图：提取绘画描述并生成图片发送。
     */
    private void handleImageGeneration(String fromUserId, String text) {
        try {
            String prompt = text.replaceFirst("帮我画|画一张|画一个|画个|画张|生成一张|生成一个", "")
                    .replace("的图|的图片|图片", "").trim();
            if (prompt.isBlank()) {
                prompt = text;
            }
            log.info("开始文生图：{}", prompt);
            byte[] image = llmClient.generateImage(prompt);
            wechatClient.sendImage(fromUserId, image, "ai-image.png", "为你画：「" + prompt + "」");
        } catch (Exception e) {
            log.error("文生图失败，from={}", fromUserId, e);
            safeSendText(fromUserId, "图片生成失败：" + e.getMessage());
        }
    }

    /**
     * 语音回复意图：LLM 生成文本后用 TTS 语音发送。
     */
    private void handleVoiceReply(String fromUserId, String text) {
        try {
            String content = text.replaceFirst("用语音|语音说|语音回复|发语音", "").trim();
            if (content.isBlank()) {
                content = "你好呀，我是智能小助手";
            }
            String reply = llmClient.chat(List.of(
                    ChatMessage.system("你是一个友好的微信机器人助手，回复简洁口语化，80字以内。"),
                    ChatMessage.user(content)));

            boolean sent = voiceService.replyWithVoice(reply,
                    (bytes, playTime, sampleRate) -> wechatClient.sendVoice(
                            fromUserId, bytes, "reply.mp3", playTime, sampleRate));
            if (!sent) {
                safeSendText(fromUserId, "（语音功能未开启或不可用，文字回复）" + reply);
            }
        } catch (Exception e) {
            log.error("语音回复失败，from={}", fromUserId, e);
            safeSendText(fromUserId, "语音回复失败：" + e.getMessage());
        }
    }

    // ---------------- 辅助方法 ----------------

    private JsonNode parseArguments(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (IOException e) {
            log.warn("工具参数 JSON 解析失败：{}", json);
            return mapper.createObjectNode();
        }
    }

    private List<ChatMessage> historyOf(String userId) {
        return chatHistories.computeIfAbsent(userId, k -> {
            List<ChatMessage> list = new ArrayList<>();
            list.add(ChatMessage.system("""
                    你是微信群里的智能小助手，回复简洁友好、口语化。
                    可以调用工具查询天气和时间等实时信息；拿到工具结果后请用自然语言总结回答。"""));
            return list;
        });
    }

    private void trimHistory(List<ChatMessage> history) {
        while (history.size() > MAX_HISTORY + 1) {
            history.remove(1);
        }
    }

    private void safeSendText(String userId, String text) {
        try {
            wechatClient.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送文本消息失败，to={}", userId, e);
        }
    }
}
