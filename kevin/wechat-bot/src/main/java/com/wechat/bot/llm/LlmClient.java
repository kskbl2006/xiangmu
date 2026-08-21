package com.wechat.bot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.bot.config.AppConfig;
import com.wechat.bot.llm.model.ChatMessage;
import com.wechat.bot.llm.model.ToolCall;
import com.wechat.bot.llm.model.ToolDefinition;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM 客户端（OpenAI 兼容协议，默认对接智谱开放平台）。
 * <p>能力：
 * <ul>
 *   <li>chat：普通对话补全</li>
 *   <li>chatWithTools：带 Function Calling 的对话，返回文本或工具调用请求</li>
 *   <li>describeImage：多模态图片理解</li>
 *   <li>generateImage：文生图，返回图片二进制</li>
 *   <li>textToSpeech：文本转语音，返回音频二进制</li>
 * </ul>
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AppConfig config;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(AppConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(config.llmTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 普通对话补全。
     */
    public String chat(List<ChatMessage> messages) throws IOException {
        return chatWithTools(messages, null).content();
    }

    /**
     * 带工具定义的对话补全：模型可能返回最终文本，也可能返回工具调用请求。
     * Function Calling 的多轮循环由上层调用方驱动（见 BotMessageHandler）。
     */
    public ChatResult chatWithTools(List<ChatMessage> messages,
                                    List<ToolDefinition> tools) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.llmChatModel());
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", mapper.valueToTree(tools));
            body.put("tool_choice", "auto");
        }

        JsonNode resp = post(config.llmBaseUrl() + "/chat/completions", body);
        JsonNode choice = resp.path("choices").path(0).path("message");
        if (choice.isMissingNode()) {
            throw new IOException("LLM 响应缺少 choices: " + resp);
        }

        String content = choice.path("content").asText(null);
        List<ToolCall> toolCalls = null;
        JsonNode callsNode = choice.get("tool_calls");
        if (callsNode != null && callsNode.isArray() && !callsNode.isEmpty()) {
            toolCalls = mapper.convertValue(callsNode,
                    mapper.getTypeFactory().constructCollectionType(List.class, ToolCall.class));
        }
        log.debug("LLM 返回：content={}, toolCalls={}", content,
                toolCalls == null ? 0 : toolCalls.size());
        return new ChatResult(content, toolCalls);
    }

    /**
     * 多模态图片理解：返回对图片的自然语言描述。
     */
    public String describeImage(String prompt, String imageBase64DataUrl) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.llmVisionModel());
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode parts = userMsg.putArray("content");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt == null || prompt.isBlank() ? "请描述这张图片的内容" : prompt);
        ObjectNode imgPart = parts.addObject();
        imgPart.put("type", "image_url");
        ObjectNode urlNode = imgPart.putObject("image_url");
        urlNode.put("url", imageBase64DataUrl);

        JsonNode resp = post(config.llmBaseUrl() + "/chat/completions", body);
        return resp.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * 文生图：返回生成图片的二进制内容。
     */
    public byte[] generateImage(String prompt) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.llmImageModel());
        body.put("prompt", prompt);

        JsonNode resp = post(config.llmBaseUrl() + "/images/generations", body);
        String url = resp.path("data").path(0).path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new IOException("文生图响应缺少图片 URL: " + resp);
        }
        log.info("文生图成功，开始下载图片：{}", url);
        return downloadBinary(url);
    }

    /**
     * 文本转语音：返回音频文件二进制（mp3）。
     */
    public byte[] textToSpeech(String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.llmTtsModel());
        body.put("input", text);
        body.put("voice", "tongtong");

        Request request = new Request.Builder()
                .url(config.llmBaseUrl() + "/audio/speech")
                .header("Authorization", authHeader())
                .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String err = response.body() == null ? "no body"
                        : response.body().string();
                throw new IOException("TTS 请求失败 HTTP " + response.code() + ": " + err);
            }
            return response.body().bytes();
        }
    }

    // ---------------- 内部方法 ----------------

    /**
     * 构造 Authorization 头：请求前校验 Key 合法性，
     * 避免非法字符触发 OkHttp "Unexpected char"晦涩报错。
     */
    private String authHeader() throws IOException {
        String key = config.llmApiKey();
        if (key.isBlank() || key.contains("API_KEY")) {
            throw new IOException("llm.api-key 未配置或仍为占位符："
                    + "请在 src/main/resources/application.properties 填入智谱 API Key"
                    + "（open.bigmodel.cn 免费申请）后重启机器人");
        }
        return "Bearer " + key;
    }

    private JsonNode post(String url, ObjectNode body) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.error("LLM 请求失败 HTTP {}：{}", response.code(), text);
                throw new IOException("LLM 请求失败 HTTP " + response.code() + ": " + text);
            }
            JsonNode node = mapper.readTree(text);
            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                throw new IOException("LLM 返回错误: " + error);
            }
            return node;
        }
    }

    private byte[] downloadBinary(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载图片失败 HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    /**
     * 对话结果：最终文本回复或工具调用请求列表（二者互斥）。
     */
    public record ChatResult(String content, List<ToolCall> toolCalls) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
