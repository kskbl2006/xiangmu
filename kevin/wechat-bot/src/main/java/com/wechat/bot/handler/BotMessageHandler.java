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
import com.wechat.bot.llm.model.ToolCall;
import com.wechat.bot.rag.RagService;
import com.wechat.bot.skill.Skill;
import com.wechat.bot.skill.SkillRegistry;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息处理器：机器人核心业务逻辑。
 * <p>处理流程（三级消息路由 Skill → RAG → LLM 兜底）：
 * <ol>
 *   <li>解析入站消息（文本 / 图片 / 语音）</li>
 *   <li>第一级 Skill 关键词：命中即本地执行直接回复（零 LLM 调用）</li>
 *   <li>原有特殊意图保留：链式/并行演示、文生图、语音回复</li>
 *   <li>第二级 RAG 关键词：知识库检索命中则增强 Prompt 再交 LLM 回答</li>
 *   <li>第三级 LLM 兜底：Function Calling 循环 + 多轮上下文闲聊</li>
 *   <li>组装回复并通过 ILinkClient 发送</li>
 * </ol>
 */
public class BotMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(BotMessageHandler.class);
    private static final Pattern CHAIN_CITY = Pattern.compile("#chain\\s*(\\S+)");
    private static final Pattern MULTI_CITIES = Pattern.compile("#multi\\s+(.+)");
    private static final int MAX_HISTORY = 20;

    /** LLM 兜底闲聊的基础系统提示词（RAG 命中时在其后追加知识库资料） */
    private static final String BASE_SYSTEM_PROMPT = """
            你是微信群里的智能小助手，回复简洁友好、口语化。
            可以调用工具查询天气和时间等实时信息；拿到工具结果后请用自然语言总结回答。""";

    /** 工具并行执行线程池：JDK21 虚拟线程，每任务一线程，轻量高效 */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final AppConfig config;
    private final LlmClient llmClient;
    private final IntentRecognizer intentRecognizer;
    private final ToolRegistry toolRegistry;
    private final WeatherTool weatherTool;
    private final VoiceService voiceService;
    private final SkillRegistry skillRegistry;
    private final RagService ragService;
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
                             VoiceService voiceService,
                             SkillRegistry skillRegistry,
                             RagService ragService) {
        this.config = config;
        this.llmClient = llmClient;
        this.intentRecognizer = intentRecognizer;
        this.toolRegistry = toolRegistry;
        this.weatherTool = weatherTool;
        this.voiceService = voiceService;
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
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
     * 三级消息路由：Skill 关键词 → RAG 关键词 → LLM 兜底。
     * <ol>
     *   <li>Skill 层：本地关键词命中即执行，直接回复（不调用 LLM）</li>
     *   <li>特殊意图层：保留原有 #chain / #multi 演示、文生图、语音回复路由</li>
     *   <li>RAG 层：知识库检索命中 → 增强 Prompt → LLM 基于资料回答</li>
     *   <li>LLM 兜底层：普通闲聊 + Function Calling（天气/时间等工具仍可用）</li>
     * </ol>
     */
    private void dispatchIntent(String fromUserId, String text, boolean fromVoice) {
        // ---- 第一级：Skill 关键词直达 ----
        Optional<Skill> matchedSkill = skillRegistry.match(text);
        if (matchedSkill.isPresent()) {
            log.info("[路由] Skill 层命中：{}", matchedSkill.get().name());
            safeSendText(fromUserId, skillRegistry.execute(matchedSkill.get(), fromUserId, text));
            return;
        }

        // ---- 特殊意图（原有演示能力保留） ----
        IntentRecognizer.Intent intent = intentRecognizer.recognize(text);
        switch (intent) {
            case CHAIN_DEMO -> runChainDemo(fromUserId, text);
            case MULTI_DEMO -> runMultiDemo(fromUserId, text);
            case IMAGE_GEN -> handleImageGeneration(fromUserId, text);
            case VOICE_REPLY -> handleVoiceReply(fromUserId, text);
            default -> {
                // ---- 第二级：RAG 关键词命中？ ----
                boolean ragHit = ragService.hit(text);
                // ---- 第三级：LLM 兜底（RAG 命中时增强 Prompt） ----
                log.info("[路由] {} 层处理", ragHit ? "RAG 增强 + LLM" : "LLM 兜底");
                handleChatWithTools(fromUserId, text, fromVoice, ragHit);
            }
        }
    }

    // ---------------- Function Calling 核心循环 ----------------

    /**
     * 带 Function Calling 的对话处理（RAG 命中与 LLM 兜底统一走此流程）。
     * <p>工作流程：
     * <ol>
     *   <li>RAG 命中时：检索知识库 Top-K 文档，增强系统 Prompt（资料注入）</li>
     *   <li>将用户消息 + 工具定义发给 LLM</li>
     *   <li>LLM 判断是否需要调用工具：返回 tool_calls 则本地执行工具</li>
     *   <li>工具执行结果以 tool 角色回传，再次请求 LLM</li>
     *   <li>循环直到 LLM 给出最终文本回复（或达到最大轮数）</li>
     * </ol>
     */
    private void handleChatWithTools(String fromUserId, String text, boolean fromVoice, boolean ragHit) {
        try {
            List<ChatMessage> history = historyOf(fromUserId);
            // RAG 增强：命中时把知识库资料拼进系统 Prompt（本轮生效，下一轮按需重建）
            history.get(0).setContent(ragHit
                    ? ragService.augmentSystemPrompt(BASE_SYSTEM_PROMPT, text)
                    : BASE_SYSTEM_PROMPT);
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
     * Function Calling 多轮循环：支持多步工具调用，兼容串行与并行两种协作模式。
     * <ul>
     *   <li>串行（轮次间依赖）：上一轮工具结果回传后，下一轮模型基于结果继续调用，
     *       形成 A→B→C 的链式调用</li>
     *   <li>并行（轮内并发）：同一轮模型返回的多个 tool_calls 之间无数据依赖，
     *       使用 JDK21 虚拟线程并行执行，结果按原顺序回传</li>
     * </ul>
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

            List<ToolCall> calls = result.toolCalls();
            long start = System.currentTimeMillis();
            if (calls.size() == 1) {
                // 单工具调用：直接串行执行
                ToolCall call = calls.get(0);
                log.info("第 {} 轮工具调用（串行）：{}({})", round, call.name(), call.arguments());
                String toolResult = toolRegistry.execute(call.name(), parseArguments(call.arguments()));
                history.add(ChatMessage.toolResult(call.getId(), call.name(), toolResult));
            } else {
                // 多工具调用：并行执行（虚拟线程），结果按原顺序回传
                log.info("第 {} 轮模型请求 {} 个工具，开始并行执行：{}", round, calls.size(),
                        calls.stream().map(ToolCall::name).toList());
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (ToolCall call : calls) {
                    JsonNode args = parseArguments(call.arguments());
                    futures.add(CompletableFuture.supplyAsync(
                            () -> toolRegistry.execute(call.name(), args), TOOL_EXECUTOR));
                }
                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    String toolResult = futures.get(i).join();
                    log.info("并行工具 {} 完成，返回：{}", call.name(),
                            toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult);
                    // 工具结果回传，供下一轮模型使用
                    history.add(ChatMessage.toolResult(call.getId(), call.name(), toolResult));
                }
                log.info("第 {} 轮 {} 个工具并行执行完成，总耗时 {} ms",
                        round, calls.size(), System.currentTimeMillis() - start);
            }
            // 进入下一轮：模型基于工具结果继续推理或再次调用工具（串行链）
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

    /**
     * 多工具协作演示（并行模式）：#multi 城市1 城市2 ...
     * <p>与 #chain 的串行模式相对，本命令演示多工具并行协作：
     * <ol>
     *   <li>并行阶段：多个 WeatherTool（每城市一个）+ DateTimeTool 同时执行，
     *       各任务之间无数据依赖，虚拟线程并发跑满</li>
     *   <li>汇总阶段（串行依赖并行结果）：所有工具完成后，结果统一交给 LLM
     *       生成比较结论（如"哪个城市更热"）</li>
     * </ol>
     */
    private void runMultiDemo(String fromUserId, String text) {
        Matcher m = MULTI_CITIES.matcher(text);
        if (!m.find()) {
            safeSendText(fromUserId, "用法：#multi 城市1 城市2 [城市3...]\n例如：#multi 北京 上海 广州\n"
                    + "（演示多工具并行协作：多城市天气+时间同时查询，LLM 汇总比较）");
            return;
        }
        String[] cities = m.group(1).trim().split("\\s+");
        if (cities.length < 2) {
            safeSendText(fromUserId, "请至少提供 2 个城市，例如：#multi 北京 上海");
            return;
        }

        try {
            safeSendText(fromUserId, "▶ 多工具并行协作演示\n并行阶段：同时查询 "
                    + cities.length + " 个城市天气 + 当前时间（共 " + (cities.length + 1) + " 个工具任务）...");
            long start = System.currentTimeMillis();

            // ---- 并行阶段：每城市一个天气任务 + 一个时间任务，虚拟线程并发执行 ----
            List<CompletableFuture<String>> futures = new ArrayList<>();
            List<String> taskNames = new ArrayList<>();
            for (String city : cities) {
                taskNames.add("get_weather(" + city + ")");
                futures.add(CompletableFuture.supplyAsync(
                        () -> toolRegistry.execute("get_weather",
                                mapper.createObjectNode().put("city", city).put("days", 1)),
                        TOOL_EXECUTOR));
            }
            taskNames.add("get_datetime");
            futures.add(CompletableFuture.supplyAsync(
                    () -> toolRegistry.execute("get_datetime", mapper.createObjectNode()),
                    TOOL_EXECUTOR));

            // 等待全部完成，按提交顺序收集结果
            List<String> results = futures.stream().map(CompletableFuture::join).toList();
            long elapsed = System.currentTimeMillis() - start;

            StringBuilder parallelResult = new StringBuilder();
            for (int i = 0; i < taskNames.size(); i++) {
                parallelResult.append("[").append(taskNames.get(i)).append("]\n")
                        .append(results.get(i)).append("\n\n");
            }
            safeSendText(fromUserId, "并行阶段完成 ✅（" + taskNames.size() + " 个任务总耗时 "
                    + elapsed + " ms，串行预计需 ~" + (elapsed * taskNames.size() / Math.max(elapsed, 1))
                    + " ms 级别）\n\n" + parallelResult + "汇总阶段：LLM 综合比较生成结论...");

            // ---- 汇总阶段：依赖并行阶段全部结果（串行） ----
            String summary = llmClient.chat(List.of(
                    ChatMessage.system("你是数据分析师，根据多个城市的天气数据和当前时间，"
                            + "用中文简洁比较各城市天气差异，指出最热/最冷/最适合出行的城市，120字以内。"),
                    ChatMessage.user("工具执行结果：\n" + parallelResult)));
            safeSendText(fromUserId, "汇总完成 ✅\n" + summary);
            log.info("多工具并行协作演示完成：{} 个城市，并行耗时 {} ms", cities.length, elapsed);
        } catch (Exception e) {
            log.error("多工具并行协作演示失败", e);
            safeSendText(fromUserId, "并行协作演示失败：" + e.getMessage());
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
            list.add(ChatMessage.system(BASE_SYSTEM_PROMPT));
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
