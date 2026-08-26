package com.github.wechat.ilink.bot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.memory.ConversationMemoryStore.Turn;
import com.github.wechat.ilink.bot.tool.ToolRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal client for DashScope's OpenAI-compatible chat-completions endpoint. */
public final class QwenClient {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final Logger log = LoggerFactory.getLogger(QwenClient.class);
  private static final int MAX_TOOL_ROUNDS = 5;

  /** Ordered audit entry for one local tool execution. Round is one-based. */
  public record ToolExecution(
      int round,
      String callId,
      String name,
      String arguments,
      String result,
      boolean success,
      long durationMillis) {}

  public record ToolChatResult(String answer, List<ToolExecution> executions) {}

  private record ToolRequest(String callId, String name, String arguments) {}

  private final AppConfig config;
  private final OkHttpClient httpClient;
  private final String chatCompletionsUrl;
  private final String apiKeyOverride;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public QwenClient(AppConfig config) {
    this(
        config,
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(90))
            .writeTimeout(Duration.ofSeconds(90))
            .callTimeout(Duration.ofSeconds(90))
            .build(),
        config.getDashscopeBaseUrl() + "/chat/completions");
  }

  QwenClient(AppConfig config, OkHttpClient httpClient) {
    this(config, httpClient, config.getDashscopeBaseUrl() + "/chat/completions", null);
  }

  QwenClient(AppConfig config, OkHttpClient httpClient, String chatCompletionsUrl) {
    this(config, httpClient, chatCompletionsUrl, null);
  }

  QwenClient(
      AppConfig config, OkHttpClient httpClient, String chatCompletionsUrl, String apiKeyOverride) {
    this.config = config;
    this.httpClient = httpClient;
    this.chatCompletionsUrl = chatCompletionsUrl;
    this.apiKeyOverride = apiKeyOverride;
  }

  public String smokeTest() throws IOException {
    return chat("Reply with exactly: OK");
  }

  public String chat(String userMessage) throws IOException {
    return chat(List.of(), userMessage);
  }

  public String chat(List<Turn> history, String userMessage) throws IOException {
    if (userMessage == null || userMessage.isBlank()) {
      throw new IllegalArgumentException("user message must not be blank");
    }
    ObjectNode requestBody = newRequestBody(history);
    requestBody
        .withArray("messages")
        .addObject()
        .put("role", "user")
        .put("content", userMessage);
    return execute(requestBody);
  }

  /** Requests a JSON object under a task-specific system instruction. */
  public String chatJson(String systemInstruction, String userMessage) throws IOException {
    if (systemInstruction == null || systemInstruction.isBlank()) {
      throw new IllegalArgumentException("system instruction must not be blank");
    }
    if (userMessage == null || userMessage.isBlank()) {
      throw new IllegalArgumentException("user message must not be blank");
    }
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", config.getQwenModel());
    requestBody.put("stream", false);
    requestBody.put("enable_thinking", false);
    requestBody.putObject("response_format").put("type", "json_object");
    ArrayNode messages = requestBody.putArray("messages");
    messages.addObject().put("role", "system").put("content", systemInstruction);
    messages.addObject().put("role", "user").put("content", userMessage);
    return execute(requestBody);
  }

  public ToolChatResult chatWithTools(
      List<Turn> history, String userMessage, ToolRegistry registry) throws IOException {
    if (userMessage == null || userMessage.isBlank()) {
      throw new IllegalArgumentException("user message must not be blank");
    }
    if (registry == null || registry.size() == 0) {
      throw new IllegalArgumentException("at least one tool is required");
    }
    ObjectNode requestBody = newRequestBody(history);
    ArrayNode messages = requestBody.withArray("messages");
    messages.addObject().put("role", "user").put("content", userMessage);
    requestBody.set("tools", registry.definitions());
    requestBody.put("tool_choice", "auto");
    requestBody.put("parallel_tool_calls", true);
    requestBody.put("enable_thinking", false);

    List<ToolExecution> executions = new ArrayList<>();
    for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
      ObjectNode assistant = executeMessage(requestBody);
      JsonNode toolCalls = assistant.path("tool_calls");
      if (!toolCalls.isArray() || toolCalls.isEmpty()) {
        JsonNode content = assistant.path("content");
        String answer = content.isTextual() ? content.asText().trim() : "";
        if (answer.isEmpty()) {
          throw new IOException("DashScope returned neither content nor tool calls");
        }
        return new ToolChatResult(answer, List.copyOf(executions));
      }

      messages.add(assistant.deepCopy());
      List<ToolRequest> toolRequests = new ArrayList<>();
      for (JsonNode toolCall : toolCalls) {
        String callId = toolCall.path("id").asText();
        String name = toolCall.path("function").path("name").asText();
        if (callId.isBlank() || name.isBlank()) {
          throw new IOException("DashScope returned an invalid tool call without id or function name");
        }
        JsonNode argumentsNode = toolCall.path("function").path("arguments");
        String arguments =
            argumentsNode.isTextual()
                ? argumentsNode.asText()
                : objectMapper.writeValueAsString(argumentsNode);
        toolRequests.add(new ToolRequest(callId, name, arguments));
      }

      List<ToolExecution> roundExecutions = executeToolRound(round + 1, toolRequests, registry);
      executions.addAll(roundExecutions);
      for (ToolExecution execution : roundExecutions) {
        messages
            .addObject()
            .put("role", "tool")
            .put("tool_call_id", execution.callId())
            .put("content", execution.result());
      }
    }
    throw new IOException("Tool calling exceeded " + MAX_TOOL_ROUNDS + " rounds");
  }

  private List<ToolExecution> executeToolRound(
      int round, List<ToolRequest> requests, ToolRegistry registry) throws IOException {
    if (requests.size() == 1) {
      return List.of(executeTool(round, requests.get(0), registry));
    }
    log.info("Executing {} independent LLM tools in parallel for round {}", requests.size(), round);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ToolExecution>> futures = new ArrayList<>();
      for (ToolRequest request : requests) {
        futures.add(executor.submit(() -> executeTool(round, request, registry)));
      }
      List<ToolExecution> results = new ArrayList<>();
      for (Future<ToolExecution> future : futures) {
        try {
          results.add(future.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Parallel tool execution was interrupted", e);
        } catch (ExecutionException e) {
          throw new IOException("Parallel tool execution failed unexpectedly", e.getCause());
        }
      }
      return List.copyOf(results);
    }
  }

  private ToolExecution executeTool(int round, ToolRequest request, ToolRegistry registry) {
    String result;
    boolean success = false;
    long startedAt = System.nanoTime();
    try {
      result = registry.execute(request.name(), request.arguments());
      success = true;
      log.info(
          "Executed LLM tool: round={}, name={}, callId={}",
          round,
          request.name(),
          request.callId());
    } catch (Exception toolError) {
      ObjectNode error = objectMapper.createObjectNode();
      error.put("ok", false);
      error.put("error_type", toolError.getClass().getSimpleName());
      error.put(
          "error",
          toolError.getMessage() == null ? "tool execution failed" : toolError.getMessage());
      result = error.toString();
      log.warn(
          "Tool execution failed: round={}, name={}, error={}",
          round,
          request.name(),
          toolError.getMessage());
    }
    long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    return new ToolExecution(
        round,
        request.callId(),
        request.name(),
        request.arguments(),
        result,
        success,
        durationMillis);
  }

  /** Uses the configured vision-capable Qwen model to describe an image and extract visible text. */
  public String chatWithImage(byte[] imageBytes, String mimeType, String userPrompt) throws IOException {
    return chatWithImage(List.of(), imageBytes, mimeType, userPrompt);
  }

  public String chatWithImage(
      List<Turn> history, byte[] imageBytes, String mimeType, String userPrompt) throws IOException {
    if (imageBytes == null || imageBytes.length == 0) {
      throw new IllegalArgumentException("image bytes must not be empty");
    }
    if (mimeType == null || !mimeType.startsWith("image/")) {
      throw new IllegalArgumentException("an image MIME type is required");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("image prompt must not be blank");
    }
    ObjectNode requestBody = newRequestBody(history);
    ArrayNode content =
        requestBody.withArray("messages").addObject().put("role", "user").putArray("content");
    content.addObject().put("type", "text").put("text", userPrompt);
    content
        .addObject()
        .put("type", "image_url")
        .putObject("image_url")
        .put("url", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes));
    return execute(requestBody);
  }

  private ObjectNode newRequestBody(List<Turn> history) {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", config.getQwenModel());
    requestBody.put("stream", false);
    ArrayNode messages = requestBody.putArray("messages");
    messages
        .addObject()
        .put("role", "system")
        .put("content", config.getWeChatSystemPrompt());
    if (history != null) {
      for (Turn turn : history) {
        if (turn == null) {
          continue;
        }
        messages.addObject().put("role", "user").put("content", turn.user());
        messages.addObject().put("role", "assistant").put("content", turn.assistant());
      }
    }
    return requestBody;
  }

  private String execute(ObjectNode requestBody) throws IOException {
    ObjectNode message = executeMessage(requestBody);
    JsonNode content = message.path("content");
    if (content.isMissingNode() || content.asText().trim().isEmpty()) {
      throw new IOException("DashScope response does not contain choices[0].message.content");
    }
    return content.asText();
  }

  private ObjectNode executeMessage(ObjectNode requestBody) throws IOException {
    String requestJson = objectMapper.writeValueAsString(requestBody);
    Request request =
        new Request.Builder()
            .url(chatCompletionsUrl)
            .header(
                "Authorization",
                "Bearer "
                    + (apiKeyOverride == null
                        ? config.requireDashscopeApiKey()
                        : apiKeyOverride))
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestJson, JSON))
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String body = responseBody == null ? "" : responseBody.string();
      if (!response.isSuccessful()) {
        throw new IOException("DashScope returned HTTP " + response.code() + ": " + abbreviate(body));
      }
      JsonNode root = objectMapper.readTree(body);
      JsonNode message = root.path("choices").path(0).path("message");
      if (!message.isObject()) {
        throw new IOException("DashScope response does not contain choices[0].message");
      }
      return (ObjectNode) message.deepCopy();
    }
  }

  private static String abbreviate(String value) {
    return value.length() <= 300 ? value : value.substring(0, 300) + "...";
  }
}
