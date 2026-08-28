package com.github.wechat.ilink.bot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.tool.BotTool;
import com.github.wechat.ilink.bot.tool.CalculatorTool;
import com.github.wechat.ilink.bot.tool.ToolRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class QwenClientToolCallingTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void completesAssistantToolExecutionRoundTrip() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                "id":"call_1","type":"function","function":{
                  "name":"calculator","arguments":"{\\\"expression\\\":\\\"(2+3)*4\\\"}"
                }}]}}]}
              """));
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":"计算结果是 20。"}}]}
              """));

      QwenClient client =
          new QwenClient(
              AppConfig.fromEnvironment(),
              new OkHttpClient(),
              server.url("/chat/completions").toString(),
              "test-key");
      ToolRegistry registry =
          new ToolRegistry(objectMapper, List.of(new CalculatorTool(objectMapper)));

      QwenClient.ToolChatResult result =
          client.chatWithTools(List.of(), "请计算 (2+3)*4", registry);

      assertEquals("计算结果是 20。", result.answer());
      assertEquals(List.of("calculator"), result.executions().stream().map(e -> e.name()).toList());
      assertTrue(result.executions().get(0).success());
      assertEquals(1, result.executions().get(0).round());

      var firstRequest =
          objectMapper.readTree(server.takeRequest().getBody().readUtf8());
      assertEquals("calculator", firstRequest.path("tools").path(0).path("function").path("name").asText());
      assertEquals("auto", firstRequest.path("tool_choice").asText());
      assertTrue(firstRequest.path("parallel_tool_calls").asBoolean());

      var secondRequest =
          objectMapper.readTree(server.takeRequest().getBody().readUtf8());
      var toolMessage = secondRequest.path("messages").get(secondRequest.path("messages").size() - 1);
      assertEquals("tool", toolMessage.path("role").asText());
      assertEquals("call_1", toolMessage.path("tool_call_id").asText());
    }
  }

  @Test
  void executesIndependentCallsFromTheSameRoundInParallelAndPreservesOrder() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"independent_a","arguments":"{}"}},
                {"id":"call_b","type":"function","function":{"name":"independent_b","arguments":"{}"}}
              ]}}]}
              """));
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":"两个独立任务均已完成。"}}]}
              """));

      CountDownLatch bothStarted = new CountDownLatch(2);
      ToolRegistry registry =
          new ToolRegistry(
              objectMapper,
              List.of(
                  barrierTool("independent_a", "{\"value\":\"A\"}", bothStarted),
                  barrierTool("independent_b", "{\"value\":\"B\"}", bothStarted)));
      QwenClient client =
          new QwenClient(
              AppConfig.fromEnvironment(),
              new OkHttpClient(),
              server.url("/chat/completions").toString(),
              "test-key");

      QwenClient.ToolChatResult result =
          client.chatWithTools(List.of(), "同时执行两个独立任务", registry);

      assertEquals(
          List.of("independent_a", "independent_b"),
          result.executions().stream().map(QwenClient.ToolExecution::name).toList());
      assertTrue(result.executions().stream().allMatch(QwenClient.ToolExecution::success));
      assertEquals(List.of(1, 1),
          result.executions().stream().map(QwenClient.ToolExecution::round).toList());

      server.takeRequest();
      JsonNode finalRequest = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
      JsonNode messages = finalRequest.path("messages");
      assertEquals("call_a", messages.get(messages.size() - 2).path("tool_call_id").asText());
      assertEquals("call_b", messages.get(messages.size() - 1).path("tool_call_id").asText());
    }
  }

  @Test
  void executesDependentWeatherThenTemperatureConversionChain() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                "id":"call_weather","type":"function","function":{
                  "name":"weather_query","arguments":"{\\\"location\\\":\\\"苏州市\\\"}"
                }}]}}]}
              """));
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                "id":"call_convert","type":"function","function":{
                  "name":"calculator","arguments":"{\\\"expression\\\":\\\"30*9/5+32\\\"}"
                }}]}}]}
              """));
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":"苏州当前 30℃，换算为 86℉。"}}]}
              """));

      QwenClient client =
          new QwenClient(
              AppConfig.fromEnvironment(),
              new OkHttpClient(),
              server.url("/chat/completions").toString(),
              "test-key");
      ToolRegistry registry =
          new ToolRegistry(objectMapper, List.of(fixedWeatherTool(), new CalculatorTool(objectMapper)));

      QwenClient.ToolChatResult result =
          client.chatWithTools(List.of(), "查询苏州温度，再换算成华氏温度", registry);

      assertEquals(List.of("weather_query", "calculator"),
          result.executions().stream().map(QwenClient.ToolExecution::name).toList());
      assertEquals(List.of(1, 2),
          result.executions().stream().map(QwenClient.ToolExecution::round).toList());
      assertTrue(result.executions().stream().allMatch(QwenClient.ToolExecution::success));
      assertEquals("30*9/5+32",
          objectMapper.readTree(result.executions().get(1).arguments()).path("expression").asText());
      assertEquals("86",
          objectMapper.readTree(result.executions().get(1).result()).path("result").asText());
      assertFalse(result.answer().contains("<tool_call>"));

      server.takeRequest(); // 首次请求让模型选择天气工具。
      JsonNode secondRequest = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
      JsonNode weatherResult = secondRequest.path("messages").get(secondRequest.path("messages").size() - 1);
      assertEquals("call_weather", weatherResult.path("tool_call_id").asText());
      assertEquals(30, objectMapper.readTree(weatherResult.path("content").asText()).path("temperature_c").asInt());

      JsonNode thirdRequest = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
      JsonNode calculatorResult = thirdRequest.path("messages").get(thirdRequest.path("messages").size() - 1);
      assertEquals("call_convert", calculatorResult.path("tool_call_id").asText());
    }
  }

  @Test
  void returnsStructuredToolErrorToModelAndKeepsConversationAlive() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          jsonResponse(
              """
              {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                "id":"bad_call","type":"function","function":{
                  "name":"calculator","arguments":"{\\\"expression\\\":\\\"1/0\\\"}"
                }}]}}]}
              """));
      server.enqueue(jsonResponse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"计算失败，请修改表达式。\"}}]}"));
      QwenClient client =
          new QwenClient(AppConfig.fromEnvironment(), new OkHttpClient(),
              server.url("/chat/completions").toString(), "test-key");
      ToolRegistry registry = new ToolRegistry(objectMapper, List.of(new CalculatorTool(objectMapper)));

      QwenClient.ToolChatResult result = client.chatWithTools(List.of(), "计算 1/0", registry);

      assertFalse(result.executions().get(0).success());
      JsonNode error = objectMapper.readTree(result.executions().get(0).result());
      assertFalse(error.path("ok").asBoolean());
      assertEquals("IllegalArgumentException", error.path("error_type").asText());
      assertEquals("计算失败，请修改表达式。", result.answer());
    }
  }

  private BotTool fixedWeatherTool() {
    return new BotTool() {
      @Override public String name() { return "weather_query"; }
      @Override public String description() { return "查询天气"; }
      @Override public ObjectNode parametersSchema() {
        return com.github.wechat.ilink.bot.tool.ToolSchemas.requiredString("location", "城市");
      }
      @Override public String execute(JsonNode arguments) {
        return "{\"location\":\"江苏 苏州\",\"condition\":\"晴\",\"temperature_c\":30}";
      }
    };
  }

  private BotTool barrierTool(String name, String result, CountDownLatch bothStarted) {
    return new BotTool() {
      @Override public String name() { return name; }
      @Override public String description() { return "independent concurrency test tool"; }
      @Override public ObjectNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
      }
      @Override public String execute(JsonNode arguments) throws Exception {
        bothStarted.countDown();
        if (!bothStarted.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("tools were not started concurrently");
        }
        return result;
      }
    };
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body);
  }
}
