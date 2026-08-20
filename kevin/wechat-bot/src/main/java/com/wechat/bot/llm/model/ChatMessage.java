package com.wechat.bot.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * LLM 对话消息模型（OpenAI 兼容格式）。
 * 支持 system / user / assistant / tool 四种角色，
 * assistant 消息可携带 toolCalls（模型发起的工具调用请求），
 * tool 消息携带 toolCallId 对应工具执行结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    public enum Role {
        @JsonProperty("system") SYSTEM,
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT,
        @JsonProperty("tool") TOOL
    }

    private String role;
    private String content;

    /** 图片理解消息的图像内容（OpenAI 多模态格式） */
    private List<Map<String, Object>> contentParts;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    private String name;

    public static ChatMessage system(String content) {
        ChatMessage m = new ChatMessage();
        m.role = Role.SYSTEM.name().toLowerCase();
        m.content = content;
        return m;
    }

    public static ChatMessage user(String content) {
        ChatMessage m = new ChatMessage();
        m.role = Role.USER.name().toLowerCase();
        m.content = content;
        return m;
    }

    /** 多模态用户消息：文本 + 图片（base64 data url） */
    public static ChatMessage userWithImage(String text, String imageDataUrl) {
        ChatMessage m = new ChatMessage();
        m.role = Role.USER.name().toLowerCase();
        m.contentParts = List.of(
                Map.of("type", "text", "text", text == null ? "描述这张图片" : text),
                Map.of("type", "image_url",
                        "image_url", Map.of("url", imageDataUrl)));
        return m;
    }

    public static ChatMessage assistant(String content) {
        ChatMessage m = new ChatMessage();
        m.role = Role.ASSISTANT.name().toLowerCase();
        m.content = content;
        return m;
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        ChatMessage m = new ChatMessage();
        m.role = Role.ASSISTANT.name().toLowerCase();
        m.content = null;
        m.toolCalls = toolCalls;
        return m;
    }

    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        ChatMessage m = new ChatMessage();
        m.role = Role.TOOL.name().toLowerCase();
        m.toolCallId = toolCallId;
        m.name = name;
        m.content = content;
        return m;
    }

    // ---------------- getters / setters ----------------

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @JsonProperty("content")
    public Object getContentForSerialization() {
        return contentParts != null ? contentParts : content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
