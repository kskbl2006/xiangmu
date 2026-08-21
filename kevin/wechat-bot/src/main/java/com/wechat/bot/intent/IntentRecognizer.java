package com.wechat.bot.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.bot.llm.LlmClient;
import com.wechat.bot.llm.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图识别器：判断用户消息的处理意图。
 * <p>采用「规则优先 + LLM 兜底」的两级策略：
 * <ol>
 *   <li>规则层：正则快速匹配高置信度关键词（天气/时间/画图/语音等）</li>
 *   <li>LLM 层：规则无法判断时，让大模型输出 JSON 分类结果</li>
 * </ol>
 */
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    /** 支持的意图类型 */
    public enum Intent {
        /** 普通闲聊/知识问答 */
        CHAT,
        /** 天气查询（交给 Function Calling 工具） */
        WEATHER,
        /** 时间日期查询（交给 Function Calling 工具） */
        DATETIME,
        /** 文生图请求 */
        IMAGE_GEN,
        /** 语音回复请求 */
        VOICE_REPLY,
        /** 多步工具链式调用演示（串行） */
        CHAIN_DEMO,
        /** 多工具并行协作演示 */
        MULTI_DEMO
    }

    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "天气|气温|温度多少|下雨|下雪|阴晴|降水|降温|升温|热不热|冷不冷|带伞|穿什么");
    private static final Pattern DATETIME_PATTERN = Pattern.compile(
            "几点|时间|日期|星期|礼拜|几号|今天|明天|后天|昨天|多少号");
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "画一|画个|画张|生成.*图|来.*图片|帮我画| image|picture|draw ");
    private static final Pattern VOICE_PATTERN = Pattern.compile(
            "语音说|用语音|发语音|语音回复");
    private static final Pattern CHAIN_PATTERN = Pattern.compile(
            "#chain|#链式");
    private static final Pattern MULTI_PATTERN = Pattern.compile(
            "#multi|#并行");

    private static final String SYSTEM_PROMPT = """
            你是一个意图分类器。请判断用户消息属于以下哪种意图，只输出 JSON：
            {"intent": "CHAT|WEATHER|DATETIME|IMAGE_GEN|VOICE_REPLY"}
            分类标准：
            - WEATHER：询问天气、气温、是否下雨下雪、穿衣建议等
            - DATETIME：询问时间、日期、星期几等
            - IMAGE_GEN：要求生成、绘制图片
            - VOICE_REPLY：明确要求用语音形式回复
            - CHAT：其他闲聊、知识问答、求助等
            只输出 JSON，不要任何解释。""";

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntentRecognizer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 识别用户消息意图。
     */
    public Intent recognize(String text) {
        if (text == null || text.isBlank()) {
            return Intent.CHAT;
        }

        // 1. 规则层：高置信度关键词直接命中
        Intent byRule = recognizeByRule(text);
        if (byRule != null) {
            log.info("意图识别（规则命中）：{} -> {}", mask(text), byRule);
            return byRule;
        }

        // 2. LLM 层：模糊语义交给大模型分类
        try {
            String answer = llmClient.chat(List.of(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(text)));
            Intent intent = parseIntent(answer);
            log.info("意图识别（LLM 分类）：{} -> {}", mask(text), intent);
            return intent;
        } catch (Exception e) {
            log.warn("LLM 意图识别失败，回退为 CHAT：{}", e.getMessage());
            return Intent.CHAT;
        }
    }

    private Intent recognizeByRule(String text) {
        if (CHAIN_PATTERN.matcher(text).find()) {
            return Intent.CHAIN_DEMO;
        }
        if (MULTI_PATTERN.matcher(text).find()) {
            return Intent.MULTI_DEMO;
        }
        if (WEATHER_PATTERN.matcher(text).find()) {
            return Intent.WEATHER;
        }
        if (IMAGE_PATTERN.matcher(text).find()) {
            return Intent.IMAGE_GEN;
        }
        if (VOICE_PATTERN.matcher(text).find()) {
            return Intent.VOICE_REPLY;
        }
        if (DATETIME_PATTERN.matcher(text).find()) {
            return Intent.DATETIME;
        }
        return null;
    }

    private Intent parseIntent(String answer) {
        try {
            // 容错：截取第一个 { 到最后一个 }
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(answer.substring(start, end + 1));
                return Intent.valueOf(node.path("intent").asText("CHAT"));
            }
        } catch (Exception e) {
            log.warn("意图 JSON 解析失败：{}", answer);
        }
        return Intent.CHAT;
    }

    /** 日志脱敏：只保留前 20 字符 */
    private String mask(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }
}
