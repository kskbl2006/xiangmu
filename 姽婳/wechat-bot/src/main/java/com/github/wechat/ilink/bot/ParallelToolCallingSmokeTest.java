package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.llm.QwenClient;
import com.github.wechat.ilink.bot.tool.CalculatorTool;
import com.github.wechat.ilink.bot.tool.DateTimeTool;
import com.github.wechat.ilink.bot.tool.ToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 使用真实模型测试同一轮中的两个独立工具调用。 */
public final class ParallelToolCallingSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(ParallelToolCallingSmokeTest.class);

  private ParallelToolCallingSmokeTest() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    ObjectMapper objectMapper = new ObjectMapper();
    ToolRegistry registry =
        new ToolRegistry(
            objectMapper,
            List.of(new CalculatorTool(objectMapper), new DateTimeTool(objectMapper)));
    String prompt =
        "请完成两个互不依赖的任务：查询中国当前日期时间；使用计算器计算 (12+8)*3。"
            + "请在同一轮并行调用两个工具，最后简洁汇总。";

    QwenClient.ToolChatResult result =
        new QwenClient(config).chatWithTools(List.of(), prompt, registry);
    Set<String> names =
        result.executions().stream()
            .map(QwenClient.ToolExecution::name)
            .collect(Collectors.toSet());
    Set<Integer> rounds =
        result.executions().stream()
            .map(QwenClient.ToolExecution::round)
            .collect(Collectors.toSet());
    if (!names.containsAll(Set.of("datetime_query", "calculator"))) {
      throw new IllegalStateException("Expected datetime and calculator tools, got: " + names);
    }
    if (rounds.size() != 1) {
      throw new IllegalStateException("Independent tools were not called in the same round: " + rounds);
    }
    if (result.executions().stream().anyMatch(execution -> !execution.success())) {
      throw new IllegalStateException("One or more parallel tools failed: " + result.executions());
    }
    log.info(
        "Parallel tool smoke test succeeded; executions={}; answer={}",
        result.executions(),
        result.answer());
  }
}
