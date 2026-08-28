package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.llm.QwenClient;
import com.github.wechat.ilink.bot.tool.CalculatorTool;
import com.github.wechat.ilink.bot.tool.DateTimeTool;
import com.github.wechat.ilink.bot.tool.ToolRegistry;
import com.github.wechat.ilink.bot.tool.WeatherTool;
import com.github.wechat.ilink.bot.weather.Weather;
import java.util.List;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 独立测试真实模型的 Function Calling，不连接微信。 */
public final class ToolCallingSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(ToolCallingSmokeTest.class);

  private ToolCallingSmokeTest() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    ObjectMapper objectMapper = new ObjectMapper();
    ToolRegistry registry =
        new ToolRegistry(
            objectMapper,
            List.of(
                new WeatherTool(new Weather(config), objectMapper),
                new CalculatorTool(objectMapper),
                new DateTimeTool(objectMapper)));
    String prompt =
        args.length == 0
            ? "请先查询苏州市当前温度，拿到真实摄氏温度后，再调用计算器按 C*9/5+32 换算为华氏温度，最后汇总。必须按顺序调用两个工具。"
            : String.join(" ", args);
    QwenClient.ToolChatResult result =
        new QwenClient(config).chatWithTools(List.of(), prompt, registry);
    List<String> called = result.executions().stream().map(QwenClient.ToolExecution::name).toList();
    if (args.length == 0) verifyDependentTemperatureChain(result, objectMapper);
    log.info("Tool smoke test succeeded; calledTools={}; executions={}; answer={}",
        called, result.executions(), result.answer());

  }

  private static void verifyDependentTemperatureChain(
      QwenClient.ToolChatResult result, ObjectMapper objectMapper) throws Exception {
    int weatherIndex = indexOf(result, "weather_query");
    int calculatorIndex = indexOf(result, "calculator");
    if (weatherIndex < 0 || calculatorIndex <= weatherIndex) {
      throw new IllegalStateException(
          "Expected weather_query before calculator, but model called: "
              + result.executions().stream().map(QwenClient.ToolExecution::name).toList());
    }
    QwenClient.ToolExecution weather = result.executions().get(weatherIndex);
    QwenClient.ToolExecution calculator = result.executions().get(calculatorIndex);
    if (!weather.success() || !calculator.success()) {
      throw new IllegalStateException("One or more tools failed: " + result.executions());
    }
    if (calculator.round() <= weather.round()) {
      throw new IllegalStateException(
          "Calculator must run in a later model round after receiving the weather result");
    }
    BigDecimal celsius =
        objectMapper.readTree(weather.result()).path("temperature_c").decimalValue();
    BigDecimal actualFahrenheit =
        new BigDecimal(objectMapper.readTree(calculator.result()).path("result").asText());
    BigDecimal expectedFahrenheit =
        celsius.multiply(BigDecimal.valueOf(9))
            .divide(BigDecimal.valueOf(5))
            .add(BigDecimal.valueOf(32));
    if (actualFahrenheit.compareTo(expectedFahrenheit) != 0) {
      throw new IllegalStateException(
          "Calculator did not use weather temperature: celsius="
              + celsius
              + ", expectedFahrenheit="
              + expectedFahrenheit
              + ", actualFahrenheit="
              + actualFahrenheit);
    }
    if (result.answer().contains("<tool_call>")) {
      throw new IllegalStateException("Raw tool protocol leaked into final answer");
    }
  }

  private static int indexOf(QwenClient.ToolChatResult result, String toolName) {
    for (int i = 0; i < result.executions().size(); i++) {
      if (toolName.equals(result.executions().get(i).name())) return i;
    }
    return -1;
  }
}
